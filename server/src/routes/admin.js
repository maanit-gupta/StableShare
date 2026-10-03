import express from 'express';
import { mutateFile } from './files.js';

export function adminRouter(ctx) {
  const { faults } = ctx;
  const router = express.Router();
  const json = express.json({ limit: '16kb', type: () => true });

  router.get('/faults', (_req, res) => res.json(faults.config));
  router.put('/faults', json, (req, res) => res.json(faults.update(req.body)));
  router.post('/faults/reset', (_req, res) => res.json(faults.reset()));
  router.get('/stats', (_req, res) => res.json(faults.stats));
  router.post('/files/:fileId/mutate', async (req, res) => res.json(await mutateFile(ctx, req.params.fileId)));

  return router;
}
