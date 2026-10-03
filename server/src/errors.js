export class ApiError extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

// Express error handler: always JSON {error, message, ...extra}.
export function errorHandler(err, req, res, _next) {
  if (res.headersSent || req.socket.destroyed) {
    req.socket.destroy();
    return;
  }
  let status = 500;
  let body = { error: 'INTERNAL', message: 'Internal server error' };
  if (err instanceof ApiError) {
    status = err.status;
    body = { error: err.code, message: err.message, ...err.extra };
  } else if (err?.code === 'ENOSPC') {
    status = 507;
    body = { error: 'INSUFFICIENT_STORAGE', message: 'Server disk is full' };
  } else if (err?.type === 'entity.parse.failed') {
    status = 400;
    body = { error: 'INVALID_REQUEST', message: 'Malformed JSON body' };
  } else if (err?.type === 'entity.too.large') {
    status = 413;
    body = { error: 'INVALID_REQUEST', message: 'JSON body too large' };
  } else {
    console.error('[error]', req.method, req.originalUrl, err);
  }
  // A body we never read would otherwise sit in the socket; close it after replying.
  if (!req.complete) res.setHeader('Connection', 'close');
  res.status(status).json(body);
}
