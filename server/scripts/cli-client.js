#!/usr/bin/env node
// StableShare CLI client: implements the full client side of the protocol (docs/DESIGN.md §3, §7, §8)
// so it can be proven end to end before the Android app exists.
//
//   node scripts/cli-client.js upload <path> [--chunk-size N]
//   node scripts/cli-client.js download <fileId> <outPath> [--chunk-size N]
//   node scripts/cli-client.js status <uploadId>
//   common: [--server URL] [--timeout-ms N]
//
// Resume after being killed: state lives in a JSON sidecar next to the target
// (<path>.stableshare-upload.json / <outPath>.stableshare-download.json).
// stdout: "resume: …", "progress i/N", "RESULT {json}". stderr: retries and errors.
// Exit codes: 0 success, 1 failed (code printed), 2 usage.
import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';
import { atomicWriteJson, fsyncDir, readJson, sha256File } from '../src/storage.js';

const MiB = 1024 * 1024;
const MAX_ATTEMPTS = 5; // per chunk / per operation
const BACKOFF_BASE_MS = 1000;
const BACKOFF_CAP_MS = 30_000;
const MAX_MISSING_CHUNK_RESYNCS = 2;

// ---------- errors and classification (docs/DESIGN.md §7) ----------

class NetError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}
class HttpError extends Error {
  constructor(status, body) {
    super(`HTTP ${status} ${body?.error ?? ''}: ${body?.message ?? ''}`.trim());
    this.status = status;
    this.body = body;
  }
}
class FatalError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}
class ChunkHashError extends Error {}

const WAITING_CODES = new Set(['ECONNREFUSED', 'ENOTFOUND', 'EHOSTUNREACH', 'ENETUNREACH', 'EAI_AGAIN', 'ENETDOWN']);

function classify(err) {
  if (err instanceof FatalError) return 'fatal';
  if (err instanceof NetError) return WAITING_CODES.has(err.code) ? 'waiting' : 'retryable';
  if (err instanceof ChunkHashError) return 'retryable';
  if (err instanceof HttpError) {
    if (err.status === 429 || (err.status >= 500 && err.status !== 507)) return 'retryable';
    if (err.status === 422 && err.body?.error === 'CHUNK_HASH_MISMATCH') return 'retryable'; // corrupted in transit
    return 'fatal';
  }
  return 'fatal';
}

function toFatal(err) {
  if (err instanceof FatalError) return err;
  if (err instanceof HttpError) return new FatalError(err.body?.error ?? `HTTP_${err.status}`, err.message);
  return new FatalError(err.code ?? 'UNEXPECTED', err.message);
}

// Full jitter: uniform(0, min(cap, base * 2^(attempt-1))).
function backoffMs(attempt) {
  return Math.random() * Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** (attempt - 1));
}

// Runs fn until it succeeds. RETRYABLE consumes one of MAX_ATTEMPTS; WAITING (server unreachable)
// consumes none but is bounded by maxWaitMs in total; FATAL ends immediately.
async function withRetry(label, fn, opts) {
  let attempts = 0;
  let waitedMs = 0;
  let waits = 0;
  for (;;) {
    try {
      return await fn();
    } catch (err) {
      const kind = classify(err);
      if (kind === 'fatal') throw toFatal(err);
      if (kind === 'waiting') {
        if (waitedMs >= opts.maxWaitMs) throw new FatalError('NETWORK_UNAVAILABLE', `${label}: server unreachable for ${waitedMs} ms`);
        const delay = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** Math.min(waits++, 5));
        console.error(`[wait] ${label}: ${err.message}; waiting ${delay} ms (no attempt consumed)`);
        await sleep(delay);
        waitedMs += delay;
        continue;
      }
      attempts++;
      if (attempts >= MAX_ATTEMPTS) {
        throw new FatalError('RETRIES_EXHAUSTED', `${label}: ${attempts} attempts failed, last: ${err.message}`);
      }
      const delay = backoffMs(attempts);
      console.error(`[retry] ${label}: attempt ${attempts}/${MAX_ATTEMPTS} failed (${err.message}); backoff ${Math.round(delay)} ms`);
      await sleep(delay);
    }
  }
}

// ---------- HTTP ----------

