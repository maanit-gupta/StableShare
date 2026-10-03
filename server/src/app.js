import fs from 'node:fs';
import express from 'express';
import { MiB, SESSION_TTL_MS } from './config.js';
import { errorHandler } from './errors.js';
import { FaultInjector } from './faults.js';
import { KeyedLock } from './locks.js';
import { requestLogger } from './logger.js';
import { storagePaths } from './storage.js';
import { adminRouter } from './routes/admin.js';
import { filesRouter } from './routes/files.js';
import { sweepUploads, uploadsRouter } from './routes/uploads.js';

// Builds the Express app without listening, so tests can create it (and re-create it on the same
// storage directory to simulate a restart). All state lives on disk except fault config and stats.
export function createApp({
  storageDir,
  defaultChunkSize = 2 * MiB,
  sessionTtlMs = SESSION_TTL_MS,
  logRequests = false,
} = {}) {
  if (!storageDir) throw new Error('storageDir is required');
  const paths = storagePaths(storageDir);
  for (const dir of [paths.uploads, paths.completed, paths.files]) fs.mkdirSync(dir, { recursive: true });

  const ctx = {
    paths,
    locks: new KeyedLock(),
    faults: new FaultInjector(),
    defaultChunkSize,
    sessionTtlMs,
  };
  ctx.sweep = (now) => sweepUploads(ctx, now);

  const app = express();
  app.disable('x-powered-by');
  app.set('etag', false); // ETag is only meaningful on file content; set explicitly there.
  app.locals.ctx = ctx;

  app.use((_req, _res, next) => {
    ctx.faults.stats.requests++;
    next();
  });
  app.use(requestLogger(logRequests));

  app.get('/health', (_req, res) => res.json({ ok: true }));
  app.use('/admin', adminRouter(ctx));

  app.use('/api', ctx.faults.middleware());
  app.use('/api/uploads', uploadsRouter(ctx));
  app.use('/api/files', filesRouter(ctx));

  app.use((req, res) => res.status(404).json({ error: 'NOT_FOUND', message: `No route for ${req.method} ${req.path}` }));
  app.use(errorHandler);
  return app;
}
