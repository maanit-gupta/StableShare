# server/ — Node mock server

## Stack and commands
Node LTS, Express, ES modules, node:test + supertest. No heavy dependencies.
npm install · npm run seed · npm run dev (node --watch) · npm start · npm test · ./scripts/chaos-test.sh
Env defaults: PORT=8080, HOST=0.0.0.0, STORAGE_DIR=./storage, DEFAULT_CHUNK_SIZE=2097152. The emulator reaches the server at http://10.0.2.2:8080.

## Conventions
- All metadata lives on disk, so a server restart loses nothing. Every metadata write is atomic: temp file → fsync → rename.
- Stream request and response bodies; never buffer a whole file in memory. Hash while streaming.
- Errors are JSON: { "error": "CODE", "message": "..." } with the status codes in DESIGN.md §3.
- Storage layout: storage/uploads/<id>/meta.json and chunks/<index>.bin; storage/completed/; storage/files/<fileId>.bin + <fileId>.meta.json + <fileId>.manifest.<chunkSize>.json.
- Fault injection is one readable middleware module, applied to /api/* only, never /admin or /health, using a seeded PRNG so tests are deterministic.
- Each test uses a fresh temp STORAGE_DIR. Restart tests re-create the app on the same directory.
- Log every request: method, path, status, duration, uploadId and chunk index.
- The protocol is consumed by the Android app: never change an endpoint's contract without updating DESIGN.md and noting it under Decisions in the root CLAUDE.md.