function makeHttp(server, timeoutMs) {
  const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });

  // Resolves with {status, headers, body}. Network failures, idle timeouts and truncated bodies
  // reject with NetError. Bodies larger than maxBody are not read (body: null).
  function request({ method, path: p, headers = {}, body, maxBody = 64 * MiB }) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const settle = (fn, value) => {
        if (settled) return;
        settled = true;
        fn(value);
      };
      const reqHeaders = { ...headers };
      if (body) reqHeaders['Content-Length'] = body.length;
      const req = http.request(new URL(p, server), { method, headers: reqHeaders, agent });
      const fail = (err) => {
        settle(reject, err);
        req.destroy();
      };
      req.setTimeout(timeoutMs, () => fail(new NetError('TIMEOUT', `${method} ${p}: no activity for ${timeoutMs} ms`)));
      req.on('error', (e) => fail(new NetError(e.code ?? 'ENET', `${method} ${p}: ${e.message}`)));
      req.on('response', (res) => {
        const declared = res.headers['content-length'] === undefined ? null : Number(res.headers['content-length']);
        if (declared !== null && declared > maxBody) {
          settle(resolve, { status: res.statusCode, headers: res.headers, body: null });
          req.destroy();
          return;
        }
        const parts = [];
        let received = 0;
        res.on('data', (c) => {
          parts.push(c);
          received += c.length;
        });
        res.on('error', (e) => fail(new NetError(e.code ?? 'ECONNRESET', `${method} ${p}: ${e.message}`)));
        res.on('close', () => {
          if (!res.complete) fail(new NetError('BODY_TRUNCATED', `${method} ${p}: connection closed mid-body`));
        });
        res.on('end', () => {
          if (declared !== null && received !== declared) {
            fail(new NetError('BODY_TRUNCATED', `${method} ${p}: got ${received} of ${declared} bytes`));
            return;
          }
          settle(resolve, { status: res.statusCode, headers: res.headers, body: Buffer.concat(parts) });
        });
      });
      req.end(body);
    });
  }

  async function json(method, p, payload) {
    const body = payload === undefined ? undefined : Buffer.from(JSON.stringify(payload));
    const headers = body ? { 'Content-Type': 'application/json' } : {};
    const res = await request({ method, path: p, headers, body });
    let parsed = null;
    if (res.body?.length) {
      try {
        parsed = JSON.parse(res.body.toString('utf8'));
      } catch {
        throw new NetError('BAD_RESPONSE', `${method} ${p}: unparseable response body`);
      }
    }
    if (res.status >= 400) throw new HttpError(res.status, parsed);
    return { status: res.status, body: parsed };
  }

  return { request, json };
}

// ---------- helpers ----------

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const out = (line) => process.stdout.write(`${line}\n`);

async function readAt(fh, offset, length) {
  const buf = Buffer.alloc(length);
  let read = 0;
  while (read < length) {
    const { bytesRead } = await fh.read(buf, read, length - read, offset + read);
    if (bytesRead === 0) throw new FatalError('SOURCE_CHANGED', `Source ended early at ${offset + read}`);
    read += bytesRead;
  }
  return buf;
}

// ---------- upload ----------

