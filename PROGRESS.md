NEXT STEP: D2
# StableShare — Progress

Updated by Claude Code at the end of every session. Keep this file under 120 lines: when "Gotchas" grows past 15 lines, merge related items.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[?]` blocked on a question · `[USER]` my step

## Resume here

Scope reset: shipping path = A–F in `plan/FINAL-PHASES.md` (replaces plan files 6d, 7, 8, 9, 10). Next step: see the top line.
Still open for the user: parallel-chunks default and the other 6.3c questions.

## Checklist

Phases 1–4 (server, Android foundation, engine, UI, release 1.0.0): `[x]` done before this plan.

### Phase 5 — Safety net
- [x] 5.1 GitHub Actions CI (`plan/5-safety-net.md`) — workflows "CI" (`ci.yml`: jobs `server`, `android`) and "Chaos test" (`chaos.yml`, workflow_dispatch only)
- [x] 5.1u [USER] push and confirm the CI run is green (2026-10-04: CI green on main push 37176085635 and PR #1)
- [x] 5.2 Model-based and fuzz tests (2026-10-04: `fuzz/RepositoryModelTest`, `fuzz/EngineFuzzTest`; both green at 200 and 2000 seeds; 1 bug found and fixed)

### Phase 6 — Features
- [x] 6.1a Wi-Fi only: engine and tests (`plan/6a-wifi-only.md`) (2026-10-04: already shipped in d7f485c; every behaviour and tests (a)–(i) present in WifiOnlyTest, NetworkGuardTest, ErrorClassifierTest, WakeupPlanTest, WorkManagerSchedulerTest; DESIGN §7/§9 cover it; 285 JVM tests green; no code change)
- [x] 6.1b Wi-Fi only: UI, copy and docs (2026-10-04: already shipped in 90dd329/2eda46b; all copy in strings.xml + UI-SPEC §5.4/5.10/6/7/8; only gap was a §7 METERED_NETWORK copy test, added; 286 JVM tests green; manual check = the Feature 1 emulator run recorded in CLAUDE.md, not repeated)
- [x] 6.2a Instant upload: server (`plan/6b-instant-upload.md`) (2026-10-04: 1172a70; 43 server tests green incl. 11 in `test/instant.test.js`; chaos script passes)
- [x] 6.2b Instant upload: client and UI (2026-10-04: 9d74c4a, 2084ce8, b8bc235; 301 JVM tests green, fuzz 2000 seeds green; manual check on API 37 emulator: 200 MB picked file uploaded normally (1 min 48 s, Verified ec3375d6…a1da), identical re-upload COMPLETED in ~6 s with 2 API requests, `instantUploads: 1`, "Already on server" pill in History + detail, Details "Data sent: None, the server already had this file", activity shows the INSTANT_UPLOAD line)
- [x] 6.3a Parallel chunks: server safety (`plan/6c-parallel-chunks.md`) (2026-10-04: 6436163; server already serialised meta.json per uploadId with KeyedLock, so no code change; 4 tests in `test/concurrency.test.js`, all 4 fail with the lock bypassed; 47 server tests green; DESIGN §6.4 + fault table updated)
- [x] 6.3b Parallel chunks: engine [APPROVE] (2026-10-04: 4bfe6ff, 2587b46 + docs; 319 JVM tests green incl. 16 in `engine/ParallelChunksTest`; fuzz 2000 seeds green with N ∈ {1,2,4}; seed 1346 re-checked)
- [x] 6.3c Parallel chunks: UI and benchmark (2026-10-04: c5b6918 UI, 90be639 test de-flake, c258804/0b68c71/0e57920 benchmark harness, cd598d7 docs; 325 JVM tests green ×2; benchmark on Pixel_9_root API 37, table in docs/benchmarks.md)
- [x] 6.4a UIDT: read docs, plan, extract TransferRunLoop [APPROVE] (`plan/6d-uidt.md`) (2026-10-04: approved variant = no new class, `TransferEngine.run` already is the loop; `RunLease` single-flight with rerun flag, 93c2c77; latent claim race fixed, 242f74e; 329 JVM tests green, fuzz 2000 green; DESIGN §9 "Hosts and the run lease")
- [x] 6.4b UIDT: TransferJobService, scheduling, stop mapping (2026-10-04: ea099a0 code, 7fbcbb3 docs; 343 JVM tests green, fuzz 2000 green; `HostSelectingSchedulerTest`, `TransferJobHostTest`, pauseAll + notification action tests; no emulator run yet, that is 6.4c)
- [x] 6.4c UIDT: emulator verification and docs (2026-10-04: b2aa451 user-stop pause, 38c6fca + this commit docs; 350 JVM tests green, fuzz 2000 green. API 37: UIJ in dumpsys, listed in Active apps, Task Manager Stop at 66/100 → PAUSED and stayed paused 60 s after reopening, resumed from the app → COMPLETED, server-verified 8d91e2aa…1710, 100 CHUNK_DONE. API 33 (new AVD Pixel_API33): WorkManager host only (SystemJobService + FGS 1001, no UIJ), kill -9 ×3 → reconciled and resumed, COMPLETED, verified a9b19920…dd97)

### Deferred (time)
- 6.4 UIDT job: NOT deferred after all; 6.4a–c were already built and verified (see above), user kept it at step A (2026-10-04)
- 7.1 Resilience report: deferred (time)
- 8.2 Full screenshot set: deferred (time); minimal set in step D
- 10.4 Design notes: deferred (time)

### Final phases (`plan/FINAL-PHASES.md`)
- [x] A Scope reset (2026-10-04: UIDT kept since it is in the code; changed PROGRESS.md, CLAUDE.md Decisions, README.md:373 force-stop now pauses; DESIGN §9 already accurate)
- [x] B Release build — 1.1.0 (versionCode 2), release/StableShare-1.1.0.apk, 3 807 237 bytes, v2-signed; 350 unit tests green; sha256 in release/CHECKSUMS.txt
- [x] C Real-device test (2026-10-04, OnePlus CPH2717, Android 16/API 36, release 1.1.0 over LAN `http://192.168.1.2:8080`, no rebuild: cleartext already allowed, server URL is a setting)
  - PASS fresh install: splash + onboarding, permissions granted; PASS Test connection (user-reported)
  - PASS upload: DEMO_EDITED.mp4 112 MB and maanit-presentation.mov 155 MB COMPLETED, server-verified
  - PASS kill mid-upload: `am crash` (pid 7086 → 12766, exit reason APP CRASH) → resumed on its own, 1 deduped chunk, Restored after restart, Verified
  - PASS instant re-upload: server `instantUploads: 2` (presentation.mov, Backgroud_1.mp4), "Already on server" shown
  - PASS pause/resume (user-reported only, not observable from the host)
  - PARTIAL download: only sample-0B.bin (0 B) downloaded on the device; the 200 MB download was not run on the phone (verified on the emulator in Phase 3/6.4c). Known limitation for the README.
