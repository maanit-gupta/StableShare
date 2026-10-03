const UPLOAD_PATH = /^\/api\/uploads\/([^/?]+)(?:\/chunks\/(\d+))?/;

// One line per request: method, path, status, duration, uploadId, chunk index, injected fault.
export function requestLogger(enabled) {
  return (req, res, next) => {
    if (!enabled) return next();
    const t0 = process.hrtime.bigint();
    let logged = false;
    const log = (status) => {
      if (logged) return;
      logged = true;
      const ms = Number(process.hrtime.bigint() - t0) / 1e6;
      const m = UPLOAD_PATH.exec(req.originalUrl);
      const parts = [new Date().toISOString(), req.method, req.originalUrl, status, `${ms.toFixed(1)}ms`];
      if (m) parts.push(`upload=${m[1]}`);
      if (m?.[2] !== undefined) parts.push(`chunk=${m[2]}`);
      const f = req.fault;
      if (f) {
        const tags = ['error', 'timeout', 'dropMidBody', 'dropAfterProcess', 'corrupt'].filter((k) => f[k]);
        if (tags.length) parts.push(`fault=${tags.join(',')}`);
      }
      console.log(parts.join(' '));
    };
    res.on('finish', () => log(res.statusCode));
    res.on('close', () => log(res.writableFinished ? res.statusCode : 'DROPPED'));
    next();
  };
}
