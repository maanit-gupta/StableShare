# Phase 5 — Safety net

Purpose: protect the engine before the risky refactors in Phase 6. Steps: 5.1, 5.1u, 5.2.

## 5.1 GitHub Actions CI

GOAL: every push and pull request runs the server tests and the Android unit tests and builds a debug APK.

DO
1. Create `.github/workflows/ci.yml` with two jobs on ubuntu-latest, triggered on push and pull_request for the repository's default branch (check whether it is main or master).
   - `server`: setup-node with the current LTS, npm cache keyed on `server/package-lock.json`, then `npm ci` and `npm test` in `server/`.
   - `android`: setup-java with the JDK version the project's Gradle and Android Gradle Plugin configuration requires (read it from the files in `android/`, do not guess), Gradle caching through gradle/actions/setup-gradle, then `./gradlew testDebugUnitTest assembleDebug` in `android/`. Upload `app/build/outputs/apk/debug/*.apk` as an artifact named `stableshare-debug-apk` with 14-day retention.
2. Use the current stable major version of each action. Check each action's README for it; do not rely on memory.
3. No secrets anywhere. `keystore.properties` does not exist in CI, so never run `assembleRelease` there, and make sure the debug build does not need it.
4. Add `.github/workflows/chaos.yml`, manual only (`workflow_dispatch`): seed the files and run `server/scripts/chaos-test.sh`. Never on push, because it moves 200 MB.
5. Prove it works from a clean checkout. Commit first, then `git clone . /tmp/stableshare-clean`, run the exact commands from the workflow there, and fix anything that relied on local-only files (executable bit on `gradlew`, `local.properties`, untracked fonts or assets, generated files).
6. Do not add the README badge now; step 9.1 does it.

DONE WHEN: the clean-clone run passes; both workflow files are committed; PROGRESS.md records the workflow names. Next is 5.1u, which is mine.

## 5.1u [USER] Push and confirm CI is green

This is your step. Do not run it as Claude Code.

1. Push to GitHub. Open the repository's Actions tab and wait for the CI run.
2. If it is red, open the failing step and copy the last ~40 lines of its log. Run `/next` after pasting them into the chat with "5.1 failed in CI:", and Claude Code will fix and re-run step 5.1.
3. When green, tick 5.1u in PROGRESS.md (or tell Claude Code "5.1u done").

If you are Claude Code and the next step is 5.1u: tell the user these instructions and stop.

## 5.2 Model-based and fuzz tests

GOAL: random but reproducible tests that hunt for state-machine and recovery bugs. Any bug found is fixed with a regression test. Never weaken an invariant to make a run pass.

READ: `StateMachine`, `TransferRepository`, `TransferEngine` and the existing engine tests' fakes (find them with grep). Read docs/DESIGN.md section 3 (protocol) and section 4 (state machine) by heading only.

LAYER A — repository model test
- Location: `android/app/src/test/.../fuzz/RepositoryModelTest.kt`. In-memory Room, a seeded `Random`, and a simple in-memory reference model of what each transfer's state and chunks should be.
- Random operations: createUpload, createDownload, claimNextQueued(k), transition(id, random target state), markChunkDone(id, random index), applyServerReceivedChunks, resetChunks, reconcileAfterProcessStart, deleteTransfer.
- Invariants checked after every operation:
  I1. State changes only along the legal transitions in the CLAUDE.md table; an illegal transition returns false and changes nothing.
  I2. COMPLETED and CANCELLED never change afterwards.
  I3. `bytesDone` equals the sum of the lengths of DONE chunks, always.
  I4. `markChunkDone` returns true if and only if the transfer is TRANSFERRING.
  I5. `claimNextQueued(k)` returns at most k rows, only from QUEUED (or RETRYING with `nextRetryAt` passed), and moves them to TRANSFERRING.
  I6. After `reconcileAfterProcessStart` no TRANSFERRING or VERIFYING row remains, and no other row changed.
  I7. Chunk rows never exist without their transfer.

LAYER B — engine scenario fuzz
- Location: `.../fuzz/EngineFuzzTest.kt`. The real engine, repository and pipelines, with virtual time, a temp-dir FileStore and a fake ConnectivityMonitor.
- Server: an in-memory `FakeServer` implementing the server's rules from DESIGN.md section 3 (idempotent create, per-chunk hash check, `already_received`, `MISSING_CHUNKS`, full-file hash check on complete, Range with ETag for downloads) plus seeded fault injection: errors, timeouts, response dropped after processing, drop mid-body, corrupted download bytes. No shortcut that always succeeds. If an existing fake is too thin, extend it.
- Random scenario per seed: 1–5 transfers (mixed uploads and downloads, sizes including 0 bytes and non-multiples of the chunk size), `maxConcurrent` 1–3, then a random schedule of user actions (pause, resume, cancel, manual retry), connectivity flips, and process death (cancel the engine scope, build a fresh container on the same database and files, reconcile, restart).
- Run until quiescent (everything terminal, paused, or waiting), then resume all paused and failed transfers once and run to quiescence again.
- Invariants:
  I8. A COMPLETED transfer has a VERIFIED event and the delivered bytes' SHA-256 equals the source's (the FakeServer's stored file for uploads, the final local file for downloads).
  I9. A CANCELLED transfer has no events after its cancel event, and its cleanup ran.
  I10. The FakeServer never stores a chunk whose hash differs from its declared hash.
  I11. The number of simultaneously TRANSFERRING/VERIFYING/RETRYING transfers never exceeds `maxConcurrent`, sampled at every virtual tick.
  I12. No transfer ever moves out of CANCELLED or COMPLETED (check the event log).
  I13. No chunk is marked DONE while its transfer is not TRANSFERRING.

SEEDS AND REPRODUCTION
- Default 200 seeds for each layer, tuned so the whole fuzz suite runs in about 2 minutes or less. Configure through a Gradle property: `-Pfuzz.seeds=N` (default 200) and `-Pfuzz.seed=S` to replay one seed. Wire the property into the unit-test task with `systemProperty`.
- On failure, print the seed and the exact replay command.
- Keep `android/app/src/test/resources/fuzz-regressions.txt`: seeds that once failed, always run. Add a seed when you fix a bug found by fuzzing.
- Run once locally with `-Pfuzz.seeds=2000` and report the result.

DONE WHEN: both layers exist and pass at the default and at 2000 seeds; the unit-test task includes them (so CI runs them); any bugs found are fixed with regression seeds; PROGRESS.md "Gotchas" lists bugs found (one line each) and the replay command. Update the root CLAUDE.md "Workflow" section with one line on how to run the fuzz tests.
