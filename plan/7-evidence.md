# Phase 7 — Evidence

## 7.1 Resilience report

GOAL: publish numbers that show the system works under faults, and be honest about what they do and do not prove. Output: `docs/RESILIENCE.md` plus raw data in `docs/resilience/results.json`.

READ: `server/scripts/cli-client.js` and `chaos-test.sh` (head only), `/admin/faults` and `/admin/stats` handlers, `docs/benchmarks.md` (headline only), PROGRESS.md "Gotchas" for fuzz findings.

PART A — protocol-level runs with the reference CLI client
- Write `server/scripts/resilience-report.js`. For each preset (Flaky Wi-Fi, Lost responses, Corruption, Chaos, and Slow network) apply the preset's values from UI-SPEC section 5.10 through `PUT /admin/faults` with a fixed seed per run, then run one upload and one download round trip through the CLI client and verify both SHA-256 hashes.
- Choose the file size and run count so the whole report takes about 40 minutes at most, and record the numbers you chose. Suggested start: a 50 MB file and 30 runs for the fault presets; fewer runs of a smaller file for the bandwidth-limited Slow network preset.
- Per preset, record: runs, runs whose hashes matched, mean, median and p95 wall time, total retries, lost responses recovered (a chunk confirmed after a lost reply), duplicate chunks the server absorbed (from `/admin/stats`), and corrupted chunks detected.
- Produce a Markdown table and the raw JSON. Include the exact command to reproduce and the seeds.

PART B — Android engine fuzz summary
- From step 5.2: seeds run in total, the invariants checked (list them), bugs found and fixed (one line each, from PROGRESS.md Gotchas), and the replay command.

PART C — features
- Link `docs/benchmarks.md` and quote its headline numbers.
- Instant upload timing: first upload of a 200 MB file versus the identical second upload (measure both once on the emulator or device and state which).

PART D — on-device kill-and-recover runs
- Three runs of a 200 MB upload plus a 200 MB download under the Chaos preset with one `kill -9` of the app process mid-transfer each (run `adb root` once, then `adb shell kill -9 $(adb shell pidof com.maanit.stableshare)`; if `adb root` is unavailable on the emulator image, background the app and use `adb shell am kill com.maanit.stableshare`). Confirm each ends COMPLETED with hashes matching `shasum -a 256` of the server files.
- If you can automate this with an adb script (`scripts/device-smoke.sh`), do. If not, do it by hand and label the section "manual runs". Never present manual runs as automated.

HONEST FRAMING (required section "What this does and does not show")
Part A exercises the protocol and the reference client, not the Android app. Part B exercises the app's engine against a faithful in-memory server, not a real radio. Part D is a small number of real runs. Together they show the recovery logic and protocol hold under injected faults; they do not prove behaviour on arbitrary real networks.

DONE WHEN: `docs/RESILIENCE.md` and the JSON exist, the script is committed with a one-line usage note in `server/CLAUDE.md`, and PROGRESS.md records the headline numbers under Gotchas.
