// Parallel chunk uploads to one session (step 6.3a): meta.json updates are serialised per uploadId by
// KeyedLock, so no record is lost when chunks land at the same time. Requests go to one listening
// server, each on its own connection, the way a client with several chunks in flight would send them.
import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fsp from 'node:fs/promises';
import request from 'supertest';
import { storagePaths } from '../src/storage.js';
import { chunksOf, createSession, freshDir, makeApp, putChunk, removeDir, sha256 } from './helpers.js';

const CS = 64 * 1024;
const range = (n) => Array.from({ length: n }, (_, i) => i);
let dir;
let server;

beforeEach(async () => {
  dir = await freshDir();
  server = makeApp(dir).listen(0);
});
afterEach(async () => {
  await new Promise((resolve) => server.close(resolve));
  await removeDir(dir);
});

const status = (id) => request(server).get(`/api/uploads/${id}`).expect(200);
const setFaults = (cfg) => request(server).put('/admin/faults').send(cfg).expect(200);

async function newSession(chunks) {
  const file = crypto.randomBytes(chunks * CS);
  const id = crypto.randomUUID();
  await createSession(request, server, id, file, { chunkSize: CS }).expect(201);
  return { id, file, parts: chunksOf(file, CS) };
}

async function assertCompletes(id, file) {
  const done = await request(server).post(`/api/uploads/${id}/complete`).expect(200);
  assert.equal(done.body.sha256, sha256(file));
  assert.equal(sha256(await fsp.readFile(storagePaths(dir).completedFile(id))), sha256(file));
}

describe('concurrent chunk uploads to one session', () => {
  test('8 chunks at once: all stored, status lists all 8, complete verifies', async () => {
    const { id, file, parts } = await newSession(8);
    const results = await Promise.all(parts.map((c, i) => putChunk(request, server, id, i, c)));
    for (const r of results) assert.equal(r.status, 200);
    assert.deepEqual(results.map((r) => r.body.status), Array(8).fill('stored'));
    assert.deepEqual((await status(id)).body.receivedChunks, range(8));
    const meta = JSON.parse(await fsp.readFile(storagePaths(dir).uploadMeta(id), 'utf8'));
    assert.equal(Object.keys(meta.chunks).length, 8);
    await assertCompletes(id, file);
  });

  test('concurrent duplicates of one chunk: one stored, the rest already_received, no temp files', async () => {
    const { id, parts } = await newSession(2);
    const results = await Promise.all(range(6).map(() => putChunk(request, server, id, 0, parts[0])));
    for (const r of results) assert.equal(r.status, 200);
    const statuses = results.map((r) => r.body.status).sort();
    assert.deepEqual(statuses, ['already_received', 'already_received', 'already_received', 'already_received', 'already_received', 'stored']);
    assert.deepEqual(await fsp.readdir(storagePaths(dir).chunksDir(id)), ['0.bin']);
    assert.equal((await request(server).get('/admin/stats')).body.dedupedChunks, 5);
    assert.deepEqual((await status(id)).body.receivedChunks, [0]);
  });

  test('status polling during concurrent uploads: every poll is a growing subset, the last lists all', async () => {
    const { id, file, parts } = await newSession(8);
    let uploading = true;
    const uploads = Promise.all(parts.map((c, i) => putChunk(request, server, id, i, c).expect(200))).finally(() => {
      uploading = false;
    });
    const polls = [];
    while (uploading) polls.push((await status(id)).body.receivedChunks);
    await uploads;
    polls.push((await status(id)).body.receivedChunks);
    assert.ok(polls.length >= 2);
    let previous = [];
    for (const seen of polls) {
      assert.deepEqual(seen, [...seen].sort((a, b) => a - b), 'sorted');
      for (const i of previous) assert.ok(seen.includes(i), `chunk ${i} vanished from a later poll`);
      previous = seen;
    }
    assert.deepEqual(polls.at(-1), range(8));
    await assertCompletes(id, file);
  });

  test('dropAfterProcess (seed 42, rate 0.5): dropped replies still persisted, resends already_received', async () => {
    const { id, file, parts } = await newSession(16);
    await setFaults({ enabled: true, seed: 42, dropAfterProcessRate: 0.5 });
    const settled = await Promise.allSettled(parts.map((c, i) => putChunk(request, server, id, i, c).expect(200)));
    const dropped = range(16).filter((i) => settled[i].status === 'rejected');
    for (const i of dropped) assert.match(String(settled[i].reason), /socket hang up|ECONNRESET/);
    const stats = (await request(server).get('/admin/stats')).body;
    assert.ok(dropped.length > 0 && dropped.length < 16, `expected some drops, got ${dropped.length}`);
    assert.equal(stats.faults.dropAfterProcess, dropped.length);

    await setFaults({ enabled: false });
    assert.deepEqual((await status(id)).body.receivedChunks, range(16));
    const resends = await Promise.all(dropped.map((i) => putChunk(request, server, id, i, parts[i]).expect(200)));
    assert.deepEqual(resends.map((r) => r.body.status), Array(dropped.length).fill('already_received'));
    await assertCompletes(id, file);
  });
});
