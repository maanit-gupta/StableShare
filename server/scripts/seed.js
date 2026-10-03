#!/usr/bin/env node
// npm run seed — deterministic sample files in STORAGE_DIR/files (existing files are skipped).
import { loadConfig } from '../src/config.js';
import { storagePaths } from '../src/storage.js';
import { SEED_FILES, writeSeededFile } from '../src/seedData.js';

const { storageDir } = loadConfig();
const paths = storagePaths(storageDir);
const only = process.argv.slice(2);
for (const { fileId, size } of SEED_FILES) {
  if (only.length && !only.includes(fileId)) continue;
  const t0 = Date.now();
  const { created, meta } = await writeSeededFile(paths, fileId, size);
  const what = created ? `created in ${Date.now() - t0} ms` : 'exists, skipped';
  console.log(`${fileId.padEnd(14)} ${String(size).padStart(11)} B  sha256=${meta.sha256}  ${what}`);
}
