import express from 'express';
import { mutateFile } from './files.js';

export function adminRouter(ctx) {
  const { faults } = ctx;
  const router = express.Router();
  const json = express.json({ limit: '16kb', type: () => true });
  router.use((_req, _res, next) => {
    ctx.lastAdminAt = Date.now();
    next();
  });

  router.get('/faults', (_req, res) => res.json(faults.config));
  router.put('/faults', json, (req, res) => res.json(faults.update(req.body)));
  router.post('/faults/reset', (_req, res) => res.json(faults.reset()));
  router.get('/stats', (_req, res) => res.json(faults.stats));
  // Not mounted when disabled, so the request falls through to the usual 404.
  if (!ctx.disableFileMutate) {
    router.post('/files/:fileId/mutate', async (req, res) => res.json(await mutateFile(ctx, req.params.fileId)));
  }

  return router;
}
