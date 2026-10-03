import fs from 'node:fs';
import fsp from 'node:fs/promises';
import crypto from 'node:crypto';
import express from 'express';
import { ApiError } from '../errors.js';
import { atomicWriteJson, readJson, sha256File } from '../storage.js';
import { etagFor } from '../seedData.js';
import { validateChunkSize } from './uploads.js';

const FILE_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

function parseFileId(req) {
  const { fileId } = req.params;
  if (!FILE_ID_RE.test(fileId) || fileId.includes('..')) throw new ApiError(404, 'FILE_NOT_FOUND', 'No such file');
  return fileId;
}

async function loadFileMeta(paths, fileId) {
  const meta = await readJson(paths.fileMeta(fileId));
  if (!meta) throw new ApiError(404, 'FILE_NOT_FOUND', `File ${fileId} not found`);
  return meta;
}

// Single range only: "a-b", "a-", "-n". Returns {start, end} (inclusive) or null if unsatisfiable/unsupported.
export function parseRange(header, size) {
  if (size === 0) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!m || (m[1] === '' && m[2] === '')) return null;
  if (m[1] === '') {
    const n = Number(m[2]);
    if (n === 0) return null;
    return { start: Math.max(0, size - n), end: size - 1 };
  }
  const start = Number(m[1]);
  const end = m[2] === '' ? size - 1 : Math.min(Number(m[2]), size - 1);
  if (start >= size || end < start) return null;
  return { start, end };
}

// One streaming pass computing every chunk's hash.
async function computeManifest(paths, meta, chunkSize) {
  const chunks = [];
  let hash = crypto.createHash('sha256');
  let inChunk = 0;
  let offset = 0;
  const finish = () => {
    chunks.push({ index: chunks.length, offset, length: inChunk, sha256: hash.digest('hex') });
    offset += inChunk;
    inChunk = 0;
    hash = crypto.createHash('sha256');
  };
  for await (let buf of fs.createReadStream(paths.fileBin(meta.fileId), { highWaterMark: 1024 * 1024 })) {
    while (buf.length) {
      const take = Math.min(chunkSize - inChunk, buf.length);
      hash.update(buf.subarray(0, take));
      inChunk += take;
      buf = buf.subarray(take);
      if (inChunk === chunkSize) finish();
    }
  }
  if (inChunk > 0) finish();
  return { fileId: meta.fileId, name: meta.name, size: meta.size, sha256: meta.sha256, etag: meta.etag, chunkSize, chunks };
}

export function filesRouter(ctx) {
  const { paths, locks, faults } = ctx;
  const router = express.Router();

  router.get('/', async (_req, res) => {
    const names = (await fsp.readdir(paths.files).catch(() => [])).filter((n) => n.endsWith('.meta.json')).sort();
    const metas = await Promise.all(names.map((n) => readJson(`${paths.files}/${n}`)));
    res.json(metas.filter(Boolean).map(({ fileId, name, size, sha256 }) => ({ fileId, name, size, sha256 })));
  });

  router.get('/:fileId/manifest', async (req, res) => {
    const fileId = parseFileId(req);
    const raw = req.query.chunkSize;
    let chunkSize = ctx.defaultChunkSize;
    if (raw !== undefined) {
      if (typeof raw !== 'string' || !/^\d+$/.test(raw)) {
        throw new ApiError(400, 'INVALID_REQUEST', 'chunkSize must be a positive integer');
      }
      chunkSize = Number(raw);
    }
    validateChunkSize(chunkSize);
    const manifest = await locks.run(`file:${fileId}`, async () => {
      const meta = await loadFileMeta(paths, fileId);
      const cachePath = paths.manifest(fileId, chunkSize);
      const cached = await readJson(cachePath);
      if (cached && cached.etag === meta.etag) return cached;
      const fresh = await computeManifest(paths, meta, chunkSize);
      await atomicWriteJson(cachePath, fresh);
      return fresh;
    });
    res.json(manifest);
  });

  router.get('/:fileId/content', async (req, res) => {
    const fileId = parseFileId(req);
    const meta = await loadFileMeta(paths, fileId);
    res.setHeader('ETag', meta.etag);
    res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('Content-Type', 'application/octet-stream');
    const range = req.get('range');
    const ifRange = req.get('if-range');
    let start = 0;
    let end = meta.size - 1;
    let status = 200;
    // If-Range mismatch (other etag or a date) => ignore Range and send the full, current body.
    if (range !== undefined && (ifRange === undefined || ifRange === meta.etag)) {
      const parsed = parseRange(range, meta.size);
      if (!parsed) {
        res.setHeader('Content-Range', `bytes */${meta.size}`);
        throw new ApiError(416, 'RANGE_NOT_SATISFIABLE', `Range "${range}" not satisfiable for ${meta.size} bytes`);
      }
      ({ start, end } = parsed);
      status = 206;
      res.setHeader('Content-Range', `bytes ${start}-${end}/${meta.size}`);
    }
    const length = meta.size === 0 ? 0 : end - start + 1;
    res.status(status);
    res.setHeader('Content-Length', length);
    if (req.method === 'HEAD' || length === 0) {
      res.end();
      return;
    }
    await faults.sendBody(req, res, fs.createReadStream(paths.fileBin(fileId), { start, end }), length);
  });

  return router;
}

// Changes content and ETag in place (exercises the "remote file changed" path).
export async function mutateFile(ctx, fileId) {
  const { paths, locks } = ctx;
  if (!FILE_ID_RE.test(fileId) || fileId.includes('..')) throw new ApiError(404, 'FILE_NOT_FOUND', 'No such file');
  return locks.run(`file:${fileId}`, async () => {
    const meta = await loadFileMeta(paths, fileId);
    const binPath = paths.fileBin(fileId);
    const fh = await fsp.open(binPath, 'r+');
    try {
      if (meta.size === 0) {
        await fh.write(crypto.randomBytes(16), 0, 16, 0);
      } else {
        const len = Math.min(4096, meta.size);
        await fh.write(crypto.randomBytes(len), 0, len, Math.floor((meta.size - len) / 2));
      }
      await fh.sync();
    } finally {
      await fh.close();
    }
    const size = (await fsp.stat(binPath)).size;
    const sha256 = await sha256File(binPath);
    const next = { ...meta, size, sha256, etag: etagFor(sha256) };
    await atomicWriteJson(paths.fileMeta(fileId), next);
    const names = await fsp.readdir(paths.files);
    await Promise.all(
      names.filter((n) => n.startsWith(`${fileId}.manifest.`)).map((n) => fsp.rm(`${paths.files}/${n}`, { force: true })),
    );
    return next;
  });
}
