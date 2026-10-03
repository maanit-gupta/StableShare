// Per-key async mutex: serialises critical sections (meta.json updates, complete) per key.
export class KeyedLock {
  #tails = new Map();

  async run(key, fn) {
    const prev = this.#tails.get(key) ?? Promise.resolve();
    let release;
    const current = new Promise((resolve) => (release = resolve));
    const tail = prev.then(() => current);
    this.#tails.set(key, tail);
    await prev;
    try {
      return await fn();
    } finally {
      release();
      if (this.#tails.get(key) === tail) this.#tails.delete(key);
    }
  }
}
