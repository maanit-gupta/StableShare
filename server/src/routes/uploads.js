import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import express from 'express';
import { ApiError } from '../errors.js';
import { LIMITS, STALE_TMP_MS } from '../config.js';
import { atomicWriteJson, fsyncDir, readJson, tmpName } from '../storage.js';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const SHA256_RE = /^[0-9a-f]{64}$/i;

export function expectedChunkLength(meta, index) {
  return Math.min(meta.chunkSize, meta.fileSize - index * meta.chunkSize);
}

function receivedChunks(meta) {
  // An instant session never received chunks but holds the whole file.
  if (meta.state === 'COMPLETED') return Array.from({ length: meta.totalChunks }, (_, i) => i);
  return Object.keys(meta.chunks)
    .map(Number)
    .sort((a, b) => a - b);
}

function statusBody(meta) {
  const body = {
    uploadId: meta.uploadId,
    fileName: meta.fileName,
    fileSize: meta.fileSize,
    chunkSize: meta.chunkSize,
    totalChunks: meta.totalChunks,
    receivedChunks: receivedChunks(meta),
    state: meta.state,
  };
  if (meta.state === 'COMPLETED') body.sha256 = meta.result.sha256;
  return body;
}

function createBody(meta) {
  const body = {
    uploadId: meta.uploadId,
    totalChunks: meta.totalChunks,
    chunkSize: meta.chunkSize,
    receivedChunks: receivedChunks(meta),
    state: meta.state,
    instant: meta.instant === true,
  };
  if (meta.state === 'COMPLETED') body.sha256 = meta.result.sha256;
  return body;
}

function completeBody(meta) {
  return { uploadId: meta.uploadId, state: meta.state, sha256: meta.result.sha256, size: meta.result.size };
}

export function validateChunkSize(value) {
  if (!Number.isInteger(value) || value < LIMITS.minChunkSize || value > LIMITS.maxChunkSize) {
    throw new ApiError(
      400,
      'INVALID_REQUEST',
      `chunkSize must be an integer between ${LIMITS.minChunkSize} and ${LIMITS.maxChunkSize}`,
    );
  }
  return value;
}

function validateCreate(body) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    throw new ApiError(400, 'INVALID_REQUEST', 'Body must be a JSON object {fileName, fileSize, chunkSize, sha256}');
  }
  const { fileName, fileSize, chunkSize, sha256 } = body;
  if (typeof fileName !== 'string' || fileName.length === 0 || fileName.length > 255) {
    throw new ApiError(400, 'INVALID_REQUEST', 'fileName must be a non-empty string of at most 255 characters');
  }
  if (!Number.isSafeInteger(fileSize) || fileSize < 0) {
    throw new ApiError(400, 'INVALID_REQUEST', 'fileSize must be a non-negative integer');
  }
  if (fileSize > LIMITS.maxFileSize) {
    throw new ApiError(413, 'FILE_TOO_LARGE', `fileSize exceeds the ${LIMITS.maxFileSize}-byte limit`);
  }
  validateChunkSize(chunkSize);
  if (typeof sha256 !== 'string' || !SHA256_RE.test(sha256)) {
    throw new ApiError(400, 'INVALID_REQUEST', 'sha256 must be 64 hex characters');
  }
  return { fileName, fileSize, chunkSize, sha256: sha256.toLowerCase() };
}

function sameParams(meta, p) {
  return (
    meta.fileName === p.fileName &&
    meta.fileSize === p.fileSize &&
    meta.chunkSize === p.chunkSize &&
    meta.sha256 === p.sha256
  );
}

// Hash index of completed uploads: index/<sha256>.json = {sha256, size, path, createdAt}, path relative
// to the storage root. Callers hold the `sha:<sha256>` lock. Hash-only matching trusts the client's
// declared sha256 (acceptable for a mock server; see DESIGN §3.1 "Instant upload").
const indexLockKey = (sha256) => `sha:${sha256}`;

async function writeIndexEntry(paths, sha256, size, file) {
  const entry = { sha256, size, path: path.relative(paths.root, file), createdAt: Date.now() };
  await atomicWriteJson(paths.indexEntry(sha256), entry);
}

