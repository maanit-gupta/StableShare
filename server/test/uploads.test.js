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

beforeEach(async () => {
  dir = await freshDir();
  app = makeApp(dir);
});
afterEach(() => removeDir(dir));

async function uploadAll(file, id = crypto.randomUUID(), chunkSize = CS) {
  await createSession(request, app, id, file, { chunkSize }).expect(201);
  for (const [i, c] of chunksOf(file, chunkSize).entries()) await putChunk(request, app, id, i, c).expect(200);
  return id;
}

describe('create session', () => {
  test('idempotent create: same params 200, different params 409', async () => {
    const id = crypto.randomUUID();
    const file = crypto.randomBytes(3000);
    const first = await createSession(request, app, id, file).expect(201);
    assert.deepEqual(first.body, { uploadId: id, totalChunks: 3, chunkSize: CS, receivedChunks: [], state: 'UPLOADING' });
    const again = await createSession(request, app, id, file).expect(200);
    assert.deepEqual(again.body, first.body);
    const conflict = await createSession(request, app, id, file, { fileName: 'other.bin' }).expect(409);
    assert.equal(conflict.body.error, 'SESSION_CONFLICT');
    const conflictSize = await createSession(request, app, id, file.subarray(0, 10)).expect(409);
    assert.equal(conflictSize.body.error, 'SESSION_CONFLICT');
  });

  test('validation: bad uuid 400, bad body 400, too large 413', async () => {
    const bad = await request(app).put('/api/uploads/not-a-uuid').send({}).expect(400);
    assert.equal(bad.body.error, 'INVALID_UPLOAD_ID');
    const id = crypto.randomUUID();
    const r1 = await request(app).put(`/api/uploads/${id}`).send({ fileName: 'a', fileSize: 1, chunkSize: 10, sha256: sha256('') });
    assert.equal(r1.status, 400);
    assert.equal(r1.body.error, 'INVALID_REQUEST');
    const r2 = await request(app)
      .put(`/api/uploads/${id}`)
      .send({ fileName: 'a', fileSize: 2 * 1024 ** 3, chunkSize: CS, sha256: sha256('') });
    assert.equal(r2.status, 413);
    assert.equal(r2.body.error, 'FILE_TOO_LARGE');
  });
});

