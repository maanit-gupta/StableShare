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
    maxFileSize: Number(env.MAX_UPLOAD_BYTES ?? LIMITS.maxFileSize),
    // Hosted-only switches; unset means off, so a local run behaves as before.
    completedTtlMs: Number(env.COMPLETED_UPLOAD_TTL_MS ?? 0),
    faultAutoResetMs: Number(env.FAULT_AUTO_RESET_MS ?? 0),
    seedOnStart: env.SEED_ON_START === '1',
    seedMaxBytes: Number(env.SEED_MAX_BYTES ?? 0),
    disableFileMutate: env.DISABLE_FILE_MUTATE === '1',
    logRequests: env.LOG_REQUESTS !== '0',
  };
}
