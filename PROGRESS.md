# StableShare — Progress

Updated by Claude Code at the end of every session. Keep this file under 120 lines: when "Gotchas" grows past 15 lines, merge related items.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[?]` blocked on a question · `[USER]` my step

## Resume here

Next step: **5.1u [USER] push and confirm the CI run is green**
Exact next action (user): CI triggers only on push/PR to `main`. Push branch `phase-5-safety-net` and open a PR into `main` (or fast-forward `main` to it and push), then watch workflow "CI" in the Actions tab. Red → paste the last ~40 log lines with "5.1 failed in CI:" and run `/next`. Green → tick 5.1u.

## Checklist

Phases 1–4 (server, Android foundation, engine, UI, release 1.0.0): `[x]` done before this plan.

### Phase 5 — Safety net
- [x] 5.1 GitHub Actions CI (`plan/5-safety-net.md`) — workflows "CI" (`ci.yml`: jobs `server`, `android`) and "Chaos test" (`chaos.yml`, workflow_dispatch only)
- [ ] 5.1u [USER] push and confirm the CI run is green
- [ ] 5.2 Model-based and fuzz tests

### Phase 6 — Features
- [ ] 6.1a Wi-Fi only: engine and tests (`plan/6a-wifi-only.md`)
- [ ] 6.1b Wi-Fi only: UI, copy and docs
- [ ] 6.2a Instant upload: server (`plan/6b-instant-upload.md`)
- [ ] 6.2b Instant upload: client and UI
- [ ] 6.3a Parallel chunks: server safety (`plan/6c-parallel-chunks.md`)
- [ ] 6.3b Parallel chunks: engine [APPROVE]
- [ ] 6.3c Parallel chunks: UI and benchmark
- [ ] 6.4a UIDT: read docs, plan, extract TransferRunLoop [APPROVE] (`plan/6d-uidt.md`)
- [ ] 6.4b UIDT: TransferJobService, scheduling, stop mapping
- [ ] 6.4c UIDT: emulator verification and docs

### Phase 7 — Evidence
- [ ] 7.1 Resilience report (`plan/7-evidence.md`)

### Phase 8 — Final build
- [ ] 8.1 Release build and smoke test [ASK] (`plan/8-final-build.md`)
- [ ] 8.2 Screenshots

### Phase 9 — README
- [ ] 9.1 Sections 1–4: summary, requirements map, quick start, Architecture (`plan/9-readme.md`)
- [ ] 9.2 Transfer protocol and Persistence strategy
- [ ] 9.3 Retry and recovery logic, integrity, lifecycle, concurrency
- [ ] 9.4 Important edge cases handled, UI, testing, limitations, structure
- [ ] 9.5 Accuracy audit

### Phase 10 — Showcase
- [ ] 10.1 Demo script (`plan/10-showcase.md`)
- [ ] 10.2u [USER] record the demo
- [ ] 10.3 Embed the demo and write the changelog
- [ ] 10.4u [USER] write the design notes in your own words
- [ ] 10.5u [USER] GitHub release
- [ ] 10.6 Submission audit

## Open questions

(none yet. Format: `step id: question`. I answer here or in chat.)

## Gotchas (short, durable facts that save re-discovery)

- The plan arrived in `StableShare-masterplan/`; moved to the repo root (MASTER-PLAN.md, PROGRESS.md, plan/, .claude/commands/next.md). Work is on branch `phase-5-safety-net`, cut from `feature-1-wifi-only`; local `main` is 8 commits behind it.
- Wi-Fi only already shipped before this plan (CLAUDE.md "Feature 1", commits d7f485c, 90dd329, 2eda46b). At 6.1a/6.1b, diff the plan against the existing code before building anything.
- JDK: Gradle daemon toolchain is pinned to 25 (`android/gradle/gradle-daemon-jvm.properties`); CI uses temurin 25. Node LTS used in CI: 24.
- Action majors (checked 2026-10-04): checkout@v7, setup-node@v7, setup-java@v6, gradle/actions/setup-gradle@v6, upload-artifact@v7.
- Clean clone builds without local.properties when ANDROID_HOME is set (runners set it); debug build needs no keystore. Baseline: 32 server tests, 282 Android JVM tests.
