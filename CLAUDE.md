# StableShare — project memory for Claude Code

StableShare is an Android app (Kotlin, Jetpack Compose) for resumable large-file uploads and downloads (up to 1 GB) against a local Node.js mock server. It must survive pause/resume, network loss, timeouts, lost responses, server errors, app kills and restarts, and only mark a transfer COMPLETED after verifying the whole file. Deliverables: source code, a signed release APK, and a README covering architecture, transfer protocol, persistence, retry/recovery and edge cases.
Repo: https://github.com/maanit-gupta/StableShare

## How context is organised
- This file: shared rules, current phase, decisions. Loaded every session.
- `server/CLAUDE.md` and `android/CLAUDE.md`: folder-specific conventions, loaded when working there.
- `docs/DESIGN.md`: the full design and source of truth. Read only the sections relevant to the task before planning. If implementation must deviate, update DESIGN.md in the same commit and log it under Decisions below.
- `docs/UI-SPEC.md`: the UI source of truth. Read it before any UI work; never invent UI that isn't in it.

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
- WAITING: no network → RETRYING with NETWORK_UNAVAILABLE; metered network with Wi-Fi only on → RETRYING with METERED_NETWORK. No attempts consumed, resume when usableNetwork turns true. The engine sends nothing while usableNetwork is false (NetworkGuard, 1500 ms debounce).
- FATAL: 404 session/file gone, 409 conflict, 413, 416, source changed/missing, remote file changed, repeated hash mismatch, disk full → FAILED with code and message.

## Protocol summary (full spec: DESIGN.md §3)
Uploads (client-generated UUID as uploadId):
- PUT /api/uploads/:id — create session, idempotent
- PUT /api/uploads/:id/chunks/:index — header X-Chunk-SHA256; duplicate with same hash → 200 already_received
- GET /api/uploads/:id — received chunk list (used on resume and after timeouts)
- POST /api/uploads/:id/complete — assemble + verify SHA-256, idempotent
- DELETE /api/uploads/:id — cancel cleanup
- Instant upload: create answers `instant` (bool); a known sha256+size → 200 COMPLETED with all chunks and `sha256`, no chunks sent
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
- Fuzz tests (`test/.../fuzz`, part of testDebugUnitTest): `./gradlew :app:testDebugUnitTest --tests '*fuzz*' -Pfuzz.seeds=2000` for a deep run, `-Pfuzz.seed=S` to replay a failure; add fixed seeds to `app/src/test/resources/fuzz-regressions.txt`.
- Small commits with conventional prefixes (feat:, fix:, test:, docs:, chore:).
- Finish with a report: what was built, how each requirement was verified (command + result), deviations, known limitations.
- Before ending: update the Phase tracker and append to Decisions and Open issues below. Keep this file under 200 lines.

## Phase tracker
- [x] Phase 1 — DESIGN.md + mock server + CLI client (2026-10-03: 32 server tests green, chaos test passing)
- [x] Phase 2 — Android foundation (data/domain layer) (2026-10-03: 112 JVM unit tests green; health() verified from the API 37 emulator; branch phase-2-android-foundation)
- [x] Phase 3 — Transfer engine (2026-10-03: 163 JVM unit tests green; emulator chaos run with kill -9 → both 200 MB transfers COMPLETED, hashes match; branch phase-3-transfer-engine)
- [ ] Phase 4 — UI, README, release APK
- [x] Feature 1 — Wi-Fi only (2026-10-04: 282 JVM unit tests green; emulator `svc wifi disable/enable` ×3 during a 200 MB upload: apiRequests flat on mobile data, upload resumed and verified; branch feature-1-wifi-only)

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