// Places the indexed file at `target` (hard link, copy as fallback) and returns true, or removes a
// stale entry (file missing or wrong size) and returns false.
async function linkFromIndex(paths, sha256, size, target) {
  const entry = await readJson(paths.indexEntry(sha256)).catch(() => null);
  if (!entry) return false;
  const source = path.resolve(paths.root, entry.path);
  const st = await fsp.stat(source).catch(() => null);
  if (entry.sha256 !== sha256 || entry.size !== size || !st?.isFile() || st.size !== size) {
    await fsp.rm(paths.indexEntry(sha256), { force: true });
    await fsyncDir(paths.index);
    return false;
  }
  const tmp = tmpName(target);
  try {
    try {
      await fsp.link(source, tmp);
    } catch (err) {
      if (err.code === 'ENOENT') throw err;
      await fsp.copyFile(source, tmp);
      const fh = await fsp.open(tmp, 'r+');
      try {
        await fh.sync();
      } finally {
        await fh.close();
      }
    }
    if ((await fsp.stat(tmp)).size !== size) throw Object.assign(new Error('size changed'), { code: 'ESIZE' });
    await fsp.rename(tmp, target);
  } catch (err) {
    await fsp.rm(tmp, { force: true });
    if (err.code !== 'ENOENT' && err.code !== 'ESIZE') throw err;
    await fsp.rm(paths.indexEntry(sha256), { force: true });
    return false;
  }
  await fsyncDir(path.dirname(target));
  return true;
}

