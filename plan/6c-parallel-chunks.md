# Phase 6, feature 3 — Parallel chunks within one transfer

Steps: 6.3a (server safety), 6.3b (engine, needs approval), 6.3c (UI and benchmark).

GOAL: a setting "Pieces at once per transfer" with values 1, 2 or 4 (default 1). Within one transfer, up to N chunks are in flight at the same time. The global limit of concurrent transfers still applies. With N = 1 everything behaves exactly as before.

## 6.3a Server safety

Phase 1 records each received chunk in `meta.json`. With several chunks landing at once, a read-modify-write on that file can silently lose records. Fix and prove that first.

READ: `server/CLAUDE.md`, the chunk upload handler and the metadata read/write code.

DO
1. Make concurrent chunk PUTs to the same session safe: serialise metadata updates per uploadId (a per-session mutex), or derive the received set from the chunks directory. Pick the simpler one and justify the choice in DESIGN.md. Chunk files are still written to a temp file and renamed atomically.
2. Read how `bandwidthKbps` is applied (per request or global) and document it in DESIGN.md. If it is per request, parallel runs look faster than they would on one real link; the benchmark notes in 6.3c must say so.
3. Tests: 8 concurrent chunk uploads to one session, then GET status lists all 8 and finalize succeeds; concurrent duplicates of the same chunk; concurrent upload plus status polling; the same with `dropAfterProcessRate` enabled and a fixed seed.

DONE WHEN: `npm test` green including the new tests; DESIGN.md updated; PROGRESS.md updated.

## 6.3b Engine [APPROVE]

First, read the code named below and show me a plan of 15 lines or fewer covering the points under DESIGN. Wait for my go-ahead.

READ: `UploadPipeline`, `DownloadPipeline`, `TransferEngine`, `TransferProgressTracker`, `FileStore` (part-file writes), `RetryPolicy`, `SettingsRepository`. DESIGN.md sections 7, 8 and 10 by heading.

DESIGN
- Setting `parallelChunks` (1, 2, 4; default 1) in DataStore, read once when a job starts and fixed for that job's lifetime.
- Upload: inside one `coroutineScope`, a `Semaphore(N)`. Acquire a permit BEFORE reading the chunk bytes (bounded memory), then read, hash, PUT, mark done, release. Dispatch chunks in index order.
- Download: same shape. One shared FileChannel for the `.part` file with positional writes. Each chunk is verified against the manifest, written, forced to disk, and only then marked DONE.
- Per-chunk retry: each chunk retries with its own attempt counter and RetryPolicy, and the transfer STAYS in TRANSFERRING during per-chunk backoff. (`markChunkDone` only writes while TRANSFERRING, so leaving that state would drop valid completions.) Transfer-level RETRYING is used only for network waits (the job ends, as today). Update DESIGN.md sections 7 and 8.
- Failure: a Fatal error, or a chunk that exhausts its attempts, cancels sibling workers and fails the transfer (FAILED with DONE chunks intact). Completed siblings are kept; in-flight siblings are not marked. WaitForNetwork from any worker cancels the siblings and makes ONE transition to RETRYING with NETWORK_UNAVAILABLE.
- Pause and cancel: existing job cancellation must reach every child and every OkHttp call. Cleanup stays in NonCancellable and never writes state.
- Verification starts only after every child has finished and every chunk is DONE.
- Startup integrity re-check for downloads: re-hash the 2 x N DONE chunks with the highest indices.
- `TransferProgressTracker` tracks a set of in-flight chunk indices and their bytes. Overall in-flight bytes are the sum; the speed average is over the total.

TESTS (recording fake client)
With N = 1, the whole existing engine suite passes unchanged. New: never more than N chunk requests in flight (assert the observed maximum); pause with N in flight leaves none of them marked DONE and resume sends only the missing chunks; one chunk exhausting retries cancels its siblings and gives FAILED RETRIES_EXHAUSTED with DONE chunks intact, and a manual retry sends only the rest; network loss with several workers gives exactly one RETRYING transition; cancel with several in flight; process death with several in flight resumes from the DONE set; a corrupt download chunk with N = 4 re-fetches only that chunk; a lost response on one of several chunks; zero-byte file; peak chunk buffers stay within N x chunk size. Extend the Phase 5 fuzz scenarios to pick N from 1, 2, 4 randomly, and run them at 2000 seeds once.

DONE WHEN: all tests and fuzz runs green; DESIGN.md sections 6, 7, 8, 10 updated; PROGRESS.md updated.

## 6.3c UI and benchmark

READ: UI-SPEC sections 5.8.6 and 5.10 by heading, and the Settings and Pieces-card composables.

EXACT COPY — add to docs/UI-SPEC.md in the same commit, and to strings.xml
- Settings, Transfers card: a segmented control "Pieces at once per transfer" with 1, 2, 4 (same style as "Transfers at the same time"), helper "More pieces at once can be faster on a good connection. Applies to transfers that start after you change it."
- Detail, Pieces card (5.8.6): every in-flight chunk shows as a "Moving" cell (previously one cell). Legend and summary unchanged.
- Detail stats line while a chunk is in backoff but the transfer is TRANSFERRING: "Piece {n} is retrying, attempt {a} of {max}." (n is 1-based.) Otherwise the usual speed and ETA.

TESTS: Compose tests for the segmented control persisting and for multiple in-flight cells; unit tests for the stats-line rule.

BENCHMARK
An androidTest (or a debug-only runner) against the running server: 200 MB upload and 200 MB download, 3 runs each at N = 1, 2, 4, with faults off and with the "Slow network" preset. Write the median times and throughput to `docs/benchmarks.md` with the machine, device or emulator, date (use the system date), server settings, and the `bandwidthKbps` caveat from 6.3a. Keep the default at 1. Report the numbers and I will decide whether to change the default.

DONE WHEN: tests green, benchmark table committed, docs updated, PROGRESS.md updated (include the table's headline result under Gotchas).
