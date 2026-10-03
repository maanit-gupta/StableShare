import { createApp } from './app.js';
import { loadConfig, SWEEP_INTERVAL_MS } from './config.js';

const config = loadConfig();
const app = createApp({
  storageDir: config.storageDir,
  defaultChunkSize: config.defaultChunkSize,
  sessionTtlMs: config.sessionTtlMs,
  logRequests: config.logRequests,
});
const { ctx } = app.locals;

const sweep = () =>
  ctx
    .sweep()
    .then(({ removed }) => removed && console.log(`[sweep] removed ${removed} expired upload session(s)`))
    .catch((err) => console.error('[sweep] failed', err));

const server = app.listen(config.port, config.host, (err) => {
  if (err) {
    console.error(`Failed to listen on ${config.host}:${config.port}:`, err.message);
    process.exit(1);
  }
  console.log(`StableShare mock server listening on http://${config.host}:${config.port} (storage: ${config.storageDir})`);
  sweep();
});
setInterval(sweep, SWEEP_INTERVAL_MS).unref();

function shutdown(signal) {
  console.log(`${signal} received, shutting down`);
  server.close(() => process.exit(0));
  server.closeAllConnections(); // includes requests held open by the timeout fault
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
