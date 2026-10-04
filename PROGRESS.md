# StableShare — Progress

Updated by Claude Code at the end of every session. Keep this file under 120 lines: when "Gotchas" grows past 15 lines, merge related items.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[?]` blocked on a question · `[USER]` my step

## Resume here

Next step: **5.1 GitHub Actions CI**
Exact next action: start the step from the top of its section in `plan/5-safety-net.md`.

## Checklist

Phases 1–4 (server, Android foundation, engine, UI, release 1.0.0): `[x]` done before this plan.

### Phase 5 — Safety net
- [ ] 5.1 GitHub Actions CI (`plan/5-safety-net.md`)
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

(none yet)
