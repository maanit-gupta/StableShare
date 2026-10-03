// Fault injection for /api/* only (never /admin or /health).
//
// When enabled, every /api request draws the same fixed sequence of numbers from one seeded PRNG,
// so a given seed plus a given request sequence always produces the same faults.
// Precedence: error > timeout > dropMidBody > dropAfterProcess; corrupt is independent.
import { Transform } from 'node:stream';
import { setTimeout as sleep } from 'node:timers/promises';
import { ApiError } from './errors.js';

export const DEFAULT_FAULTS = Object.freeze({
  enabled: false,
  seed: 1,
  latencyMs: 0,
  latencyJitterMs: 0,
  bandwidthKbps: 0, // kilobits per second, 0 = unlimited
  errorRate: 0, // 503 before processing
  timeoutRate: 0, // accept, never respond
  dropMidBodyRate: 0, // destroy the socket partway through a request or response body
  dropAfterProcessRate: 0, // persist a chunk/complete, then destroy the socket instead of replying
  corruptRate: 0, // flip a byte in a download body
});

const RATE_KEYS = ['errorRate', 'timeoutRate', 'dropMidBodyRate', 'dropAfterProcessRate', 'corruptRate'];
const NON_NEGATIVE_KEYS = ['latencyMs', 'latencyJitterMs', 'bandwidthKbps'];

// mulberry32: tiny, fast, good enough for test determinism.
export function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function emptyStats() {
  return {
    requests: 0,
    apiRequests: 0,
    faults: { latency: 0, error: 0, timeout: 0, dropMidBody: 0, dropAfterProcess: 0, corrupt: 0 },
    dedupedChunks: 0,
  };
}

function noFault() {
  return {
    latencyMs: 0,
    error: false,
    timeout: false,
    dropMidBody: false,
    dropAfterProcess: false,
    corrupt: false,
    dropFraction: 0,
    corruptFraction: 0,
    afterProcessEligible: false, // set by routes whose effect is persisted before replying
    dropHandled: false, // set when a body stream applied dropMidBody itself
  };
}

export class FaultInjector {
  constructor() {
    this.reset();
  }

  reset() {
    this.config = { ...DEFAULT_FAULTS };
    this.rng = mulberry32(this.config.seed);
    this.stats = emptyStats();
    return this.config;
  }

  update(partial) {
    if (partial === null || typeof partial !== 'object' || Array.isArray(partial)) {
      throw new ApiError(400, 'INVALID_REQUEST', 'Fault config must be a JSON object');
    }
    const next = { ...this.config };
    for (const [key, value] of Object.entries(partial)) {
      if (!(key in DEFAULT_FAULTS)) throw new ApiError(400, 'INVALID_REQUEST', `Unknown fault key "${key}"`);
      if (key === 'enabled') {
        if (typeof value !== 'boolean') throw new ApiError(400, 'INVALID_REQUEST', 'enabled must be a boolean');
      } else if (key === 'seed') {
        if (!Number.isInteger(value)) throw new ApiError(400, 'INVALID_REQUEST', 'seed must be an integer');
      } else if (RATE_KEYS.includes(key)) {
        if (typeof value !== 'number' || !(value >= 0 && value <= 1)) {
          throw new ApiError(400, 'INVALID_REQUEST', `${key} must be a number in [0, 1]`);
        }
      } else if (NON_NEGATIVE_KEYS.includes(key)) {
        if (typeof value !== 'number' || !(value >= 0) || !Number.isFinite(value)) {
          throw new ApiError(400, 'INVALID_REQUEST', `${key} must be a non-negative number`);
        }
      }
      next[key] = value;
    }
    this.config = next;
    if ('seed' in partial) this.rng = mulberry32(next.seed);
    return this.config;
  }

  decide() {
    const c = this.config;
    if (!c.enabled) return noFault();
    const r = this.rng;
    // Always draw the full sequence so decisions never shift when one rate changes.
    const [jitter, error, timeout, drop, dap, corrupt, dropPos, corruptPos] = Array.from({ length: 8 }, r);
    const f = noFault();
    f.latencyMs = Math.max(0, Math.round(c.latencyMs + (jitter * 2 - 1) * c.latencyJitterMs));
    f.error = error < c.errorRate;
    f.timeout = !f.error && timeout < c.timeoutRate;
    f.dropMidBody = !f.error && !f.timeout && drop < c.dropMidBodyRate;
    f.dropAfterProcess = !f.error && !f.timeout && !f.dropMidBody && dap < c.dropAfterProcessRate;
    f.corrupt = corrupt < c.corruptRate;
    f.dropFraction = dropPos;
    f.corruptFraction = corruptPos;
    return f;
  }

