import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import request from 'supertest';
import { storagePaths } from '../src/storage.js';
import { writeSeededFile } from '../src/seedData.js';
import { binaryParser, freshDir, makeApp, removeDir, sha256 } from './helpers.js';

let dir;
let app;
let paths;
let file; // Buffer of 'odd' (10 000 bytes)

beforeEach(async () => {
  dir = await freshDir();
  app = makeApp(dir);
  paths = storagePaths(dir);
  await writeSeededFile(paths, 'odd', 10_000);
  await writeSeededFile(paths, 'empty', 0);
  file = await fsp.readFile(paths.fileBin('odd'));
});
afterEach(() => removeDir(dir));

const content = (id = 'odd') => request(app).get(`/api/files/${id}/content`).buffer(true).parse(binaryParser);

describe('seed', () => {
  test('seeded content is deterministic and skipped when present', async () => {
    const other = await freshDir();
    try {
      const a = await writeSeededFile(storagePaths(other), 'odd', 10_000);
      assert.equal(a.meta.sha256, sha256(file));
      const again = await writeSeededFile(storagePaths(other), 'odd', 10_000);
      assert.equal(again.created, false);
    } finally {
      await removeDir(other);
    }
  });
});

describe('listing and manifest', () => {
  test('GET /api/files lists files', async () => {
    const res = await request(app).get('/api/files').expect(200);
    assert.deepEqual(res.body, [
      { fileId: 'empty', name: 'empty.bin', size: 0, sha256: sha256('') },
      { fileId: 'odd', name: 'odd.bin', size: 10_000, sha256: sha256(file) },
    ]);
  });

  test('manifest chunk hashes recompute correctly and are cached per chunk size', async () => {
    const res = await request(app).get('/api/files/odd/manifest?chunkSize=1024').expect(200);
    const m = res.body;
    assert.equal(m.size, 10_000);
    assert.equal(m.sha256, sha256(file));
    assert.equal(m.etag, `"${sha256(file)}"`);
    assert.equal(m.chunkSize, 1024);
    assert.equal(m.chunks.length, 10);
    for (const c of m.chunks) {
      assert.equal(c.offset, c.index * 1024);
      assert.equal(c.length, c.index === 9 ? 10_000 - 9 * 1024 : 1024);
      assert.equal(c.sha256, sha256(file.subarray(c.offset, c.offset + c.length)));
    }
    const cached = JSON.parse(await fsp.readFile(paths.manifest('odd', 1024), 'utf8'));
    assert.deepEqual(cached, m);
    const other = await request(app).get('/api/files/odd/manifest?chunkSize=4096').expect(200);
    assert.equal(other.body.chunks.length, 3);
    const empty = await request(app).get('/api/files/empty/manifest').expect(200);
    assert.deepEqual(empty.body.chunks, []);
    assert.equal(empty.body.chunkSize, 2 * 1024 * 1024);
    await request(app).get('/api/files/odd/manifest?chunkSize=12').expect(400);
    await request(app).get('/api/files/missing/manifest').expect(404);
  });
});

describe('content and ranges', () => {
  test('full body 200 with ETag', async () => {
    const res = await content().expect(200);
    assert.equal(res.headers.etag, `"${sha256(file)}"`);
    assert.equal(res.headers['accept-ranges'], 'bytes');
    assert.ok(res.body.equals(file));
  });

  test('Range → 206 bytes and Content-Range', async () => {
    const res = await content().set('Range', 'bytes=1024-2047').expect(206);
    assert.equal(res.headers['content-range'], 'bytes 1024-2047/10000');
    assert.equal(res.headers['content-length'], '1024');
    assert.ok(res.body.equals(file.subarray(1024, 2048)));
    const open = await content().set('Range', 'bytes=9000-').expect(206);
    assert.equal(open.headers['content-range'], 'bytes 9000-9999/10000');
    assert.ok(open.body.equals(file.subarray(9000)));
    const suffix = await content().set('Range', 'bytes=-100').expect(206);
    assert.ok(suffix.body.equals(file.subarray(9900)));
    const clamped = await content().set('Range', 'bytes=9990-20000').expect(206);
    assert.equal(clamped.headers['content-range'], 'bytes 9990-9999/10000');
  });

  test('If-Range match → 206, mismatch → 200 full body', async () => {
    const etag = `"${sha256(file)}"`;
    const match = await content().set('Range', 'bytes=0-9').set('If-Range', etag).expect(206);
    assert.ok(match.body.equals(file.subarray(0, 10)));
    const mismatch = await content().set('Range', 'bytes=0-9').set('If-Range', '"stale"').expect(200);
    assert.ok(mismatch.body.equals(file));
    assert.equal(mismatch.headers['content-range'], undefined);
  });

  test('If-Range after the remote file changed → 200 with new ETag (remote-changed path)', async () => {
    const before = (await request(app).get('/api/files/odd/manifest?chunkSize=1024').expect(200)).body;
    const mutated = await request(app).post('/admin/files/odd/mutate').expect(200);
    assert.notEqual(mutated.body.etag, before.etag);
    const res = await content().set('Range', 'bytes=0-9').set('If-Range', before.etag).expect(200);
    assert.equal(res.headers.etag, mutated.body.etag);
    assert.equal(sha256(res.body), mutated.body.sha256);
    const after = (await request(app).get('/api/files/odd/manifest?chunkSize=1024').expect(200)).body;
    assert.equal(after.etag, mutated.body.etag);
    assert.notDeepEqual(after.chunks, before.chunks);
  });

  test('416 for unsatisfiable, multi-range, zero-byte', async () => {
    const r1 = await content().set('Range', 'bytes=10000-').expect(416);
    assert.equal(r1.headers['content-range'], 'bytes */10000');
    await content().set('Range', 'bytes=0-1,5-6').expect(416);
    await content().set('Range', 'bytes=5-2').expect(416);
    const r2 = await content('empty').set('Range', 'bytes=0-0').expect(416);
    assert.equal(r2.headers['content-range'], 'bytes */0');
    const full = await content('empty').expect(200);
    assert.equal(full.body.length, 0);
    await content('missing').expect(404);
  });
});
