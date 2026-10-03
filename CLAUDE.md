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
- [x] Phase 1 — DESIGN.md + mock server + CLI client (2026-10-03: 32 server tests green, chaos test passing)
- [x] Phase 2 — Android foundation (data/domain layer) (2026-10-03: 112 JVM unit tests green; health() verified from the API 37 emulator; branch phase-2-android-foundation)
- [ ] Phase 3 — Transfer engine
- [ ] Phase 4 — UI, README, release APK

## Decisions (append: date — decision — why)
- 2026-10-03 — Server on Express 5 (ESM), sole runtime dependency; tests use node:test + supertest — matches server/CLAUDE.md, async handlers forward errors natively.
- 2026-10-03 — Chunk PUT success is 200 with `status: "stored"` (duplicate: `"already_received"`); create is 201 new / 200 existing — clients treat any 2xx as success, the status field distinguishes.
- 2026-10-03 — Limits: fileSize ≤ 1 GiB, chunkSize 1 KiB–64 MiB (default 2 MiB) — 1 KiB minimum keeps tests fast, 64 MiB caps memory per chunk.
- 2026-10-03 — Seed data = AES-256-CTR keystream keyed by sha256("stableshare-seed:<fileId>"); ETag = quoted sha256 — reproducible hashes on every machine, ETag changes iff content changes.
- 2026-10-03 — Manifest cache file is files/<fileId>.manifest.<chunkSize>.json (one per chunk size), invalidated by ETag — spec asks for per-chunk-size caching.
- 2026-10-03 — Multi-range or malformed Range → 416 (not ignored) — protocol is single-range only; explicit failure beats a surprise 200.
- 2026-10-03 — /admin/faults/reset also zeroes /admin/stats; dropAfterProcess only applies to chunk PUT and complete — those are the only persisted-then-reply operations.
- 2026-10-03 — CLI client state = JSON sidecars (<path>.stableshare-upload.json, <out>.stableshare-download.json + <out>.part); chaos test runs its own server on port 18080 — avoids clashing with a dev server on 8080.
- 2026-10-03 — `server:CLAUDE.md` (macOS colon artefact) moved to server/CLAUDE.md.
- 2026-10-03 — Android toolchain: AGP 9.4.1 built-in Kotlin 2.2.10, KSP 2.3.12, Room 2.8.5, OkHttp 5.5, kotlinx-serialization 1.11, DataStore 1.2.1, Robolectric 4.17 (sdk 36) for Room/FileStore tests; the test JVM gets `--add-exports java.base/jdk.internal.access` because Robolectric needs it on JDK 25.
- 2026-10-03 — `transition()` takes an optional `expectedFrom` — a stopped worker's RETRYING→QUEUED must not un-pause a row the user paused meanwhile (PAUSED→QUEUED is legal).
- 2026-10-03 — Reconciliation moves only TRANSFERRING/VERIFYING → QUEUED; RETRYING keeps nextRetryAt and is claimed by claimNextQueued when due (null = waiting for network, woken by the Phase 3 connectivity monitor). DESIGN §9 updated.
- 2026-10-03 — ErrorCode is the 15-value enum; DESIGN's SESSION_EXPIRED/REMOTE_CHANGED/HASH_MISMATCH renamed to SESSION_NOT_FOUND/REMOTE_FILE_CHANGED/RETRIES_EXHAUSTED. 413/other 4xx/protocol violations → Fatal UNKNOWN; 416 and 404 FILE_NOT_FOUND → REMOTE_FILE_CHANGED; 400 INCOMPLETE_BODY = transport drop. Offline check applies to timeouts too.
- 2026-10-03 — ConnectivityChecker needs INTERNET capability, not VALIDATED — a LAN-only network hosting the mock server never validates.
- 2026-10-03 — Android 17 (API 37) blocks app traffic to private-range hosts (10.0.2.2, LAN) without runtime ACCESS_LOCAL_NETWORK; declared and requested at launch. Found during emulator verification (connects silently timed out).
- 2026-10-03 — Part files are `<safeName>.<transferId>.part` in getExternalFilesDir(DOWNLOADS); finalize = Files.move without REPLACE_EXISTING, then " (n)" suffixes, so nothing is overwritten. file:// URIs (generated files) are read directly, content:// via ContentResolver/DocumentFile.
- 2026-10-03 — allowBackup=false: a restored transfer DB without its part files/URI grants would be inconsistent.

## Open issues (append; remove when resolved)
- Disk-full (507) is mapped in the error handler but has no automated test (needs a size-limited filesystem); Android-side DISK_FULL lands in Phase 3.
- Not yet pushed to the GitHub remote (origin is configured). Phase 2 lives on branch phase-2-android-foundation (not merged to main).
- content:// source reads (SAF, persisted grants) have no automated test — Robolectric has no document provider; cover with an instrumented test in Phase 3.
- Phase 3 must request/handle ACCESS_LOCAL_NETWORK denial (transfers would otherwise just time out as CONNECTION_LOST); the placeholder only requests it.
- Placeholder's first health check can fire before the permission dialog is answered (it then times out once; the post-grant re-check succeeds).
- No instrumented (connectedDebugAndroidTest) tests yet; the template ones were removed.