  middleware() {
    return (req, res, next) => {
      this.stats.apiRequests++;
      const f = this.decide();
      req.fault = f;
      this.#hookJson(req, res);
      const go = () => {
        if (f.error) {
          this.stats.faults.error++;
          res.setHeader('Connection', 'close');
          res.status(503).json({ error: 'INJECTED_FAULT', message: 'Injected 503 (errorRate)' });
          return;
        }
        if (f.timeout) {
          this.stats.faults.timeout++;
          return; // accepted, never answered
        }
        next();
      };
      if (f.latencyMs > 0) {
        this.stats.faults.latency++;
        setTimeout(go, f.latencyMs);
      } else {
        go();
      }
    };
  }

  // dropAfterProcess and dropMidBody for JSON responses.
  #hookJson(req, res) {
    const original = res.json.bind(res);
    res.json = (body) => {
      const f = req.fault;
      if (f.dropAfterProcess && f.afterProcessEligible && res.statusCode < 300) {
        this.stats.faults.dropAfterProcess++;
        req.socket.destroy();
        return res;
      }
      if (f.dropMidBody && !f.dropHandled) {
        f.dropHandled = true;
        this.stats.faults.dropMidBody++;
        const payload = Buffer.from(JSON.stringify(body));
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        res.setHeader('Content-Length', payload.length);
        res.write(payload.subarray(0, Math.floor(payload.length * f.dropFraction)), () => req.socket.destroy());
        return res;
      }
      return original(body);
    };
  }

  // Transforms for an incoming body of `length` bytes: bandwidth throttle and mid-body drop.
  requestStreams(req, length) {
    const streams = [];
    if (this.config.bandwidthKbps > 0) streams.push(this.#throttle());
    const f = req.fault;
    if (f?.dropMidBody) {
      f.dropHandled = true;
      const dropAt = Math.floor(f.dropFraction * length);
      let seen = 0;
      const stats = this.stats;
      streams.push(
        new Transform({
          transform(buf, _enc, cb) {
            if (seen + buf.length > dropAt) {
              stats.faults.dropMidBody++;
              req.socket.destroy();
              const err = new Error('Injected mid-body drop');
              err.code = 'INJECTED_DROP';
              cb(err);
              return;
            }
            seen += buf.length;
            cb(null, buf);
          },
        }),
      );
    }
    return streams;
  }

  #throttle() {
    const cfg = () => this.config;
    const start = Date.now();
    let total = 0;
    return new Transform({
      transform(buf, _enc, cb) {
        total += buf.length;
        const wait = throttleWait(start, total, cfg().bandwidthKbps);
        if (wait > 0) setTimeout(() => cb(null, buf), wait);
        else cb(null, buf);
      },
    });
  }

  // Streams `source` (exactly `length` bytes) to `res`, applying throttle, corrupt and mid-body drop.
  async sendBody(req, res, source, length) {
    const f = req.fault ?? noFault();
    const dropAt = f.dropMidBody ? Math.floor(f.dropFraction * length) : -1;
    const corruptAt = f.corrupt && length > 0 ? Math.floor(f.corruptFraction * length) : -1;
    if (dropAt >= 0) f.dropHandled = true;
    const start = Date.now();
    let sent = 0;
    try {
      for await (const buf of source) {
        if (res.destroyed) return;
        if (corruptAt >= sent && corruptAt < sent + buf.length) {
          buf[corruptAt - sent] ^= 0xff;
          this.stats.faults.corrupt++;
        }
        if (dropAt >= 0 && sent + buf.length > dropAt) {
          this.stats.faults.dropMidBody++;
          await new Promise((resolve) => res.write(buf.subarray(0, dropAt - sent), resolve));
          req.socket.destroy();
          return;
        }
        const wait = throttleWait(start, sent + buf.length, this.config.bandwidthKbps);
        if (wait > 0) await sleep(wait);
        sent += buf.length;
        if (!res.write(buf)) await drainOrClose(res);
      }
      res.end();
    } finally {
      source.destroy();
    }
  }
}

function throttleWait(start, totalBytes, kbps) {
  if (!kbps) return 0;
  const dueMs = (totalBytes * 8) / kbps; // kbps = bits per millisecond
  return Math.max(0, Math.round(start + dueMs - Date.now()));
}

function drainOrClose(res) {
  return new Promise((resolve) => {
    const done = () => {
      res.off('drain', done);
      res.off('close', done);
      resolve();
    };
    res.on('drain', done);
    res.on('close', done);
  });
}
