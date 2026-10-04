import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fsp from 'node:fs/promises';
import request from 'supertest';
import { storagePaths } from '../src/storage.js';
import { chunksOf, createSession, freshDir, makeApp, putChunk, removeDir, sha256 } from './helpers.js';

const CS = 1024;
let dir;
let app;
let paths;

beforeEach(async () => {
  dir = await freshDir();
  app = makeApp(dir);
  paths = storagePaths(dir);
});
afterEach(() => removeDir(dir));

async function uploadAndComplete(file, id = crypto.randomUUID()) {
  await createSession(request, app, id, file).expect(201);
  for (const [i, c] of chunksOf(file, CS).entries()) await putChunk(request, app, id, i, c).expect(200);
  await request(app).post(`/api/uploads/${id}/complete`).expect(200);
  return id;
}

async function expectInstant(file, id = crypto.randomUUID()) {
  const res = await createSession(request, app, id, file).expect(200);
  assert.deepEqual(res.body, {
    uploadId: id,
    totalChunks: Math.ceil(file.length / CS),
    chunkSize: CS,
    receivedChunks: chunksOf(file, CS).map((_, i) => i),
    state: 'COMPLETED',
    instant: true,
    sha256: sha256(file),
  });
  assert.deepEqual(await fsp.readFile(paths.completedFile(id)), file);
  return id;
}

const stats = async () => (await request(app).get('/admin/stats').expect(200)).body;

