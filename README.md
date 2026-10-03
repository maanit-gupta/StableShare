# StableShare

Resumable large-file (up to 1 GB) uploads and downloads for Android, with a local Node.js mock server that injects faults.

> Work in progress. The full README (architecture, transfer protocol, persistence, retry/recovery, edge cases) arrives in Phase 4.
> The design lives in [docs/DESIGN.md](docs/DESIGN.md).

## Quick start (server)
```sh
cd server
npm install
npm run seed     # deterministic sample files
npm start        # http://0.0.0.0:8080 (emulator: http://10.0.2.2:8080)
npm test
```