async function upload(opts, filePath) {
  const { http: api } = opts;
  const abs = path.resolve(filePath);
  const sidecarPath = `${abs}.stableshare-upload.json`;
  let st;
  try {
    st = await fsp.stat(abs);
  } catch {
    throw new FatalError('SOURCE_MISSING', `Cannot read ${abs}`);
  }
  if (!st.isFile()) throw new FatalError('SOURCE_MISSING', `${abs} is not a regular file`);
  if (st.size > 1024 * MiB) throw new FatalError('FILE_TOO_LARGE', `${abs} is larger than 1 GiB`);

  const localSha = await sha256File(abs);
  let state = await readJson(sidecarPath);
  if (state) {
    if (state.size !== st.size || state.mtimeMs !== st.mtimeMs || state.sha256 !== localSha) {
      throw new FatalError(
        'SOURCE_CHANGED',
        `Source changed since upload ${state.uploadId} started; delete ${sidecarPath} to start a new upload`,
      );
    }
    console.error(`[upload] resuming upload ${state.uploadId} from sidecar`);
  } else {
    state = {
      uploadId: crypto.randomUUID(),
      path: abs,
      size: st.size,
      mtimeMs: st.mtimeMs,
      sha256: localSha,
      chunkSize: opts.chunkSize,
      createdAt: new Date().toISOString(),
    };
    await atomicWriteJson(sidecarPath, state); // id persisted before the first request
  }
  const { uploadId, chunkSize } = state;
  const base = `/api/uploads/${uploadId}`;
  const totalChunks = Math.ceil(st.size / chunkSize);

  await withRetry('create session', () =>
    api.json('PUT', base, { fileName: path.basename(abs), fileSize: st.size, chunkSize, sha256: localSha }),
  opts);

  const getStatus = () => withRetry('status', () => api.json('GET', base), opts).then((r) => r.body);

  const fh = await fsp.open(abs, 'r');
  try {
    const sourceUnchanged = async () => {
      const now = await fh.stat();
      if (now.size !== st.size || now.mtimeMs !== st.mtimeMs) {
        throw new FatalError('SOURCE_CHANGED', `${abs} was modified during the upload`);
      }
    };

    const sendChunk = (index) => {
      let ambiguous = false; // a previous attempt may have landed: check status before resending
      return withRetry(`chunk ${index}`, async () => {
        if (ambiguous) {
          const status = await getStatus();
          if (status.receivedChunks.includes(index)) return 'recovered';
        }
        ambiguous = true;
        await sourceUnchanged();
        const offset = index * chunkSize;
        const data = await readAt(fh, offset, Math.min(chunkSize, st.size - offset));
        const res = await api.request({
          method: 'PUT',
          path: `${base}/chunks/${index}`,
          headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': sha256(data) },
          body: data,
        });
        const body = res.body?.length ? JSON.parse(res.body.toString('utf8')) : null;
        if (res.status !== 200) throw new HttpError(res.status, body);
        return body.status;
      }, opts);
    };

    let result;
    for (let resync = 0; ; resync++) {
      const status = await getStatus();
      if (status.state === 'COMPLETED') {
        result = { sha256: status.sha256, size: status.fileSize };
        break;
      }
      const have = new Set(status.receivedChunks);
      out(`resume: ${have.size}/${totalChunks} chunks already on server (upload ${uploadId})`);
      let done = have.size;
      for (let i = 0; i < totalChunks; i++) {
        if (have.has(i)) continue;
        const how = await sendChunk(i);
        done++;
        out(`progress ${done}/${totalChunks} chunk=${i} ${how}`);
      }
      let ambiguous = false;
      try {
        result = await withRetry('complete', async () => {
          if (ambiguous) {
            const s = await getStatus();
            if (s.state === 'COMPLETED') return { sha256: s.sha256, size: s.fileSize };
          }
          ambiguous = true;
          return (await api.json('POST', `${base}/complete`)).body;
        }, opts);
        break;
      } catch (err) {
        if (err.code === 'MISSING_CHUNKS' && resync < MAX_MISSING_CHUNK_RESYNCS) continue;
        throw err;
      }
    }

    if (result.sha256 !== localSha || result.size !== st.size) {
      throw new FatalError('FILE_HASH_MISMATCH', `Server has ${result.sha256} (${result.size} B), local ${localSha}`);
    }
    await fsp.rm(sidecarPath, { force: true });
    out(`RESULT ${JSON.stringify({ op: 'upload', uploadId, size: st.size, sha256: result.sha256, verified: true })}`);
  } finally {
    await fh.close();
  }
}

// ---------- download ----------