describe('chunks', () => {
  test('happy path: chunks stored, status lists them, complete verifies', async () => {
    const file = crypto.randomBytes(3 * CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    const parts = chunksOf(file, CS);
    const r0 = await putChunk(request, app, id, 0, parts[0]).expect(200);
    assert.deepEqual(r0.body, { uploadId: id, index: 0, status: 'stored', sha256: sha256(parts[0]) });
    await putChunk(request, app, id, 2, parts[2]).expect(200);
    const st = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.deepEqual(st.body.receivedChunks, [0, 2]);
    assert.equal(st.body.state, 'UPLOADING');
    assert.equal(st.body.sha256, undefined);
    await putChunk(request, app, id, 1, parts[1]).expect(200);
    const done = await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    assert.deepEqual(done.body, { uploadId: id, state: 'COMPLETED', sha256: sha256(file), size: file.length });
    const paths = storagePaths(dir);
    assert.equal(sha256(await fsp.readFile(paths.completedFile(id))), sha256(file));
    const final = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.equal(final.body.state, 'COMPLETED');
    assert.equal(final.body.sha256, sha256(file));
    assert.deepEqual(final.body.receivedChunks, [0, 1, 2]);
    const late = await putChunk(request, app, id, 0, parts[0]).expect(409);
    assert.equal(late.body.error, 'SESSION_COMPLETED');
  });

  test('duplicate chunk: already_received and not rewritten', async () => {
    const file = crypto.randomBytes(2 * CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    const c0 = file.subarray(0, CS);
    await putChunk(request, app, id, 0, c0).expect(200);
    const chunkPath = storagePaths(dir).chunkFile(id, 0);
    const before = await fsp.stat(chunkPath);
    await new Promise((r) => setTimeout(r, 20));
    const dup = await putChunk(request, app, id, 0, c0).expect(200);
    assert.equal(dup.body.status, 'already_received');
    const after = await fsp.stat(chunkPath);
    assert.equal(after.ino, before.ino);
    assert.equal(after.mtimeMs, before.mtimeMs);
    const stats = await request(app).get('/admin/stats').expect(200);
    assert.equal(stats.body.dedupedChunks, 1);
    // Same index, different (valid) content: conflict.
    const other = crypto.randomBytes(CS);
    const conflict = await putChunk(request, app, id, 0, other).expect(409);
    assert.equal(conflict.body.error, 'CHUNK_CONFLICT');
    const leftovers = (await fsp.readdir(storagePaths(dir).chunksDir(id))).filter((n) => n.endsWith('.tmp'));
    assert.deepEqual(leftovers, []);
  });

  test('wrong hash rejected and not stored', async () => {
    const file = crypto.randomBytes(2 * CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    const res = await putChunk(request, app, id, 0, file.subarray(0, CS), sha256('something else')).expect(422);
    assert.equal(res.body.error, 'CHUNK_HASH_MISMATCH');
    assert.deepEqual(await fsp.readdir(storagePaths(dir).chunksDir(id)), []);
    const st = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.deepEqual(st.body.receivedChunks, []);
  });

  test('wrong length rejected (short 400, long 413), bad index and header rejected', async () => {
    const file = crypto.randomBytes(2 * CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    const short = await putChunk(request, app, id, 0, crypto.randomBytes(CS - 1)).expect(400);
    assert.equal(short.body.error, 'CHUNK_LENGTH_MISMATCH');
    const long = await putChunk(request, app, id, 0, crypto.randomBytes(CS + 1)).expect(413);
    assert.equal(long.body.error, 'CHUNK_TOO_LARGE');
    const idx = await putChunk(request, app, id, 2, file.subarray(0, CS)).expect(400);
    assert.equal(idx.body.error, 'INVALID_CHUNK_INDEX');
    const hdr = await putChunk(request, app, id, 0, file.subarray(0, CS), 'nope').expect(400);
    assert.equal(hdr.body.error, 'INVALID_CHUNK_HASH');
    const unknown = await putChunk(request, app, crypto.randomUUID(), 0, file.subarray(0, CS)).expect(404);
    assert.equal(unknown.body.error, 'SESSION_NOT_FOUND');
    assert.deepEqual(await fsp.readdir(storagePaths(dir).chunksDir(id)), []);
  });

  test('odd-size last chunk', async () => {
    const file = crypto.randomBytes(2 * CS + 452);
    const id = crypto.randomUUID();
    const created = await createSession(request, app, id, file).expect(201);
    assert.equal(created.body.totalChunks, 3);
    // A full-size body for the short last chunk is too large.
    await putChunk(request, app, id, 2, crypto.randomBytes(CS)).expect(413);
    for (const [i, c] of chunksOf(file, CS).entries()) await putChunk(request, app, id, i, c).expect(200);
    const done = await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    assert.equal(done.body.size, file.length);
    assert.equal(done.body.sha256, sha256(file));
  });
});

describe('complete', () => {
  test('missing chunks → 409 with list', async () => {
    const file = crypto.randomBytes(4 * CS);
    const id = crypto.randomUUID();
    await createSession(request, app, id, file).expect(201);
    await putChunk(request, app, id, 1, file.subarray(CS, 2 * CS)).expect(200);
    const res = await request(app).post(`/api/uploads/${id}/complete`).expect(409);
    assert.equal(res.body.error, 'MISSING_CHUNKS');
    assert.deepEqual(res.body.missing, [0, 2, 3]);
  });

  test('hash mismatch → 422, session stays UPLOADING, nothing assembled', async () => {
    const file = crypto.randomBytes(2 * CS);
    const id = crypto.randomUUID();
    await request(app)
      .put(`/api/uploads/${id}`)
      .send({ fileName: 'x', fileSize: file.length, chunkSize: CS, sha256: sha256('not the file') })
      .expect(201);
    for (const [i, c] of chunksOf(file, CS).entries()) await putChunk(request, app, id, i, c).expect(200);
    const res = await request(app).post(`/api/uploads/${id}/complete`).expect(422);
    assert.equal(res.body.error, 'FILE_HASH_MISMATCH');
    assert.equal(res.body.actual, sha256(file));
    const st = await request(app).get(`/api/uploads/${id}`).expect(200);
    assert.equal(st.body.state, 'UPLOADING');
    assert.deepEqual(await fsp.readdir(storagePaths(dir).completed), []);
  });

  test('finalize is idempotent', async () => {
    const file = crypto.randomBytes(3 * CS + 7);
    const id = await uploadAll(file);
    const first = await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    const second = await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    assert.deepEqual(second.body, first.body);
    // Re-creating with identical params after completion is also fine.
    const again = await createSession(request, app, id, file).expect(200);
    assert.equal(again.body.state, 'COMPLETED');
  });

  test('zero-byte upload', async () => {
    const file = Buffer.alloc(0);
    const id = crypto.randomUUID();
    const created = await createSession(request, app, id, file).expect(201);
    assert.equal(created.body.totalChunks, 0);
    await putChunk(request, app, id, 0, Buffer.alloc(0)).expect(400);
    const done = await request(app).post(`/api/uploads/${id}/complete`).expect(200);
    assert.deepEqual(done.body, { uploadId: id, state: 'COMPLETED', sha256: sha256(''), size: 0 });
    assert.equal((await fsp.stat(storagePaths(dir).completedFile(id))).size, 0);
  });
});

describe('lifecycle', () => {
  test('server restart keeps sessions and chunks', async () => {
    const file = crypto.randomBytes(3 * CS);
    const id = crypto.randomUUID();
    const parts = chunksOf(file, CS);
    await createSession(request, app, id, file).expect(201);
    await putChunk(request, app, id, 0, parts[0]).expect(200);
    await putChunk(request, app, id, 1, parts[1]).expect(200);

    const restarted = makeApp(dir); // same storage dir, new process state
    const st = await request(restarted).get(`/api/uploads/${id}`).expect(200);
    assert.deepEqual(st.body.receivedChunks, [0, 1]);
    const dup = await putChunk(request, restarted, id, 1, parts[1]).expect(200);
    assert.equal(dup.body.status, 'already_received');
    await putChunk(request, restarted, id, 2, parts[2]).expect(200);
    const done = await request(restarted).post(`/api/uploads/${id}/complete`).expect(200);
    assert.equal(done.body.sha256, sha256(file));
  });

  test('delete is idempotent and removes data', async () => {
    const file = crypto.randomBytes(2 * CS);
    const id = await uploadAll(file);
    await request(app).delete(`/api/uploads/${id}`).expect(204);
    await request(app).delete(`/api/uploads/${id}`).expect(204);
    const st = await request(app).get(`/api/uploads/${id}`).expect(404);
    assert.equal(st.body.error, 'SESSION_NOT_FOUND');
    assert.deepEqual(await fsp.readdir(storagePaths(dir).uploads), []);
  });

  test('sweeper expires idle sessions but keeps completed ones', async () => {
    const idle = crypto.randomUUID();
    await createSession(request, app, idle, crypto.randomBytes(CS)).expect(201);
    const done = await uploadAll(crypto.randomBytes(CS));
    await request(app).post(`/api/uploads/${done}/complete`).expect(200);

    const { ctx } = app.locals;
    assert.deepEqual(await ctx.sweep(Date.now()), { removed: 0 });
    assert.deepEqual(await ctx.sweep(Date.now() + 25 * 60 * 60 * 1000), { removed: 1 });
    const gone = await request(app).get(`/api/uploads/${idle}`).expect(404);
    assert.equal(gone.body.error, 'SESSION_NOT_FOUND');
    await request(app).get(`/api/uploads/${done}`).expect(200);
  });
});