describe('instant upload', () => {
  test('second upload of identical content under a new id is instant', async () => {
    const file = crypto.randomBytes(3 * CS + 17);
    const first = await uploadAndComplete(file);
    const entry = JSON.parse(await fsp.readFile(paths.indexEntry(sha256(file)), 'utf8'));
    assert.equal(entry.sha256, sha256(file));
    assert.equal(entry.size, file.length);
    assert.equal(entry.path, `completed/${first}.bin`);
    const id = await expectInstant(file);
    const status = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.equal(status.body.state, 'COMPLETED');
    assert.equal(status.body.sha256, sha256(file));
    assert.deepEqual(status.body.receivedChunks, [0, 1, 2, 3]);
    // Repeating the create (lost response) is idempotent and still reports instant.
    const again = await createSession(request, app, id, file).expect(200);
    assert.equal(again.body.instant, true);
    assert.equal(again.body.state, 'COMPLETED');
    assert.equal((await stats()).instantUploads, 1);
  });

  test('same size, different content is not instant', async () => {
    const file = crypto.randomBytes(2 * CS);
    await uploadAndComplete(file);
    const other = crypto.randomBytes(2 * CS);
    const res = await createSession(request, app, crypto.randomUUID(), other).expect(201);
    assert.equal(res.body.instant, false);
    assert.equal(res.body.state, 'UPLOADING');
    assert.deepEqual(res.body.receivedChunks, []);
  });

  test('index entry deleted → not instant', async () => {
    const file = crypto.randomBytes(2 * CS);
    await uploadAndComplete(file);
    await fsp.rm(paths.indexEntry(sha256(file)));
    const res = await createSession(request, app, crypto.randomUUID(), file).expect(201);
    assert.equal(res.body.instant, false);
  });

  test('indexed file missing or wrong size → not instant, stale entry removed', async () => {
    const file = crypto.randomBytes(2 * CS);
    const first = await uploadAndComplete(file);
    await fsp.rm(paths.completedFile(first));
    const res = await createSession(request, app, crypto.randomUUID(), file).expect(201);
    assert.equal(res.body.instant, false);
    await assert.rejects(fsp.access(paths.indexEntry(sha256(file))));

    // A normal upload re-indexes; then truncate the indexed file.
    const second = await uploadAndComplete(file);
    await fsp.truncate(paths.completedFile(second), CS);
    const res2 = await createSession(request, app, crypto.randomUUID(), file).expect(201);
    assert.equal(res2.body.instant, false);
    await assert.rejects(fsp.access(paths.indexEntry(sha256(file))));
    assert.equal((await stats()).instantUploads, 0);
  });

  test('index survives a server restart', async () => {
    const file = crypto.randomBytes(2 * CS + 5);
    await uploadAndComplete(file);
    app = makeApp(dir);
    await expectInstant(file);
  });

  test('zero-byte files are never instant', async () => {
    const file = Buffer.alloc(0);
    await createSession(request, app, crypto.randomUUID(), file).expect(201);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    const res = await createSession(request, app, crypto.randomUUID(), file).expect(201);
    assert.equal(res.body.instant, false);
    assert.equal(res.body.state, 'UPLOADING');
  });

  test('concurrent sessions of identical content both end valid', async () => {
    const file = crypto.randomBytes(4 * CS);
    // Two full uploads finalising at the same time, then two instant creates at the same time.
    const [a, b] = [crypto.randomUUID(), crypto.randomUUID()];
    await Promise.all([createSession(request, app, a, file).expect(201), createSession(request, app, b, file).expect(201)]);
    for (const [i, c] of chunksOf(file, CS).entries()) {
      await Promise.all([putChunk(request, app, a, i, c).expect(200), putChunk(request, app, b, i, c).expect(200)]);
    }
    await Promise.all([
      request(app).post(`/api/uploads/${a}/complete`).expect(200),
      request(app).post(`/api/uploads/${b}/complete`).expect(200),
    ]);
    const [c, d] = [crypto.randomUUID(), crypto.randomUUID()];
    await Promise.all([expectInstant(file, c), expectInstant(file, d)]);
    for (const id of [a, b, c, d]) assert.deepEqual(await fsp.readFile(paths.completedFile(id)), file);
    assert.equal((await stats()).instantUploads, 2);
  });

  test('deleting one session leaves the other file intact', async () => {
    const file = crypto.randomBytes(3 * CS);
    const first = await uploadAndComplete(file);
    const instant = await expectInstant(file);
    await request(app).delete(`/api/uploads/${instant}`).expect(204);
    assert.deepEqual(await fsp.readFile(paths.completedFile(first)), file);
    await request(app).get(`/api/uploads/${instant}`).expect(404);

    // And the other way round: delete the original, the instant copy survives.
    const instant2 = await expectInstant(file);
    await request(app).delete(`/api/uploads/${first}`).expect(204);
    assert.deepEqual(await fsp.readFile(paths.completedFile(instant2)), file);
    // The index pointed at the deleted original: the next upload is a normal one.
    const res = await createSession(request, app, crypto.randomUUID(), file).expect(201);
    assert.equal(res.body.instant, false);
  });

  test('POST complete on an instant session is idempotent', async () => {
    const file = crypto.randomBytes(2 * CS + 1);
    await uploadAndComplete(file);
    const id = await expectInstant(file);
    const expected = { uploadId: id, state: 'COMPLETED', sha256: sha256(file), size: file.length };
    const r1 = await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    const r2 = await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    assert.deepEqual(r1.body, expected);
    assert.deepEqual(r2.body, expected);
  });

  test('chunk PUT to an instant session → 409', async () => {
    const file = crypto.randomBytes(2 * CS);
    await uploadAndComplete(file);
    const id = await expectInstant(file);
    const res = await putChunk(request, app, id, 0, file.subarray(0, CS)).expect(409);
    assert.equal(res.body.error, 'SESSION_COMPLETED');
  });

  test('instantUploads counts instant creates only, and resets with faults', async () => {
    const file = crypto.randomBytes(2 * CS);
    await uploadAndComplete(file);
    assert.equal((await stats()).instantUploads, 0);
    const id = await expectInstant(file);
    await createSession(request, app, id, file).expect(200); // idempotent repeat: not counted again
    await expectInstant(file);
    assert.equal((await stats()).instantUploads, 2);
    await request(app).post('/admin/faults/reset').expect(200);
    assert.equal((await stats()).instantUploads, 0);
  });
});