- [x] D Screenshots (minimal) (2026-10-04: release 1.1.0 on the OnePlus over LAN, server throttled to 3000 kbps; 05-transfers-active, 07-detail-transferring, 11-history, 12-settings added, 99–154 KB each at 487×1080; older files kept)
- [ ] D2 Demo video
- [ ] E1 README sections 1–6
- [ ] E2 README sections 7–12 + final checks
- [ ] F1 Push and release, items 1–3
- [ ] F2 Push and release, items 4–5

## Open questions

(Format: `step id: question`. I answer here or in chat.)

- 6.3c: Default for "Pieces at once per transfer"? Measured: fast link ≤ 1.26× at N = 4, Slow network 3.3–3.7× (inflated by the per-request throttle). Recommendation: keep 1.
- 6.3c: Placement guessed (plan silent): the control sits directly below "Transfers at the same time" (UI-SPEC §5.10). OK?
- 6.3c: Slow-network uploads time out: at 512 kbps a 2 MiB piece needs ~33 s, but OkHttp's 30 s read timeout (`ProtocolClient.kt:265`) starts once the body is in socket buffers. Pieces retry and still verify, but N = 1 upload takes 498 s vs 337 s download. Raise the read timeout for chunk PUTs, or scale it with piece size? Not changed (engine change outside 6.3c).
- 6.3c: Server bug? `faults.js` throttles whenever `bandwidthKbps > 0`, even with `enabled: false` (latency and the rates do respect `enabled`). The UI's Off preset zeroes everything, so only API users hit it. Fix the server to gate on `enabled`?
- 6.3c: `ParallelChunksTest.networkLossWithSeveralWorkersMakesExactlyOneRetryingTransition` failed once in an isolated class run (9 passes after, message not captured). Pre-existing 6.3b test, not touched by this step; worth a look if it shows up in CI. Likely cause found in 6.4a: `claimNextQueued(exclude = jobs.keys)` read a live key view that a finishing job could empty mid-copy (NoSuchElementException, seen once in UploadPipelineTest); fixed in 242f74e.

