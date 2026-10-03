import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fsp from 'node:fs/promises';
import request from 'supertest';
import { storagePaths } from '../src/storage.js';
import { writeSeededFile } from '../src/seedData.js';
import { binaryParser, createSession, freshDir, makeApp, putChunk, removeDir, sha256 } from './helpers.js';

const CS = 1024;
let dir;
let app;

beforeEach(async () => {
  dir = await freshDir();
  app = makeApp(dir);
});
afterEach(() => removeDir(dir));

const setFaults = (cfg) => request(app).put('/admin/faults').send(cfg).expect(200);
const resetFaults = () => request(app).post('/admin/faults/reset').expect(200);

async function errorPattern(seed, n) {
  await setFaults({ enabled: true, seed, errorRate: 0.3 });
  const pattern = [];
  for (let i = 0; i < n; i++) pattern.push((await request(app).get('/api/files')).status);
  return pattern;
}

describe('fault config', () => {
  test('validates input and resets to defaults', async () => {
    const bad = await request(app).put('/admin/faults').send({ errorRate: 1.5 }).expect(400);
    assert.equal(bad.body.error, 'INVALID_REQUEST');
    await request(app).put('/admin/faults').send({ bogus: 1 }).expect(400);
    const cfg = await setFaults({ enabled: true, latencyMs: 5 });
    assert.equal(cfg.body.latencyMs, 5);
    const reset = await resetFaults();
    assert.equal(reset.body.enabled, false);
    assert.equal(reset.body.latencyMs, 0);
  });
});

describe('fault behaviour', () => {
  test('rates honoured with a fixed seed (deterministic, ~rate), counted in stats', async () => {
    const a = await errorPattern(42, 200);
    const b = await errorPattern(42, 200);
    assert.deepEqual(a, b, 'same seed → same fault sequence');
    const errors = a.filter((s) => s === 503).length;
    assert.ok(errors > 40 && errors < 80, `expected ~60 errors, got ${errors}`);
    assert.ok(a.every((s) => s === 503 || s === 200));
    const c = await errorPattern(7, 200);
    assert.notDeepEqual(a, c, 'different seed → different sequence');
    const stats = (await request(app).get('/admin/stats').expect(200)).body;
    assert.equal(stats.faults.error, a.filter((s) => s === 503).length * 2 + c.filter((s) => s === 503).length);
  });

  test('faults never applied to /admin or /health', async () => {
    await setFaults({ enabled: true, errorRate: 1, timeoutRate: 1, dropMidBodyRate: 1, latencyMs: 0 });
    for (let i = 0; i < 20; i++) {
      await request(app).get('/health').expect(200, { ok: true });
      await request(app).get('/admin/stats').expect(200);
      await request(app).get('/admin/faults').expect(200);
    }
    const res = await request(app).get('/api/files').expect(503);
    assert.equal(res.body.error, 'INJECTED_FAULT');
    const stats = (await request(app).get('/admin/stats').expect(200)).body;
    assert.equal(stats.apiRequests, 1);
    assert.equal(stats.faults.error, 1);
  });

  test('dropAfterProcess: socket destroyed, yet status lists the chunk and a resend is already_received', async () => {
    const file = crypto.randomBytes(2 * CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    await setFaults({ enabled: true, dropAfterProcessRate: 1 });
    const c0 = file.subarray(0, CS);
    await assert.rejects(putChunk(request, app, id, 0, c0), /socket hang up|ECONNRESET/);
    await resetFaults();
    const st = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.deepEqual(st.body.receivedChunks, [0]);
    const resend = await putChunk(request, app, id, 0, c0).expect(200);
    assert.equal(resend.body.status, 'already_received');
  });

  test('dropAfterProcess on complete: finalized anyway, status reports COMPLETED with sha256', async () => {
    const file = crypto.randomBytes(CS + 1);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    await putChunk(request, app, id, 0, file.subarray(0, CS)).expect(200);
    await putChunk(request, app, id, 1, file.subarray(CS)).expect(200);
    await setFaults({ enabled: true, dropAfterProcessRate: 1 });
    await assert.rejects(request(app).post(`/api/uploads/${id}/complete`), /socket hang up|ECONNRESET/);
    // Non-eligible routes (GET status) are unaffected even while the fault is on.
    const st = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.equal(st.body.state, 'COMPLETED');
    assert.equal(st.body.sha256, sha256(file));
  });

  test('dropMidBody on chunk upload: nothing stored, no temp left', async () => {
    const file = crypto.randomBytes(64 * CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file, { chunkSize: 64 * CS }).expect(201);
    await setFaults({ enabled: true, dropMidBodyRate: 1 });
    await assert.rejects(putChunk(request, app, id, 0, file));
    await resetFaults();
    const st = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.deepEqual(st.body.receivedChunks, []);
    await new Promise((r) => setTimeout(r, 50));
    assert.deepEqual(await fsp.readdir(storagePaths(dir).chunksDir(id)), []);
    assert.equal((await request(app).get('/admin/stats')).body.faults.dropMidBody, 0, 'reset zeroes stats');
  });

  test('dropMidBody on download: body truncated', async () => {
    await writeSeededFile(storagePaths(dir), 'big', 512 * 1024);
    await setFaults({ enabled: true, dropMidBodyRate: 1 });
    await assert.rejects(request(app).get('/api/files/big/content').buffer(true).parse(binaryParser));
    const stats = (await request(app).get('/admin/stats')).body;
    assert.equal(stats.faults.dropMidBody, 1);
  });

  test('corrupt: exactly one byte flipped in a download body', async () => {
    const { meta } = await writeSeededFile(storagePaths(dir), 'f', 50_000);
    const original = await fsp.readFile(storagePaths(dir).fileBin('f'));
    await setFaults({ enabled: true, corruptRate: 1 });
    const res = await request(app).get('/api/files/f/content').buffer(true).parse(binaryParser).expect(200);
    assert.equal(res.body.length, original.length);
    assert.notEqual(sha256(res.body), meta.sha256);
    let diffs = 0;
    for (let i = 0; i < original.length; i++) if (original[i] !== res.body[i]) diffs++;
    assert.equal(diffs, 1);
  });

  test('timeout: request never answered and nothing persisted', async () => {
    const file = crypto.randomBytes(CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    await setFaults({ enabled: true, timeoutRate: 1 });
    await assert.rejects(putChunk(request, app, id, 0, file).timeout(300), /Timeout/);
    await resetFaults();
    const st = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.deepEqual(st.body.receivedChunks, []);
  });

  test('latency and bandwidth slow requests down', async () => {
    await writeSeededFile(storagePaths(dir), 'f', 50_000);
    await setFaults({ enabled: true, latencyMs: 150 });
    let t0 = Date.now();
    await request(app).get('/api/files').expect(200);
    assert.ok(Date.now() - t0 >= 140, 'latency applied');
    await setFaults({ latencyMs: 0, bandwidthKbps: 800 }); // 100 kB/s → 50 kB ≈ 500 ms
    t0 = Date.now();
    const res = await request(app).get('/api/files/f/content').buffer(true).parse(binaryParser).expect(200);
    assert.equal(res.body.length, 50_000);
    assert.ok(Date.now() - t0 >= 400, `bandwidth applied (${Date.now() - t0} ms)`);
  });
});
