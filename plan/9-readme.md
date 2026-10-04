# Phase 9 — README

The README is a graded deliverable. It must contain, with these exact section headings so a grader finds them: **Architecture**, **Transfer protocol**, **Persistence strategy**, **Retry and recovery logic**, **Important edge cases handled**. It must be accurate to the code, skimmable, and about 500 lines or fewer; push depth into docs/DESIGN.md and link to it.

READ RULES (apply to 9.1–9.5): write each section from docs/DESIGN.md (by heading) and from the code it points at; never re-read the whole README after writing; use `grep -n '^#' README.md` to see its outline. Use Mermaid for diagrams and check each is valid syntax. Every claim must be something you can point to in the code or a test; if you cannot, leave it out. Link implementing files with relative paths.

## 9.1 Sections 1–4 (summary, requirements map, quick start, Architecture)

Create or replace `README.md` with the full heading skeleton (all sections below as empty headings in order), then write these sections:
1. Title "StableShare" and a one-paragraph summary. A row of 3–4 screenshots from `docs/screenshots/`. A CI status badge using the workflow in `.github/workflows/ci.yml`, and a link to the APK in `release/`. Add a "Demo" heading directly under the summary and leave it empty; step 10.3 fills it. No placeholder text.
2. **Requirements covered**: a table of the 12 assignment requirements (upload and download, transfer queue, transfer states, pause and resume, persistent progress, partial transfers, failure and retry, duplicate and lost requests, data integrity, concurrency, lifecycle, cancellation) with columns: requirement, where implemented (file link), how it is tested (test name or script), how to see it in the app. Below it, a second table for the optional enhancements and extras (speed and ETA, configurable concurrency, history and logs, network simulator, corrupted-partial recovery, automated tests, Wi-Fi only, instant upload, parallel chunks, user-initiated transfer job, CI, model-based tests).
3. **Quick start**: prerequisites; run the server (`npm install`, `npm run seed`, `npm run dev`); install the APK or build from source; emulator address `10.0.2.2:8080` versus a phone using the computer's LAN address; how to use the Network simulator.
4. **Architecture**: a Mermaid diagram (UI, ViewModels, repository, Room, coordinator and run loop, hosts, pipelines, ProtocolClient, server and its storage); a short description of each layer; and why each technology was chosen (WorkManager plus the UIDT host, Room, OkHttp, one coordinator, manual dependency injection, Compose).

DONE WHEN: sections 1–4 written, the skeleton in place, PROGRESS.md updated.

## 9.2 Transfer protocol and Persistence strategy

5. **Transfer protocol**: an endpoint table (method, path, purpose, key headers, status codes) for uploads, downloads and admin; Mermaid sequence diagrams for the upload flow, the download flow, the lost-response flow (chunk processed, reply lost, status check, no duplicate), and the instant-upload flow; the error-body shape and the main status codes.
6. **Persistence strategy**: the three tables (transfers, chunks, transfer_events) with their key columns; transactional state changes through `transition()`; the write ordering rule (bytes, fsync, then database) and why; the server as source of truth for uploads and the manifest plus on-disk hashes for downloads; restart reconciliation; what is deliberately kept in memory only.

DONE WHEN: both sections written and every endpoint in the table exists in the server code; PROGRESS.md updated.

## 9.3 Retry and recovery logic, integrity, lifecycle, concurrency

7. **Retry and recovery logic** (use exactly this heading): the error classification table (retryable, waiting, fatal) with codes; the backoff formula with its numbers; attempt limits and what happens when they run out; network waiting including Wi-Fi only; the argument that no path loops forever; per-chunk retry versus transfer-level retry with parallel chunks; restart reconciliation; recovery from corrupted partial data (only bad chunks are re-fetched).
8. **Data integrity**: per-chunk and full-file SHA-256, atomic renames, ETag with If-Range, why COMPLETED is only set after verification, and what verification does and does not prove.
9. **Lifecycle**: foreground and background, swipe-away, process death, reboot, system stop reasons, the WorkManager coordinator and the UIDT host with the single-runner lease, how `STOP_REASON_USER` is handled, and the force-stop limitation.
10. **Concurrency model**: limited concurrent transfers with the configurable limit, parallel chunks within one transfer and the trade-off, the APPEND_OR_REPLACE race and why it matters.

DONE WHEN: sections written and checked against the code; PROGRESS.md updated.

## 9.4 Important edge cases handled, UI, testing, limitations, structure

11. **Important edge cases handled** (use exactly this heading): a table with columns scenario, behaviour, where handled, test. Start from the edge-case catalogue in docs/DESIGN.md section 11 and add rows for Wi-Fi only, instant upload, parallel chunks and the UIDT host. Every row's test column must name a real test or script (verify each name with grep).
12. **UI and design**: the two visual styles and where each is used, the mascot moods mapped to transfer states (embed `docs/design/mascot/previews/mascot_sheet.png`), accessibility and reduced-motion support, and a link to `docs/UI-SPEC.md`.
13. **Testing**: how to run server tests, Android unit tests, the fuzz tests (with seeds and replay), instrumented tests, `chaos-test.sh`, the resilience report script, and the benchmark; plus the CI workflows. Link `docs/RESILIENCE.md` and `docs/benchmarks.md` with their headline numbers.
14. **Known limitations and future work**: cleartext HTTP for the local mock server, no authentication, hash-only matching for instant upload, single-range downloads, the mock server's single process, and anything else you found during the build. Remove items that this plan has completed (parallel chunks, user-initiated jobs).
15. **Project structure**: a tree of the repository with one-line descriptions.

DONE WHEN: sections written; edge-case test names verified; PROGRESS.md updated.

## 9.5 Accuracy audit

1. Clone the committed repo into a temp directory (`git clone . /tmp/stableshare-readme-check`) and run every command in Quick start and Testing from there. Fix the README (or the code, if the command should have worked) for anything that fails.
2. Check every relative link and image path resolves.
3. Verify the five required headings exist exactly: Architecture, Transfer protocol, Persistence strategy, Retry and recovery logic, Important edge cases handled.
4. Spot-check ten random claims against the code and report them.
5. Check each Mermaid block for syntax errors (use `npx @mermaid-js/mermaid-cli` only if it installs quickly; otherwise review by eye and say so).
6. Report the README's line count and fix anything over about 500 lines by moving depth into DESIGN.md.

DONE WHEN: audit list reported with corrections made; PROGRESS.md updated.
