import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createApp } from '../src/app.js';

export const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

export async function freshDir() {
  return fsp.mkdtemp(path.join(os.tmpdir(), 'stableshare-test-'));
}

export async function removeDir(dir) {
  await fsp.rm(dir, { recursive: true, force: true });
}

export function makeApp(storageDir, opts = {}) {
  return createApp({ storageDir, ...opts });
}

export function chunksOf(buf, chunkSize) {
  const out = [];
  for (let off = 0; off < buf.length; off += chunkSize) out.push(buf.subarray(off, off + chunkSize));
  return out;
}

export function putChunk(request, app, id, index, body, hash = sha256(body)) {
  return request(app)
    .put(`/api/uploads/${id}/chunks/${index}`)
    .set('Content-Type', 'application/octet-stream')
    .set('X-Chunk-SHA256', hash)
    .send(body);
}

export function createSession(request, app, id, file, { chunkSize = 1024, fileName = 'test.bin' } = {}) {
  return request(app)
    .put(`/api/uploads/${id}`)
    .send({ fileName, fileSize: file.length, chunkSize, sha256: sha256(file) });
}

// Collects a binary response body as a Buffer.
export function binaryParser(res, cb) {
  const parts = [];
  res.on('data', (c) => parts.push(c));
  res.on('end', () => cb(null, Buffer.concat(parts)));
}