async function download(opts, fileId, outPath) {
  const { http: api } = opts;
  const abs = path.resolve(outPath);
  const partPath = `${abs}.part`;
  const sidecarPath = `${abs}.stableshare-download.json`;

  const manifest = (
    await withRetry('manifest', () =>
      api.json('GET', `/api/files/${encodeURIComponent(fileId)}/manifest?chunkSize=${opts.chunkSize}`),
    opts)
  ).body;
  const total = manifest.chunks.length;

  let state = await readJson(sidecarPath);
  if (state) {
    if (state.fileId !== fileId || state.chunkSize !== manifest.chunkSize) {
      throw new FatalError('SIDECAR_MISMATCH', `${sidecarPath} belongs to another download; remove it first`);
    }
    if (state.etag !== manifest.etag) {
      throw new FatalError('REMOTE_CHANGED', `${fileId} changed on the server since the download started`);
    }
  } else {
    state = { fileId, etag: manifest.etag, size: manifest.size, sha256: manifest.sha256, chunkSize: manifest.chunkSize, doneChunks: [] };
  }

  // Source of truth: manifest + on-disk hashes. Re-verify every chunk the sidecar claims.
  const done = new Set();
  const partStat = await fsp.stat(partPath).catch(() => null);
  if (partStat && partStat.size === manifest.size) {
    const fh = await fsp.open(partPath, 'r');
    try {
      for (const i of state.doneChunks) {
        const c = manifest.chunks[i];
        if (c && sha256(await readAt(fh, c.offset, c.length)) === c.sha256) done.add(i);
        else console.error(`[download] chunk ${i} failed on-disk verification; will re-download`);
      }
    } finally {
      await fh.close();
    }
  } else {
    const fh = await fsp.open(partPath, 'w');
    await fh.truncate(manifest.size);
    await fh.sync();
    await fh.close();
  }
  state.doneChunks = [...done].sort((a, b) => a - b);
  await atomicWriteJson(sidecarPath, state);
  out(`resume: ${done.size}/${total} chunks verified on disk (${fileId})`);

  const fh = await fsp.open(partPath, 'r+');
  try {
    for (const c of manifest.chunks) {
      if (done.has(c.index)) continue;
      const data = await withRetry(`chunk ${c.index}`, async () => {
        const res = await api.request({
          method: 'GET',
          path: `/api/files/${encodeURIComponent(fileId)}/content`,
          headers: { Range: `bytes=${c.offset}-${c.offset + c.length - 1}`, 'If-Range': manifest.etag },
          maxBody: c.length,
        });
        if (res.status === 200) {
          throw new FatalError('REMOTE_CHANGED', `${fileId} changed on the server (If-Range mismatch: got 200, expected 206)`);
        }
        if (res.status !== 206) {
          let body = null;
          try {
            body = res.body?.length ? JSON.parse(res.body.toString('utf8')) : null;
          } catch {}
          throw new HttpError(res.status, body);
        }
        const expectedRange = `bytes ${c.offset}-${c.offset + c.length - 1}/${manifest.size}`;
        if (res.headers['content-range'] !== expectedRange || res.body.length !== c.length) {
          throw new NetError('BAD_RANGE', `chunk ${c.index}: unexpected range ${res.headers['content-range']}`);
        }
        if (sha256(res.body) !== c.sha256) throw new ChunkHashError(`chunk ${c.index}: hash mismatch (corrupted in transit)`);
        return res.body;
      }, opts);
      // bytes -> fsync -> then record progress
      await fh.write(data, 0, data.length, c.offset);
      await fh.datasync();
      done.add(c.index);
      state.doneChunks = [...done].sort((a, b) => a - b);
      await atomicWriteJson(sidecarPath, state);
      out(`progress ${done.size}/${total} chunk=${c.index}`);
    }
  } finally {
    await fh.close();
  }

  const actual = await sha256File(partPath);
  if (actual !== manifest.sha256) {
    throw new FatalError('FILE_HASH_MISMATCH', `Downloaded file hash ${actual} != manifest ${manifest.sha256}`);
  }
  await fsp.rename(partPath, abs);
  await fsyncDir(path.dirname(abs));
  await fsp.rm(sidecarPath, { force: true });
  out(`RESULT ${JSON.stringify({ op: 'download', fileId, path: abs, size: manifest.size, sha256: actual, verified: true })}`);
}

// ---------- status ----------

async function status(opts, uploadId) {
  const res = await withRetry('status', () => opts.http.json('GET', `/api/uploads/${uploadId}`), opts);
  out(JSON.stringify(res.body, null, 2));
}

// ---------- main ----------

function usage(msg) {
  if (msg) console.error(msg);
  console.error(`usage:
  cli-client.js upload <path> [--chunk-size N]
  cli-client.js download <fileId> <outPath> [--chunk-size N]
  cli-client.js status <uploadId>
options: --server URL (default $STABLESHARE_SERVER or http://127.0.0.1:8080), --timeout-ms N (default 30000),
         --max-wait-ms N (default 600000, total time to wait for an unreachable server)`);
  process.exit(2);
}

function parseArgs(argv) {
  const positional = [];
  const opts = {
    server: process.env.STABLESHARE_SERVER ?? 'http://127.0.0.1:8080',
    chunkSize: 2 * MiB,
    timeoutMs: 30_000,
    maxWaitMs: 600_000,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const num = () => {
      const v = Number(argv[++i]);
      if (!Number.isInteger(v) || v <= 0) usage(`${a} needs a positive integer`);
      return v;
    };
    if (a === '--server') opts.server = argv[++i];
    else if (a === '--chunk-size') opts.chunkSize = num();
    else if (a === '--timeout-ms') opts.timeoutMs = num();
    else if (a === '--max-wait-ms') opts.maxWaitMs = num();
    else if (a.startsWith('--')) usage(`unknown option ${a}`);
    else positional.push(a);
  }
  return { positional, opts };
}

async function main() {
  const { positional, opts } = parseArgs(process.argv.slice(2));
  const [cmd, ...args] = positional;
  opts.http = makeHttp(opts.server, opts.timeoutMs);
  const t0 = Date.now();
  if (cmd === 'upload' && args.length === 1) await upload(opts, args[0]);
  else if (cmd === 'download' && args.length === 2) await download(opts, args[0], args[1]);
  else if (cmd === 'status' && args.length === 1) await status(opts, args[0]);
  else usage();
  console.error(`[done] ${cmd} in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
}

main().then(
  () => process.exit(0),
  (err) => {
    const fatal = toFatal(err);
    console.error(`FAILED ${fatal.code}: ${fatal.message}`);
    process.exit(1);
  },
);
