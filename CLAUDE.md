# StableShare — project memory for Claude Code

StableShare is an Android app (Kotlin, Jetpack Compose) for resumable large-file uploads and downloads (up to 1 GB) against a local Node.js mock server. It must survive pause/resume, network loss, timeouts, lost responses, server errors, app kills and restarts, and only mark a transfer COMPLETED after verifying the whole file. Deliverables: source code, a signed release APK, and a README covering architecture, transfer protocol, persistence, retry/recovery and edge cases.
Repo: https://github.com/maanit-gupta/StableShare

## How context is organised
- This file: shared rules, current phase, decisions. Loaded every session.
- `server/CLAUDE.md` and `android/CLAUDE.md`: folder-specific conventions, loaded when working there.
- `docs/DESIGN.md`: the full design and source of truth. Read only the sections relevant to the task before planning. If implementation must deviate, update DESIGN.md in the same commit and log it under Decisions below.

## Layout
- `server/` Node mock server, tests, CLI client, chaos script
- `android/` Android app (package com.maanit.stableshare, minSdk 26)
- `docs/` DESIGN.md, screenshots
- `release/` final APK only

## Non-negotiable rules
1. Every transfer state change goes through `TransferRepository.transition()` (compare-and-set validated by the StateMachine). Nothing else writes the state column.
2. Write bytes → fsync → then update the database. Never the reverse.
3. COMPLETED only after full-file SHA-256 verification (server-confirmed for uploads, local re-hash for downloads).
4. COMPLETED and CANCELLED are terminal. Nothing may revive a cancelled transfer, including restart reconciliation.
5. Chunk progress is written only while the transfer is TRANSFERRING, so stale or cancelled jobs cannot write.
6. We own retries (OkHttp retryOnConnectionFailure = false). Every failure path is bounded: it succeeds, consumes a limited attempt, waits on an external signal (network), or ends FAILED.
7. Source of truth for chunk status: the server for uploads, the manifest plus on-disk hashes for downloads.
8. Never commit keystore.properties, *.jks, or server/storage/. Never print secrets.

## State machine
QUEUED → TRANSFERRING, PAUSED, CANCELLED
TRANSFERRING → VERIFYING, RETRYING, PAUSED, FAILED, CANCELLED, QUEUED*
RETRYING → TRANSFERRING, QUEUED, PAUSED, FAILED, CANCELLED
VERIFYING → COMPLETED, RETRYING, FAILED, CANCELLED, QUEUED*
PAUSED → QUEUED, CANCELLED
FAILED → QUEUED (manual retry, keeps progress), CANCELLED
COMPLETED, CANCELLED → terminal
*QUEUED from TRANSFERRING/VERIFYING only via restart reconciliation or a system stop.

## Error classification
- RETRYABLE: timeouts, connection reset, HTTP 5xx, 429 → backoff with full jitter (base 1 s, ×2, cap 30 s), max 5 attempts per chunk, then FAILED RETRIES_EXHAUSTED.
- WAITING: no network → RETRYING with NETWORK_UNAVAILABLE, no attempts consumed, resume on connectivity.
- FATAL: 404 session/file gone, 409 conflict, 413, 416, source changed/missing, remote file changed, repeated hash mismatch, disk full → FAILED with code and message.

## Protocol summary (full spec: DESIGN.md §3)
Uploads (client-generated UUID as uploadId):
- PUT /api/uploads/:id — create session, idempotent
- PUT /api/uploads/:id/chunks/:index — header X-Chunk-SHA256; duplicate with same hash → 200 already_received
- GET /api/uploads/:id — received chunk list (used on resume and after timeouts)
- POST /api/uploads/:id/complete — assemble + verify SHA-256, idempotent
- DELETE /api/uploads/:id — cancel cleanup
Downloads:
- GET /api/files, GET /api/files/:id/manifest?chunkSize= (size, sha256, etag, per-chunk hashes)
- GET /api/files/:id/content with Range + If-Range (200 instead of 206 = remote file changed)
Admin: GET /health, GET/PUT /admin/faults, POST /admin/faults/reset, GET /admin/stats

## Concurrency
Up to N transfers at once (1–4, default 2), enforced by a single TransferCoordinatorWorker. Chunks within one transfer are sequential.

## Workflow for every session
- Start in plan mode; present a concise plan and wait for approval.
- No stubs or TODOs for required features. If blocked, stop and say why.
- Run the relevant tests and builds; fix until green. Never delete or weaken a test to pass.
- Small commits with conventional prefixes (feat:, fix:, test:, docs:, chore:).
- Finish with a report: what was built, how each requirement was verified (command + result), deviations, known limitations.
- Before ending: update the Phase tracker and append to Decisions and Open issues below. Keep this file under 200 lines.

## Phase tracker
- [ ] Phase 1 — DESIGN.md + mock server + CLI client
- [ ] Phase 2 — Android foundation (data/domain layer)
- [ ] Phase 3 — Transfer engine
- [ ] Phase 4 — UI, README, release APK

## Decisions (append: date — decision — why)

## Open issues (append; remove when resolved)
