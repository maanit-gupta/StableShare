import { createApp } from './app.js';
import { loadConfig, SWEEP_INTERVAL_MS } from './config.js';
import { ensureSeedFiles } from './seedData.js';

const config = loadConfig();
const app = createApp({
  storageDir: config.storageDir,
  defaultChunkSize: config.defaultChunkSize,
  sessionTtlMs: config.sessionTtlMs,
  maxFileSize: config.maxFileSize,
  completedTtlMs: config.completedTtlMs,
  disableFileMutate: config.disableFileMutate,
  logRequests: config.logRequests,
});
const { ctx } = app.locals;

const sweep = () =>
  ctx
    .sweep()
    .then(({ removed }) => removed && console.log(`[sweep] removed ${removed} expired upload(s)`))
    .catch((err) => console.error('[sweep] failed', err));

const server = app.listen(config.port, config.host, (err) => {
  if (err) {
    console.error(`Failed to listen on ${config.host}:${config.port}:`, err.message);
    process.exit(1);
  }
  console.log(`StableShare mock server listening on http://${config.host}:${config.port} (storage: ${config.storageDir})`);
  sweep();
  // After listen, so /health answers while large seed files are verified or written.
  if (config.seedOnStart) {
    ensureSeedFiles(ctx.paths, console.log, config.seedMaxBytes)
      .then(() => console.log('[seed] seed files ready'))
      .catch((err) => console.error('[seed] failed', err));
  }
});
setInterval(sweep, SWEEP_INTERVAL_MS).unref();

// Hosted only (FAULT_AUTO_RESET_MS > 0): faults left on are switched off once no admin call has
// been made for that long, so one visitor's chaos settings do not stick for everyone else.
if (config.faultAutoResetMs > 0) {
  setInterval(() => {
    if (!ctx.faults.isDefault() && Date.now() - ctx.lastAdminAt > config.faultAutoResetMs) {
      ctx.faults.reset();
      console.log('[faults] auto-reset after admin inactivity');
    }
  }, 60 * 1000).unref();
}

function shutdown(signal) {
  console.log(`${signal} received, shutting down`);
  server.close(() => process.exit(0));
  server.closeAllConnections(); // includes requests held open by the timeout fault
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