export function uploadsRouter(ctx) {
  const { paths, locks, faults } = ctx;
  const router = express.Router();
  const json = express.json({ limit: '64kb', type: () => true });

  function parseId(req) {
    const id = req.params.uploadId;
    if (!UUID_RE.test(id)) throw new ApiError(400, 'INVALID_UPLOAD_ID', 'uploadId must be a UUID');
    return id.toLowerCase();
  }

  async function loadMeta(id) {
    const meta = await readJson(paths.uploadMeta(id));
    if (!meta) throw new ApiError(404, 'SESSION_NOT_FOUND', `Upload session ${id} not found (unknown, cancelled or expired)`);
    return meta;
  }

  // Create session (idempotent).
  router.put('/:uploadId', json, async (req, res) => {
    const id = parseId(req);
    const params = validateCreate(req.body);
    const { status, meta } = await locks.run(id, async () => {
      const existing = await readJson(paths.uploadMeta(id));
      if (existing) {
        if (!sameParams(existing, params)) {
          throw new ApiError(409, 'SESSION_CONFLICT', `Upload ${id} already exists with different parameters`);
        }
        return { status: 200, meta: existing };
      }
      const now = Date.now();
      const created = {
        uploadId: id,
        ...params,
        totalChunks: Math.ceil(params.fileSize / params.chunkSize),
        state: 'UPLOADING',
        chunks: {},
        createdAt: now,
        updatedAt: now,
      };
      // Instant upload: the server already holds these bytes. Zero-byte files have nothing to save.
      // Bytes land (link + dir fsync) before the meta that marks the session COMPLETED.
      const instant =
        params.fileSize > 0 &&
        (await locks.run(indexLockKey(params.sha256), () =>
          linkFromIndex(paths, params.sha256, params.fileSize, paths.completedFile(id)),
        ));
      if (instant) {
        Object.assign(created, {
          state: 'COMPLETED',
          instant: true,
          completedAt: now,
          result: { sha256: params.sha256, size: params.fileSize },
        });
        await fsp.mkdir(paths.uploadDir(id), { recursive: true });
      } else {
        await fsp.mkdir(paths.chunksDir(id), { recursive: true });
      }
      await atomicWriteJson(paths.uploadMeta(id), created);
      await fsyncDir(paths.uploads);
      if (instant) faults.stats.instantUploads++;
      return { status: instant ? 200 : 201, meta: created };
    });
    res.status(status).json(createBody(meta));
  });

  // Upload one chunk.
  router.put('/:uploadId/chunks/:index', async (req, res) => {
    const id = parseId(req);
    const meta = await loadMeta(id);
    if (meta.state === 'COMPLETED') throw new ApiError(409, 'SESSION_COMPLETED', `Upload ${id} is already completed`);
    const rawIndex = req.params.index;
    const index = Number(rawIndex);
    if (!/^\d+$/.test(rawIndex) || index >= meta.totalChunks) {
      throw new ApiError(400, 'INVALID_CHUNK_INDEX', `index must be an integer in [0, ${meta.totalChunks})`);
    }
    const declared = req.get('x-chunk-sha256');
    if (!declared || !SHA256_RE.test(declared)) {
      throw new ApiError(400, 'INVALID_CHUNK_HASH', 'X-Chunk-SHA256 header must be 64 hex characters');
    }
    const declaredHash = declared.toLowerCase();
    const contentLength = req.get('content-length');
    if (contentLength === undefined) throw new ApiError(411, 'LENGTH_REQUIRED', 'Content-Length is required');
    const expected = expectedChunkLength(meta, index);
    const length = Number(contentLength);
    if (length > expected) {
      throw new ApiError(413, 'CHUNK_TOO_LARGE', `Chunk ${index} must be ${expected} bytes, got ${length}`, { expectedLength: expected });
    }
    if (length < expected) {
      throw new ApiError(400, 'CHUNK_LENGTH_MISMATCH', `Chunk ${index} must be ${expected} bytes, got ${length}`, { expectedLength: expected });
    }

    // From here on the effect is persisted before replying: eligible for the lost-response fault.
    req.fault.afterProcessEligible = true;

    // Stream to a unique temp file while hashing. The final chunk file is never written in place.
    const target = paths.chunkFile(id, index);
    const tmp = tmpName(target);
    const hash = crypto.createHash('sha256');
    let received = 0;
    const counter = new Transform({
      transform(buf, _enc, cb) {
        hash.update(buf);
        received += buf.length;
        cb(null, buf);
      },
    });
    try {
      await pipeline(req, ...faults.requestStreams(req, length), counter, fs.createWriteStream(tmp, { flush: true }));
    } catch (err) {
      await fsp.rm(tmp, { force: true });
      if (err.code === 'INJECTED_DROP' || req.socket.destroyed) return; // nobody left to answer
      throw err;
    }
    if (received !== expected) {
      await fsp.rm(tmp, { force: true });
      throw new ApiError(400, 'INCOMPLETE_BODY', `Expected ${expected} bytes, received ${received}`);
    }
    const actual = hash.digest('hex');
    if (actual !== declaredHash) {
      await fsp.rm(tmp, { force: true });
      throw new ApiError(422, 'CHUNK_HASH_MISMATCH', `Chunk ${index} hash does not match X-Chunk-SHA256`, {
        expected: declaredHash,
        actual,
      });
    }

    const status = await locks.run(id, async () => {
      try {
        const current = await readJson(paths.uploadMeta(id));
        if (!current) throw new ApiError(404, 'SESSION_NOT_FOUND', `Upload session ${id} not found`);
        if (current.state === 'COMPLETED') throw new ApiError(409, 'SESSION_COMPLETED', `Upload ${id} is already completed`);
        const stored = current.chunks[index];
        if (stored === actual) {
          faults.stats.dedupedChunks++;
          return 'already_received';
        }
        if (stored) {
          throw new ApiError(409, 'CHUNK_CONFLICT', `Chunk ${index} was already stored with a different hash`, {
            stored,
            actual,
          });
        }
        await fsp.rename(tmp, target);
        await fsyncDir(paths.chunksDir(id));
        current.chunks[index] = actual;
        current.updatedAt = Date.now();
        await atomicWriteJson(paths.uploadMeta(id), current);
        return 'stored';
      } finally {
        await fsp.rm(tmp, { force: true }); // no-op after a successful rename
      }
    });
    res.json({ uploadId: id, index, status, sha256: actual });
  });

  // Session status.
  router.get('/:uploadId', async (req, res) => {
    const meta = await loadMeta(parseId(req));
    res.json(statusBody(meta));
  });

  // Assemble + verify (idempotent).
  router.post('/:uploadId/complete', async (req, res) => {
    const id = parseId(req);
    req.fault.afterProcessEligible = true;
    const body = await locks.run(id, async () => {
      const meta = await loadMeta(id);
      if (meta.state === 'COMPLETED') return completeBody(meta);
      const missing = [];
      for (let i = 0; i < meta.totalChunks; i++) if (!meta.chunks[i]) missing.push(i);
      if (missing.length) {
        throw new ApiError(409, 'MISSING_CHUNKS', `${missing.length} chunk(s) missing`, { missing });
      }
      const target = paths.completedFile(id);
      const tmp = tmpName(target);
      const hash = crypto.createHash('sha256');
      let size = 0;
      async function* assemble() {
        for (let i = 0; i < meta.totalChunks; i++) {
          for await (const buf of fs.createReadStream(paths.chunkFile(id, i))) {
            hash.update(buf);
            size += buf.length;
            yield buf;
          }
        }
      }
      try {
        await pipeline(assemble, fs.createWriteStream(tmp, { flush: true }));
      } catch (err) {
        await fsp.rm(tmp, { force: true });
        throw err;
      }
      const actual = hash.digest('hex');
      if (actual !== meta.sha256 || size !== meta.fileSize) {
        await fsp.rm(tmp, { force: true });
        throw new ApiError(422, 'FILE_HASH_MISMATCH', 'Assembled file does not match the declared sha256', {
          expected: meta.sha256,
          actual,
        });
      }
      await fsp.rename(tmp, target);
      await fsyncDir(paths.completed);
      const now = Date.now();
      Object.assign(meta, { state: 'COMPLETED', completedAt: now, updatedAt: now, result: { sha256: actual, size } });
      await atomicWriteJson(paths.uploadMeta(id), meta);
      await fsp.rm(paths.chunksDir(id), { recursive: true, force: true });
      if (size > 0) await locks.run(indexLockKey(actual), () => writeIndexEntry(paths, actual, size, target));
      return completeBody(meta);
    });
    res.json(body);
  });

  // Cancel cleanup (idempotent).
  router.delete('/:uploadId', async (req, res) => {
    const id = parseId(req);
    await locks.run(id, async () => {
      await fsp.rm(paths.uploadDir(id), { recursive: true, force: true });
      await fsp.rm(paths.completedFile(id), { force: true });
    });
    res.status(204).end();
  });

  return router;
}