- 6.4b: UIDT jobs must have a network constraint (JobInfo docs, API 34). When it is lost the system stops the job (`onStopJob`, reschedules), which today hits `requeueAfterStop` (QUEUED, "Interrupted by a system stop") instead of RETRYING NETWORK_UNAVAILABLE. Plan: constraint `NETWORK_TYPE_ANY` even with Wi-Fi only (NetworkGuard keeps deciding metered), and map STOP_REASON_CONSTRAINT_CONNECTIVITY to the waiting path. ANSWER (user, 2026-10-04): yes.
- 6.4b: Task Manager "Stop" kills the process with no `onStopJob` and the app cannot reschedule that job; the docs recommend a stop/pause action in the job notification. UI-SPEC has none. Add a "Pause all" notification action (copy?), or leave it and rely on the WorkManager backstop + reconciliation? ANSWER (user, 2026-10-04): yes, add it; button text "Pause transfers" (goes in strings.xml + UI-SPEC in the same commit).
- 6.4b: A UIDT host that finds the WorkManager loop holding the lease returns at once, so those transfers keep running under the dataSync FGS. Acceptable, or should the job host wait for the lease? ANSWER (user, 2026-10-04): fine as built.

- 6.4c: Task Manager "Stop" on API 37 = `fully stop … by user request` (ApplicationExitInfo REASON_USER_REQUESTED, subreason 23 STOP APP). Process killed, job dropped ("because of user stop"), no `onStopJob`, package NOT in stopped state. The row stays TRANSFERRING, nothing runs for ≥ 60 s, and the next app open reconciles it → QUEUED → resumes on its own. So the plan's "becomes PAUSED and stays paused" fails. Proposal: at process start read `ActivityManager.getHistoricalProcessExitReasons` (API 30+); if the last exit was REASON_USER_REQUESTED, reconciliation moves TRANSFERRING → PAUSED (legal edge) and VERIFYING → QUEUED (no PAUSED edge, as with a job USER stop), plus an INFO event. That also covers Settings → Force stop. Changes the reconciliation rule (DESIGN §9 + CLAUDE.md). Build it, or accept "resumes on next open" and document it? ANSWER (user, 2026-10-04): build it (REASON_USER_REQUESTED → TRANSFERRING → PAUSED, VERIFYING → QUEUED); update DESIGN §9 + CLAUDE.md.
- B: Version? ANSWER (user, 2026-10-04): bump to 1.1.0, versionCode 2.
- 6.4c: No API ≤ 33 system image is installed (only android-37.1). Download `system-images;android-33;google_apis;arm64-v8a` (~1.5 GB) via sdkmanager and create an AVD for part 2, or skip part 2? ANSWER (user, 2026-10-04): download it and verify.

## Gotchas (short, durable facts that save re-discovery)

