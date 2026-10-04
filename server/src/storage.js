import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

export function storagePaths(root) {
  return {
    root,
    uploads: path.join(root, 'uploads'),
    completed: path.join(root, 'completed'),
    files: path.join(root, 'files'),
    index: path.join(root, 'index'),
    uploadDir: (id) => path.join(root, 'uploads', id),
    uploadMeta: (id) => path.join(root, 'uploads', id, 'meta.json'),
    chunksDir: (id) => path.join(root, 'uploads', id, 'chunks'),
    chunkFile: (id, i) => path.join(root, 'uploads', id, 'chunks', `${i}.bin`),
    completedFile: (id) => path.join(root, 'completed', `${id}.bin`),
    indexEntry: (sha256) => path.join(root, 'index', `${sha256}.json`),
    fileBin: (fileId) => path.join(root, 'files', `${fileId}.bin`),
    fileMeta: (fileId) => path.join(root, 'files', `${fileId}.meta.json`),
    manifest: (fileId, chunkSize) => path.join(root, 'files', `${fileId}.manifest.${chunkSize}.json`),
  };
}

export async function ensureStorage(paths) {
  await Promise.all([paths.uploads, paths.completed, paths.files, paths.index].map((d) => fsp.mkdir(d, { recursive: true })));
}

export function tmpName(target) {
  return `${target}.${crypto.randomBytes(6).toString('hex')}.tmp`;
}

export async function fsyncPath(p) {
  const fh = await fsp.open(p, 'r');
  try {
    await fh.sync();
  } finally {
    await fh.close();
  }
}

export async function fsyncDir(dir) {
  // Directory fsync makes the rename itself durable. Not supported everywhere (e.g. Windows): best effort.
  try {
    await fsyncPath(dir);
  } catch (err) {
    if (!['EISDIR', 'EPERM', 'EINVAL', 'EBADF'].includes(err.code)) throw err;
  }
}

// temp file -> fsync -> rename -> fsync dir
export async function atomicWriteFile(target, data) {
  const tmp = tmpName(target);
  const fh = await fsp.open(tmp, 'w');
  try {
    await fh.writeFile(data);
    await fh.sync();
  } catch (err) {
    await fh.close().catch(() => {});
    await fsp.rm(tmp, { force: true });
    throw err;
  }
  await fh.close();
  await fsp.rename(tmp, target);
  await fsyncDir(path.dirname(target));
}

export function atomicWriteJson(target, obj) {
  return atomicWriteFile(target, JSON.stringify(obj, null, 2));
}

export async function readJson(p) {
  try {
    return JSON.parse(await fsp.readFile(p, 'utf8'));
  } catch (err) {
    if (err.code === 'ENOENT') return null;
    throw err;
  }
}

export async function exists(p) {
  try {
    await fsp.access(p);
    return true;
  } catch {
    return false;
  }
}

export async function sha256File(p, start, end) {
  const hash = crypto.createHash('sha256');
  const opts = start === undefined ? {} : { start, end };
  for await (const buf of fs.createReadStream(p, opts)) hash.update(buf);
  return hash.digest('hex');
}
