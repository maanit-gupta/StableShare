# StableShare — Master Plan (Phases 5–10)

Phases 1–4 are complete: mock server, Android foundation, transfer engine, UI, and a release build. This plan covers everything after: a safety net, three features plus instant upload, evidence, the final build, the README, and the showcase.

This file is short on purpose. It is read at the start of every session. The detail for each step lives in its own file under `plan/`; read only the section for the step you are doing.

## How a session works

1. Read this file, then `PROGRESS.md`.
2. Do exactly ONE step: the one named under "Resume here" in PROGRESS.md, or the first unchecked step.
3. Read only that step's section in its plan file (find it with `grep -n '^## ' plan/<file>`).
4. Do the work, following the rules below.
5. At the end: update PROGRESS.md, commit, print the report, print the NEXT line, and stop.

Never start a second step, even if there seems to be room left. Small sessions are the point.

## Context-saving rules

1. Read the minimum. Beyond this file, PROGRESS.md and your step section, read only the source files the step touches. Locate things with `grep -rn`, `rg`, and `sed -n 'A,Bp'` ranges. Never read DESIGN.md, UI-SPEC.md, README.md or a large source file in full: list headings with `grep -n '^#'` and read just the section you need.
2. Tame output. Run tests narrowly while developing (for example `--tests '*Wifi*'`, or `node --test <file>`) and run the full suite once, at the end of the step. Send long output to a file (`> /tmp/out.txt 2>&1`) and read only the failing part with `grep`, `head` or `tail`. Never print more than about 80 lines of a log.
3. Commit as you go with conventional prefixes (feat:, fix:, test:, docs:, chore:). Never stop with a broken or uncommitted tree: if you must stop mid-step, make a `wip(<step id>):` commit that builds, and write the exact next action under "Resume here".
4. Wrap up early. If you have made roughly 40 tool calls, or I tell you the context is above about 60%, finish the smallest safe unit, commit, update PROGRESS.md and stop.
5. If stuck on the same failure after 3 different attempts, stop. Record what you tried under "Open questions" and tell me.
6. If a plan file or UI-SPEC.md is silent on something you need (a size, a string, a behaviour), STOP and ask. Do not guess. Record the question under "Open questions", mark the step `[?]`, and tell me.
7. Names in the plan files (TransferRepository, ConnectivityMonitor, and so on) come from CLAUDE.md. If the code differs, follow the code and note the difference under "Gotchas".
8. Never weaken or delete a test to make it pass. Never print or commit secrets (`keystore.properties`, `*.jks`).
9. Plan files are read-only for you. You edit only PROGRESS.md, the code, tests and the docs a step names.
10. Any UI text added in Phase 6 goes into docs/UI-SPEC.md in the same commit as the code, and strings go in strings.xml. Light theme only; no copy beyond what a step lists.

## Step flags

- `[APPROVE]`: show me a short plan and wait for my go-ahead before changing code.
- `[USER]`: I do this step myself. Do not attempt it. If you are asked to resume and the next step is a USER step, tell me exactly what to do and stop.
- `[ASK]`: this step needs a decision from me before or during the work.

## Session report (print at the end, 10 lines at most)

```
STEP: <id and title>
RESULT: done | partial | blocked
COMMITS: <short hashes and subjects>
TESTS: <what you ran and the result>
FILES: <new or notable files>
ISSUES: <anything I must know or decide>
NEXT: <step id> — run /clear, then /next
```

## Phase map

| Phase | Plan file | Steps |
|---|---|---|
| 5 Safety net | `plan/5-safety-net.md` | 5.1 CI, 5.1u confirm green, 5.2 model-based and fuzz tests |
| 6 Features | `plan/6a-wifi-only.md` | 6.1a engine, 6.1b UI and docs |
| | `plan/6b-instant-upload.md` | 6.2a server, 6.2b client and UI |
| | `plan/6c-parallel-chunks.md` | 6.3a server safety, 6.3b engine `[APPROVE]`, 6.3c UI and benchmark |
| | `plan/6d-uidt.md` | 6.4a plan and extract the run loop `[APPROVE]`, 6.4b job host and scheduling, 6.4c verify and docs |
| 7 Evidence | `plan/7-evidence.md` | 7.1 resilience report |
| 8 Final build | `plan/8-final-build.md` | 8.1 release build and smoke test `[ASK]`, 8.2 screenshots |
| 9 README | `plan/9-readme.md` | 9.1 to 9.4 write it in four parts, 9.5 accuracy audit |
| 10 Showcase | `plan/10-showcase.md` | 10.1 demo script, 10.2u record, 10.3 embed and changelog, 10.4u design notes, 10.5u GitHub release, 10.6 submission audit |

Why this order: CI and the model-based tests come first so the risky engine changes (parallel chunks, the UIDT host) land on top of a regression net. Features that touch the server come before evidence so the report measures the final system. The README comes after the final build so it describes what shipped.

## Deliverables (from the assignment)

Source code, a working APK, and a README containing: architecture, transfer protocol, persistence strategy, retry/recovery logic, and important edge cases handled. Phase 9 owns the README; its section headings must match those five phrases so a grader finds them instantly.

## File index

- `MASTER-PLAN.md` (this file) and `PROGRESS.md` (the checklist, updated every session) live at the repo root.
- `plan/*.md`: one file per phase group, read one section at a time.
- `.claude/commands/next.md`: the `/next` command that runs one session.
- Standing project rules stay in the root `CLAUDE.md`, `server/CLAUDE.md` and `android/CLAUDE.md`; design in `docs/DESIGN.md`; UI in `docs/UI-SPEC.md`.