- UIDT (6.4b): `TransferScheduler.ensureRunning(userInitiated)` + extension `ensureRunning()` = background start. `AppContainer.scheduler` is now `HostSelectingScheduler` (WorkManagerScheduler is private and does the wake-ups). Stop kinds live in the engine (`StopKind`, `TransferEngine.run(stopReason, stopKind)`); `TransferJobHost` maps JobParameters stop reasons and is the test seam. Deviation forced by the state machine: a user stop moves VERIFYING → QUEUED (no PAUSED edge). Job id 1 000 001; WorkManager's JobScheduler id range is now 0–999 999 (lint SpecifyJobSchedulerIdRange). Notification ids: 1001 WorkManager FGS, 1002 UIDT job. To verify in 6.4c: JobScheduler's NETWORK_TYPE_ANY may require a validated network, unlike our ConnectivityChecker (LAN-only server). `lintDebug` already had 3 errors before 6.4b (TransferCoordinatorWorker getStopReason, TransferRow LocalContext); not in CI. Baseline: 343 Android JVM tests.
- Real device (C): `am kill` does nothing while the transfer FGS runs and a release build has no `run-as`; `adb shell am crash com.maanit.stableshare` kills it with exit reason APP CRASH (not a user stop), so it is restored, not paused.
- User stop (6.4c): `PreviousProcessExit` (engine/) reads ApplicationExitInfo once per process; `TransferEngine(previousExitByUser)` applies it on the process's first run only; `reconcileAfterProcessStart(stoppedByUser)`. A paused-on-restart row logs only STATE_CHANGE "app stopped by the user" (no INFO, not Restored). `adb shell am force-stop` also records REASON_USER_REQUESTED, so it now pauses too; chaos/kill tests must use `kill -9` (root shell: `adb root` works on google_apis images, `run-as … kill` failed on API 33). README.md:373 ("Force stop … resume on the next launch") is now wrong: fix in Phase 9 (now PAUSED). 7.1's fallback `am kill` was not checked for its exit reason; prefer `adb root` + `kill -9`. AVD `Pixel_API33` (google_apis arm64, API 33) exists. Baseline: 350 Android JVM tests.
- UIDT verified on the emulator (6.4c): dumpsys shows `JOB #u0aNNN/1000001 … TransferJobService`, `Flags: 20`, `Priority: 500 [MAX]`, `userInitiatedApproved: true (started as UIJ: true)`. JobScheduler turns NETWORK_TYPE_ANY into INTERNET&VALIDATED, so a LAN-only (unvalidated) network will not start the job; the emulator's Wi-Fi validates. Throttle the server for manual stop tests (`PUT /admin/faults {"enabled":true,"bandwidthKbps":4000}`, then reset) or a 200 MB upload finishes in about 40 s. Task Manager = expand quick settings (`cmd statusbar expand-settings`), then tap "1 app is active".