// Removes non-completed sessions idle for longer than the TTL, and stale temp files.
export async function sweepUploads(ctx, now = Date.now()) {
  const { paths, locks, sessionTtlMs } = ctx;
  let removed = 0;
  const ids = await fsp.readdir(paths.uploads).catch(() => []);
  for (const id of ids) {
    await locks.run(id, async () => {
      const meta = await readJson(paths.uploadMeta(id)).catch(() => null);
      const dir = paths.uploadDir(id);
      if (!meta) {
        // Crashed before meta.json landed: orphan directory.
        const st = await fsp.stat(dir).catch(() => null);
        if (st && now - st.mtimeMs > STALE_TMP_MS) {
          await fsp.rm(dir, { recursive: true, force: true });
          removed++;
        }
        return;
      }
      if (meta.state !== 'COMPLETED' && now - meta.updatedAt > sessionTtlMs) {
        await fsp.rm(dir, { recursive: true, force: true });
        removed++;
        return;
      }
      await removeStaleTmp(paths.chunksDir(id), now);
    });
  }
  await removeStaleTmp(paths.completed, now);
  return { removed };
}

async function removeStaleTmp(dir, now) {
  const names = await fsp.readdir(dir).catch(() => []);
  for (const name of names.filter((n) => n.endsWith('.tmp'))) {
    const p = path.join(dir, name);
    const st = await fsp.stat(p).catch(() => null);
    if (st && now - st.mtimeMs > STALE_TMP_MS) await fsp.rm(p, { force: true });
  }
}
