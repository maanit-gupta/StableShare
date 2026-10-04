# Phase 6, feature 4 — User-initiated data transfer job (Android 14+)

Steps: 6.4a (plan and extract the run loop, needs approval), 6.4b (job host and scheduling), 6.4c (verify and docs).

GOAL: on Android 14+ (API 34), transfers the user starts while the app is visible run inside a user-initiated data transfer (UIDT) JobScheduler job, the platform mechanism for long user-started transfers. Everything else (older Android, background restarts) keeps using the existing WorkManager coordinator unchanged. This is additive: the existing path must not regress.

FACTS TO RESPECT (from the platform docs, verify in 6.4a)
- A UIDT job is a JobScheduler job built with `setUserInitiated(true)`, introduced in API 34, and needs the `RUN_USER_INITIATED_JOBS` permission.
- It must be scheduled while the app is visible to the user (or in an allowed condition).
- The job must show a notification, set through the JobService's `setNotification`.
- It is not a foreground service type and is not a one-line manifest change.

## 6.4a Read the docs, plan, extract the run loop [APPROVE]

1. Read the current official page "User-initiated data transfers" (developer.android.com, background-work/background-tasks/uidt). Tell me anything in it that contradicts this plan, including any required job constraints.
2. Read `TransferCoordinatorWorker`, `TransferScheduler`, `TransferEngine`, `AppContainer`, the manifest, and `compileSdk` in the Gradle files.
3. Show me a plan of 20 lines or fewer for the design below and WAIT FOR MY GO-AHEAD. If you judge the refactor too risky after reading the code, say so and propose an alternative instead of forcing it.

DESIGN (confirm in the plan)
1. Extract the coordinator loop from `TransferCoordinatorWorker` into a plain class `TransferRunLoop` (claim slots, react to database flows, cancel jobs when rows leave active states, exit when idle). The Worker and a new `TransferJobService` are thin hosts.
2. Single-runner guarantee. The concurrency limit is enforced per loop, so two loops would double it. Add a process-wide lease (single-flight, for example a Mutex or AtomicBoolean owned by the AppContainer). A host that cannot get the lease finishes immediately with success; the running loop already sees new QUEUED rows through the database.
3. After approval, do ONLY the extraction in this session: move the loop into `TransferRunLoop`, add the lease, keep the Worker as the only host, and prove nothing changed.

TESTS: the whole existing engine suite and the Phase 5 fuzz suite pass unchanged against `TransferRunLoop`; a lease test where two hosts start at once, exactly one loop runs, and observed concurrent transfers never exceed `maxConcurrent`.

DONE WHEN: extraction committed, all tests green, DESIGN.md section 9 describes the hosts and the lease, PROGRESS.md updated with the approved design.

## 6.4b Job host and scheduling

READ: `TransferRunLoop`, `TransferScheduler`, the ongoing-notification builder, the manifest.

DO
1. `TransferJobService`: a JobService hosting `TransferRunLoop` in a coroutine scope. Declare it in the manifest with permission `android.permission.BIND_JOB_SERVICE` and `exported="false"`. Add the `RUN_USER_INITIATED_JOBS` permission. `compileSdk` must be 34 or higher; guard every new API with a version check or `@RequiresApi`, and keep minSdk 26.
2. `onStartJob`: call `setNotification(...)` immediately with the existing ongoing-notification builder (same channel, small icon and content), with the end-notification policy set to remove the notification. Completion and failure notifications stay separate, as today.
3. `onStopJob`: stop the loop and treat it as an interruption, never a failure. If the stop reason is `STOP_REASON_USER` (the user stopped it from the system Task Manager or app settings), move active transfers to PAUSED and do NOT restart anything. For any other reason, move active transfers to QUEUED through the repository's `transition()`, then call the existing `ensureRunning()` once as best effort. Document that background foreground-service start restrictions may delay that restart.
4. Scheduling: change to `TransferScheduler.ensureRunning(userInitiated: Boolean)`. User actions (the hoop Upload throw, Resume, manual Retry, adding a download) pass true; app start, connectivity changes, wake-ups and settings changes pass false. When userInitiated is true AND SDK is 34 or higher AND the app process lifecycle is STARTED (ProcessLifecycleOwner) AND the lease is free, schedule one UIDT job with `JobInfo.Builder`: a fixed job id, `setUserInitiated(true)`, and the network constraint the docs require (unmetered when the Wi-Fi only setting is on, otherwise any network), with no other constraints. If scheduling throws or fails, fall back to the existing WorkManager path and write the reason to Logcat (never to a transfer's event log).
5. The WorkManager coordinator path keeps working as is, including how it handles being started from the background. Do not change that logic.
6. The Wi-Fi only gating (`usableNetwork`) must still apply inside the shared loop.

NO NEW UI COPY. The only visible differences are system-owned: the job's notification, and the app appearing in the system Task Manager's list of active apps while the job runs.

TESTS
Unit: host selection (SDK below 34 uses WorkManager; SDK 34+ with userInitiated, visible and lease free uses UIDT; not visible uses WorkManager; lease held starts no new host). Stop mapping: `STOP_REASON_USER` gives PAUSED and no restart; other reasons give QUEUED and one `ensureRunning` call; CANCELLED rows are never touched. Existing suites and the fuzz suite stay green.

DONE WHEN: tests green; PROGRESS.md updated.

## 6.4c Verify on emulators and docs [ASK if an image is missing]

1. On an API 34+ emulator: start a 200 MB upload from the UI, then run `adb shell dumpsys jobscheduler | grep -i com.maanit.stableshare` and show me the (trimmed) output proving the job is user-initiated. Confirm the app appears in the system Task Manager list. Stop it from there and confirm the transfer becomes PAUSED and stays paused; resume it from the app and confirm it completes with a verified hash.
2. On an API 33 (or lower) emulator image, run a basic transfer and a process kill to prove the old path is unchanged. If you cannot create an API 33 image, tell me and stop on that part.
3. Docs: DESIGN.md section 9 (hosts, lease, stop-reason handling) and the root CLAUDE.md Decisions. The README is written in Phase 9.

DONE WHEN: evidence reported, docs updated, PROGRESS.md updated.
