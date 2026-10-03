// Deterministic pseudo-random file content: AES-256-CTR keystream over zeros, keyed by the fileId.
// Fast (hardware AES) and identical on every machine, so hashes are reproducible.
import fsp from 'node:fs/promises';
import crypto from 'node:crypto';
import { atomicWriteJson, fsyncDir, readJson, tmpName } from './storage.js';
import { MiB, GiB } from './config.js';

export const SEED_FILES = Object.freeze([
  { fileId: 'sample-50MB', size: 50 * MiB },
  { fileId: 'sample-200MB', size: 200 * MiB },
  { fileId: 'sample-500MB', size: 500 * MiB },
  { fileId: 'sample-1GB', size: GiB },
  { fileId: 'sample-0B', size: 0 },
  { fileId: 'sample-odd', size: 3 * MiB + 123 },
]);

const BLOCK = 4 * MiB;

export function etagFor(sha256) {
  return `"${sha256}"`;
}

// Writes files/<fileId>.bin and .meta.json unless both already exist and agree. Returns {created, meta}.
export async function writeSeededFile(paths, fileId, size, name = `${fileId}.bin`) {
  const binPath = paths.fileBin(fileId);
  const existing = await readJson(paths.fileMeta(fileId));
  if (existing) {
    const st = await fsp.stat(binPath).catch(() => null);
    if (st && st.size === existing.size) return { created: false, meta: existing };
  }
  await fsp.mkdir(paths.files, { recursive: true });
  const key = crypto.createHash('sha256').update(`stableshare-seed:${fileId}`).digest();
  const cipher = crypto.createCipheriv('aes-256-ctr', key, Buffer.alloc(16));
  const zeros = Buffer.alloc(BLOCK);
  const hash = crypto.createHash('sha256');
  const tmp = tmpName(binPath);
  const fh = await fsp.open(tmp, 'w');
  try {
    for (let written = 0; written < size; ) {
      const n = Math.min(BLOCK, size - written);
      const block = cipher.update(n === BLOCK ? zeros : zeros.subarray(0, n));
      hash.update(block);
      await fh.write(block, 0, block.length);
      written += n;
    }
    await fh.sync();
  } finally {
    await fh.close();
  }
  await fsp.rename(tmp, binPath);
  await fsyncDir(paths.files);
  const sha256 = hash.digest('hex');
  const meta = { fileId, name, size, sha256, etag: etagFor(sha256) };
  await atomicWriteJson(paths.fileMeta(fileId), meta);
  return { created: true, meta };
}
