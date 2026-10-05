# Changelog

## 1.3.0 (versionCode 4), 2026-10-05

APK: `release/StableShare-1.3.0.apk`, 3 963 781 bytes, v2-signed, SHA-256 `4f5d31be439fb8d05724eb4e8831a63e68b84ae590764c2e96f597785afd1687`.

UI update only. The transfer protocol, chunking, the transfers schema, scheduling and retry logic are unchanged.

### Added
- **Server profiles:** Settings → Server has Hosted, Local server (Emulator or Phone on Wi-Fi) and Custom address cards with a health check and an Online, Waking or Unreachable status (0106de0, 82ae9da).
- **First-launch server choice** ("Where should files go?") and a server status pill on Transfers (79306ef).
- **Try a demo:** quick upload (20 MB), big upload (200 MB) or a sample download, with no file to pick. It is on the empty Transfers screen and in Settings → Demo (e9a54b7).
- **Transfers:** tap a row to expand its pieces, retries, resumes and speed, with **View details** (a9616cc).
- **History:** a row menu with **Copy hash** and **Remove from history** (1adf026).

### Changed
- Polish and device pass across the new screens (1598e08).

## 1.2.0 (versionCode 3), 2026-10-04

APK: `release/StableShare-1.2.0.apk`, 3 807 321 bytes, SHA-256 `6569424a726472a76cc954300992a4f6f0382b2a0ab2bb95596a4845af95b0af`. Not published as a GitHub release; the APK is in the repository.

### Added
- **Hosted server** at https://stableshare.onrender.com (Render free web service), built from `server/Dockerfile` and configured by `render.yaml` (e3bee03, 48d5fe7).
- **Env-gated server options for hosting**, all off by default: `SEED_ON_START`, `SEED_MAX_BYTES`, `MAX_UPLOAD_BYTES` (413 `FILE_TOO_LARGE`), `COMPLETED_UPLOAD_TTL_MS`, `FAULT_AUTO_RESET_MS` and `DISABLE_FILE_MUTATE` (fc44da2, 2652ded).

### Changed
- The app defaults to the hosted server, and OkHttp's read timeout is 60 s (was 30 s) to cover cold starts and slow pieces (3869841).

## 1.1.0 (versionCode 2), 2026-10-04

APK: `release/StableShare-1.1.0.apk`, 3 807 237 bytes, v2-signed, SHA-256 `b8e4179498644c2b77f9a4056a347535bb47d3615590e113e8913224f451e4e0`.

### Added
- **Wi-Fi only** setting. No engine traffic goes over metered networks. Running transfers stop within 1.5 s of losing Wi-Fi, wait as "Waiting for Wi-Fi" without using retry attempts, and resume on Wi-Fi (d7f485c, 90dd329).
- **Instant upload.** The server keeps a hash index of completed files, so a file it already has (same SHA-256 and size) completes without sending any pieces and is still verified. The app shows an "Already on server" pill and a "Data sent" row (1172a70, 9d74c4a, 2084ce8).
- **Pieces at once per transfer** setting (1, 2 or 4; default 1). It caps the number of piece requests and buffers in flight. Benchmarks are in `docs/benchmarks.md` (4bfe6ff, c5b6918).
- **User-initiated data transfer job** on Android 14+ for transfers the user starts, with WorkManager as the other host. A process-wide `RunLease` keeps a single coordinator loop. The ongoing notification gains a "Pause transfers" action (93c2c77, ea099a0).
- **Model-based and fuzz tests** for the repository and the engine, and GitHub Actions CI (server tests, Android unit tests, debug APK), plus a manual chaos workflow (1ef0a52, d97cebf).

### Changed
- Stopping the app from Task Manager, or with Force stop, now pauses its interrupted transfers on the next launch instead of resuming them (read from `ApplicationExitInfo`, API 30+) (b2aa451).

### Fixed
- A retry event could be logged after a concurrent cancel. Found by the engine fuzzer (seed 1346) (564085d).
- A latent race when claiming queued transfers while a job finished (242f74e).

### Verified
- 47 server tests and 350 Android JVM tests green. Fuzz suites green at 2000 seeds.
- Real device (OnePlus CPH2717, Android 16, release build over LAN): uploads of 112 MB and 155 MB, resume after a process kill, instant re-upload, pause and resume. Only a 0-byte download was run on the phone. The 200 MB download was verified on emulators.

## 1.0.0 (versionCode 1)

The first complete build: mock server and CLI client, Android data layer, transfer engine, full UI and the README. Not published as a GitHub release.