- 2026-10-03 — Coordinator = unique work "transfer-coordinator", APPEND_OR_REPLACE (KEEP drops a request landing while the coordinator exits). In-process dedupe via TransferEngine.isAcceptingWork() (flag cleared before the final DB check), so running coordinators aren't chained needlessly. Expedited request; refused setForeground is logged and ignored.
- 2026-10-03 — No state-machine change: verification setbacks (MISSING_CHUNKS, local full-file mismatch) go VERIFYING → RETRYING (nextRetryAt = now) → TRANSFERRING.
- 2026-10-03 — claimNextQueued(limit, exclude): the coordinator never claims a RETRYING row its own job is backing off in-process. New repo ops promoteDueRetries/promoteWaitingForNetwork/setSourceInfo; all state writes still go through transitionLocked.
- 2026-10-03 — Attempt budgets: chunk failures use persisted chunks.attempts; non-chunk steps (session, status, complete, local hash) an in-memory per-run counter (max 5). Lost-response confirmation consumes no attempt; the status check runs only if the whole body was written.
- 2026-10-03 — Upload 404 SESSION_NOT_FOUND → reset + recreate the session once per run, second → FAILED (DESIGN §11 #9 updated).
- 2026-10-03 — Download resume re-hashes only the last 2 DONE chunks; older damage is caught by the full-file check, which resets only bad chunks (DESIGN §10 updated). REMOTE_FILE_CHANGED keeps the .part until cancel.
- 2026-10-03 — Cancel cleanup lives in TransferController (NonCancellable: stopJob → DELETE session / delete local file), so cancelling PAUSED/FAILED/QUEUED rows cleans up without a coordinator.
- 2026-10-03 — ACCESS_LOCAL_NETWORK denied → ambiguous transport errors become FAILED UNKNOWN "Local network permission denied" (debug screen shows a banner + re-request).
- 2026-10-03 — WorkManager on-demand init (Configuration.Provider + AppWorkerFactory); work-runtime/testing 2.12.0. TransferApi interface (data/net) lets tests use an in-memory FakeTransferServer; FileStore is `open` only for the disk-full test.
- 2026-10-03 — Engine tests run under runTest (virtual time) while Room runs on its own threads, so virtual time can jump ahead while a query is pending; tests synchronise with gates/flows, never timestamps. Process death is simulated by freezing the engine's only thread.
- 2026-10-03 — Chaos run: adb root unavailable (google_apis_playstore image); used `adb shell run-as com.maanit.stableshare kill -9 <pid>` (real SIGKILL from the app uid).

- 2026-10-03 — Phase 4 answers (user): ACCESS_LOCAL_NETWORK asked with POST_NOTIFICATIONS on Get started/Skip and once per process on Transfers; release kill test on a rootable google_apis_ps16k AVD `Pixel_9_root`; download-sheet manifest failure reverts the button + "Couldn't add {name}. {reason}"; Activity hides QUEUED-from-RETRYING/TRANSFERRING/VERIFYING; slider labels "Latency/Jitter/Bandwidth/Error rate/Timeout rate/Mid-transfer drop rate/Lost response rate/Corruption rate"; a11y labels Back/More options/Copy {field}/Open transfer settings/Expanded-Collapsed; licences = simple Neutral screen; onboarding page 3 waves on a loop.
- 2026-10-03 — Engine bug fixed: OkHttp pool now evicts idle connections at 4 s (< Node's 5 s keep-alive); stale sockets made the first GET after idle fail with retries off. Regression test in ProtocolClientTest.
- 2026-10-03 — Upload screen scrolls between the top bar and the pinned launch pad (short screens / 200% font); hoop overlay clipped to the scroll viewport.
- 2026-10-04 — Engine bug fixed: a coordinator started in the background had setForeground refused once and never showed the ongoing notification; ForegroundPromoter retries every 10 s (ForegroundPromoterTest).
- 2026-10-04 — Notifications: ongoing "Moving {n} file(s)" with n = max(running, queued + active); results on channel "results" via TransferResultNotifier (in-process transitions only); taps carry EXTRA_TRANSFER_ID / EXTRA_OPEN_TRANSFERS and skip the splash.
- 2026-10-04 — Times: Started = createdAt, Finished = completedAt (COMPLETED) or updatedAt (CANCELLED); average speed = bytes / duration. Queue position = QUEUED rows by (createdAt, id), same as claimable().
- 2026-10-04 — Release: signing from android/keystore.properties when present; R8 keeps AGP 9 `optimization.packageScope` = androidx/kotlin/kotlinx only (22 MB → 3.7 MB), app classes untouched, so no keep rules; minified build smoke-tested.
- 2026-10-04 — Compose UI tests run under Robolectric in testDebugUnitTest; instrumented PickedFileUploadTest drives the real system picker with UI Automator 2.4.0 (needs the mock server on the host).
- 2026-10-04 — README's 12 requirements are a reconstruction approved by the user (assignment text not in the repo). Screens that scroll under the transparent status bar draw a page-coloured scrim (UI-SPEC §5.3).
- 2026-10-04 — The user renamed phase-4-ui to main and pushed it (main now holds Phases 1–4); phase-2/phase-3 branches remain as ancestors.
- 2026-10-04 — Wi-Fi only: ErrorCode is stored by name (TEXT), so METERED_NETWORK needed no Room migration (round-trip test instead).
- 2026-10-04 — Proactive stop lives at the request level (NetworkGuard/GuardedTransferApi, pipelines only), not as a job-level watchdog: it cancels in-flight calls after 1500 ms unusable, makes new calls wait ≤ 1500 ms, lets local-only work (hashing, verify, finalise) finish, and ends a backoff early via RETRYING → TRANSFERRING → RETRYING(code) (no RETRYING → RETRYING in the state machine).
- 2026-10-04 — Wi-Fi only gates engine traffic only; user-started requests (health, Test connection, file list/manifest, simulator, cancel DELETE) still go out. Wi-Fi only counts as on until DataStore answers.
- 2026-10-04 — Waiting rows are re-coded live (METERED_NETWORK ↔ NETWORK_UNAVAILABLE) by EngineBootstrap via recodeNetworkWaiters, which writes only errorCode/errorMessage plus an INFO event, never the state column.
- 2026-10-04 — QUEUED rows while wifiOnly && network != Unmetered show "Waiting for Wi-Fi" (inkSecondary, Wi-Fi title/stats; QUEUED mascot/ring/plane) — user decision, UI-SPEC §6. DataStore key is `wifi_only` (snake_case like the others).
- 2026-10-04 — Pipeline events go through `TransferRepository.logEventWhile(id, StateMachine.ACTIVE, …)` (state checked in the same transaction) — the engine fuzz found a RETRY_SCHEDULED log landing after a concurrent cancel. Controller cleanup logs stay unguarded.
- 2026-10-04 — Instant upload (6.2a): hash index `index/<sha256>.json` (path relative to the storage root, under a `sha:<hash>` lock); instant sessions are hard-linked to `completed/<id>.bin` (the code's name, not the plan's `<id>-<fileName>`). Every create body has `instant`, and COMPLETED ones `sha256`, so a repeated create (lost response) still reads instant. Stale entries are dropped on lookup; deleting the indexed original makes the next identical upload a normal one, which re-indexes.
- 2026-10-04 — Instant upload (client): on `instant` the pipeline marks all chunks DONE, logs INSTANT_UPLOAD once, goes VERIFYING and still compares `complete`'s sha256 with the local hash (mismatch → FAILED FILE_HASH_MISMATCH); a status-reported COMPLETED session takes the same route. "Already on server" pill on Transfers/Upload/History rows and under the detail file line; Details row "Data sent" placed after "Pieces".

- 2026-10-04 — Parallel chunks engine (6.3b): `parallelChunks` 1/2/4 (default 1), fixed per job. N = 1 runs the unchanged sequential path; N > 1 runs a Semaphore(N) worker pool (permit before reading → ≤ N buffers), per-chunk backoff in place while TRANSFERRING, one terminal write via a per-run mutex, download tail re-check 2 × N. Per-chunk open+write+fsync kept instead of a shared FileChannel. DESIGN §6.4/7/8/10 updated.
- 2026-10-04 — Parallel chunks UI/benchmark (6.3c): "Pieces at once per transfer" 1/2/4 below "Transfers at the same time"; a piece backing off in place shows "Piece {n} is retrying, attempt {a} of {max}." ({a} = failures so far, like RETRYING's line). Default stays 1 pending the user (docs/benchmarks.md).
- 2026-10-04 — UIDT 6.4a: no TransferRunLoop class (TransferEngine.run already is the loop, worker already thin); the blocking runLock became a process-wide `RunLease` (single-flight + rerun flag, so an exit-window host is never lost) — user-approved deviation from plan 6d; DESIGN §9.
- 2026-10-04 — UIDT 6.4b: user actions call ensureRunning(userInitiated = true) → HostSelectingScheduler schedules a UIDT job (API 34+, visible, lease free, none pending; NETWORK_TYPE_ANY even with Wi-Fi only) else WorkManager. Job stops: USER → PAUSED (VERIFYING → QUEUED, no PAUSED edge), CONNECTIVITY → RETRYING NETWORK_UNAVAILABLE, other → QUEUED + one background ensureRunning. Ongoing notification gains "Pause transfers" (pauseAll). WorkManager JobScheduler ids capped at 999 999; UIDT job id 1 000 001.
- 2026-10-04 — UIDT 6.4c: Task Manager "Stop" (and Force stop) kills the process without onStopJob (exit REASON_USER_REQUESTED, verified on API 37), so reconciliation used to resume the transfer on the next open. User decision: the first coordinator run of a process reads `PreviousProcessExit` (ApplicationExitInfo, API 30+) and on a user stop moves TRANSFERRING → PAUSED, VERIFYING → QUEUED (no PAUSED edge); DESIGN §9. JobScheduler maps NETWORK_TYPE_ANY to INTERNET&VALIDATED, so a UIDT job needs a validated network.

## Open issues (append; remove when resolved)
- Server disk-full (507) is mapped in the error handler but has no automated test (needs a size-limited filesystem). Android DISK_FULL is tested.
- Crash between finalizePart and setLocalUri re-downloads the file (the verified copy is left orphaned); a cancel whose cleanup is cut short by process death leaves a server session (expires in 24 h) or a local file.
- REMOTE_FILE_CHANGED manual retry reuses the stored ETag and fails again; UI-SPEC's copy tells the user to cancel and download again (no "download again" action in the spec).
- After kill -9, resumption took ~18 s on API 37 (WorkManager stops its stale run on restart; the 15 s backstop wake-up / reschedule restarts it). Generated test files are never deleted.
- Phase 4 blocked on android/keystore.properties (missing): signed assembleRelease, release/StableShare-1.0.0.apk, release smoke test (onboarding, 200 MB up + down with Flaky Wi-Fi, kill -9 on Pixel_9_root, Restored pill, both Verified) and the final report remain.
- Wi-Fi only: the classifier is shared, so a user-started request (e.g. Settings → Test connection) that fails on mobile data with Wi-Fi only on reads "Couldn't connect: Waiting for Wi-Fi". Up to one request can start on mobile data in the instant before the network callback reports the switch (seen once in three emulator cycles).
