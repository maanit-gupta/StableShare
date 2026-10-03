import path from 'node:path';
import { fileURLToPath } from 'node:url';

const serverRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export const KiB = 1024;
export const MiB = 1024 * KiB;
export const GiB = 1024 * MiB;

export const LIMITS = Object.freeze({
  maxFileSize: GiB,
  minChunkSize: KiB,
  maxChunkSize: 64 * MiB,
});

export const SESSION_TTL_MS = 24 * 60 * 60 * 1000;
export const SWEEP_INTERVAL_MS = 60 * 60 * 1000;
export const STALE_TMP_MS = 60 * 60 * 1000;

export function loadConfig(env = process.env) {
  return {
    port: Number(env.PORT ?? 8080),
    host: env.HOST ?? '0.0.0.0',
    storageDir: path.resolve(serverRoot, env.STORAGE_DIR ?? './storage'),
    defaultChunkSize: Number(env.DEFAULT_CHUNK_SIZE ?? 2 * MiB),
    sessionTtlMs: Number(env.SESSION_TTL_MS ?? SESSION_TTL_MS),
    logRequests: env.LOG_REQUESTS !== '0',
  };
}