- The plan arrived in `StableShare-masterplan/`; moved to the repo root (MASTER-PLAN.md, PROGRESS.md, plan/, .claude/commands/next.md). Work is on branch `phase-5-safety-net`, cut from `feature-1-wifi-only`; local `main` is 8 commits behind it.
- Wi-Fi only already shipped before this plan (CLAUDE.md "Feature 1", commits d7f485c, 90dd329, 2eda46b). At 6.1a/6.1b, diff the plan against the existing code before building anything. Code vs plan 6a: DataStore key is `wifi_only` (not `wifiOnly`); the proactive stop is per request (NetworkGuard/GuardedTransferApi), not a job-level watcher; promotion lives in EngineBootstrap (`promoteWaitingForNetwork`) and the coordinator.
- JDK: Gradle daemon toolchain is pinned to 25 (`android/gradle/gradle-daemon-jvm.properties`); CI uses temurin 25. Node LTS used in CI: 24.
- Job-level `env:` cannot use the `runner` context (GitHub rejects the file and logs a 0 s failed run on every push); put `${{ runner.* }}` in step env. Lint with actionlint (download script → scratchpad).
- Action majors (checked 2026-10-04): checkout@v7, setup-node@v7, setup-java@v6, gradle/actions/setup-gradle@v6, upload-artifact@v7.
- Clean clone builds without local.properties when ANDROID_HOME is set (runners set it); debug build needs no keystore. Baseline: 32 server tests, 286 Android JVM tests (after 6.1b).
- Fuzz: `./gradlew :app:testDebugUnitTest --tests '*fuzz*' -Pfuzz.seeds=2000` (repo ≈ 66 s, engine ≈ 51 s; default 200 ≈ 12 s). Replay: `-Pfuzz.seed=S`. Regression seeds in `app/src/test/resources/fuzz-regressions.txt` (`<layer> <seed>`). Changing the scenario generator reshuffles seeds: re-find a reproducing seed for each regression entry (revert the fix, search with a large `fuzz.seeds`).
- Engine fuzz is deterministic: Room uses `setQueryCoroutineContext(testDispatcher)` and FileStore gets the same dispatcher (EngineHarness `io`). Process death = the old process's clock throws (every repository write needs it), then its scope is cancelled. I11 counts pipelines and TRANSFERRING+VERIFYING rows; RETRYING rows with a persisted backoff from a dead process hold no slot by design, so they are not counted.
- Instant upload (server): linked file is `completed/<id>.bin` (code's name; plan said `<id>-<fileName>`). Every create body has `instant`; COMPLETED ones also `sha256`, and a repeated create of an instant session still says `instant: true`. 200 = instant, 201 = new. Android's Json has `ignoreUnknownKeys = true`, so old clients are unaffected. Baseline: 43 server tests.
- Instant upload (client): `CreateSessionResponse.instant`/`sha256` default to absent; EventType is stored by name, so INSTANT_UPLOAD needed no migration. Pill flag = `TransferRepository.observeInstantUploadIds()` (lists) or the detail's own events. `FakeTransferServer.instantUploads` (default on) and `reportedSha` drive tests; `EngineHarness.upload(fileName=)` makes an identical copy without rewriting (and re-mtiming) a file another upload is reading. Fuzz re-uploads use a separate `reuseRnd`, so older seeds kept their scenarios (seed 1346 re-checked: still fails with the fix reverted). Baseline: 301 Android JVM tests.
- Manual instant-upload checks: the Upload screen's test-file chips write fresh random bytes each time (`FileStore.generateTestFile` seeds with nanoTime), so for an identical re-upload `adb push` one file to /sdcard/Download and pick it twice through the system picker. The detail's "Data sent" row lives in the collapsed "Details" card at the bottom.
- Bug found by fuzzing (engine seed 1346): RETRY_SCHEDULED logged after a concurrent cancel → fixed with `TransferRepository.logEventWhile`.
- Parallel chunks (engine): setting `parallelChunks`, key `parallel_chunks`, values 1/2/4. N = 1 keeps the old sequential path byte-for-byte (approved); N > 1 = `forEachChunkInParallel` + `RetryRunner.runChunkInPlace` (backoff stays TRANSFERRING, terminal outcomes behind a per-run mutex). Download writes keep per-chunk open+write+fsync instead of one shared FileChannel (approved). Tracker: `inFlightChunks` map, `inFlightChunk` = lowest index (UI unchanged until 6.3c). Test seams: `FakeTransferServer.peakChunkRequests`, `BufferMeter`; `FileStore.readChunk` is now `open`. Fuzz N uses its own Random. Baseline: 319 Android JVM tests.
- Parallel chunks (UI, 6.3c): Settings tag `parallelChunks`; tracker `backoffChunks` (index → failed attempts, set by `chunkBackingOff` from `runChunkInPlace`'s onBackoff, cleared when the piece moves/commits or the phase leaves Transferring); `TransferItem.inFlightChunks`/`chunkBackoff`. Robolectric `captureToImage` times out; PiecesCardTest draws the decor view into a software bitmap under `@GraphicsMode(NATIVE)`. Robolectric tests that change maxConcurrent/wifiOnly need `WorkManagerTestInitHelper` (else a background exception fails a later test). Baseline: 325 Android JVM tests.
- Benchmark (6.3c) headline: fast link, warm: upload N=1 40.7 s, N=2 41.0 s, N=4 34.6 s; download 26.4 / 23.8 / 21.0 s (200 MiB). Slow network (20 MiB): upload 498 / 236 / 136 s, download 337 / 170 / 102 s. The first case of a session runs cold (103 → 35 s), so warm up first. Run: `android/scripts/benchmark-parallel.sh` (~1.5 h); `bench-20MB` is seeded into server storage by the script.
- Server parallel chunks: `KeyedLock` (per uploadId) already guarded meta.json; `bandwidthKbps` is per request (each body throttled from its own start), so N parallel chunks get N × the limit; the 6.3c benchmark notes must say so. Baseline: 47 server tests.
- Coordinator lease (6.4a): `RunLease` in `engine/`, owned by `AppContainer.runLease`; `TransferEngine.run()` now returns Boolean (false = handed off). `EngineHarness.lease` / `onWakeup` (fires in the loop's exit window) are the test seams. UIDT facts verified 2026-10-04: network constraint mandatory, allowed constraints have no delay (wake-ups stay WorkManager), background schedule → RESULT_FAILURE (fall back to WorkManager), `setEstimatedNetworkBytes` recommended. Baseline: 329 Android JVM tests.
