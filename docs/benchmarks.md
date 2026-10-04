# Benchmarks — parallel chunks

How much "Pieces at once per transfer" (`parallelChunks`, N = 1, 2, 4; DESIGN.md §6.4) speeds up one transfer. The default stays at **1**.

## Setup

- **Date:** 2026-10-04.
- **Host:** Apple M5 (10 cores, 16 GB), macOS 26.6.2, Node v26.5.1. The mock server runs on the host (`npm start`, port 8080) with default settings: 2 MiB pieces and a 24 h session expiry.
- **Device:** Android emulator `Pixel_9_root`, API 37 (google_apis_ps16k, arm64-v8a, 4 cores, 2 GB RAM), reaching the server at `http://10.0.2.2:8080` over the emulator's virtual network.
- **App:** debug build at commit `0b68c71` plus the benchmark's idle-engine cleanup. One transfer at a time, Wi-Fi only off, automatic retry on.
- **Runner:** `android/scripts/benchmark-parallel.sh` (instrumented test `ParallelChunksBenchmark`). Each run is timed from enqueue to COMPLETED, so it includes the upload's checksum pass (Preparing) and the final verification on both sides. Uploads use freshly generated random files, so the server never answers with an instant upload. Every run is a fresh transfer, and the file is deleted afterwards.
- **Networks:**
  - *Faults off:* simulator disabled, every value 0.
  - *Slow network:* the UI preset (latency 800 ms, jitter 400 ms, `bandwidthKbps` 512, no errors).
- **Sizes:** 200 MiB with faults off. 20 MiB on Slow network (user decision: a 200 MiB transfer at 512 kbps takes about 55 min per run). The 20 MiB download is `bench-20MB`, seeded by the script.
- **Runs:** 3 per cell; the table gives the median. MB/s and kB/s are decimal (10⁶ and 10³ bytes).

## Results

| Network | Direction | Size | N | Runs (s) | Median (s) | Throughput | vs N = 1 |
|---|---|---|---|---|---|---|---|
| Faults off | Upload | 200 MiB | 1 | 102.8, 67.7, 35.2 | 67.7 | 3.10 MB/s | 1.00× |
| Faults off | Upload | 200 MiB | 2 | 32.7, 35.5, 33.7 | 33.7 | 6.21 MB/s | 2.01× |
| Faults off | Upload | 200 MiB | 4 | 36.4, 34.4, 33.6 | 34.4 | 6.09 MB/s | 1.97× |
| Faults off | Download | 200 MiB | 1 | 25.9, 26.4, 26.9 | 26.4 | 7.94 MB/s | 1.00× |
| Faults off | Download | 200 MiB | 2 | 24.3, 23.2, 23.8 | 23.8 | 8.82 MB/s | 1.11× |
| Faults off | Download | 200 MiB | 4 | 20.3, 43.2, 21.0 | 21.0 | 9.99 MB/s | 1.26× |
| Slow network | Upload | 20 MiB | 1 | 498.5, 496.8, 532.2 | 498.5 | 42 kB/s | 1.00× |
| Slow network | Upload | 20 MiB | 2 | 202.7, 265.4, 235.9 | 235.9 | 89 kB/s | 2.11× |
| Slow network | Upload | 20 MiB | 4 | 136.2, 135.0, 136.0 | 136.0 | 154 kB/s | 3.67× |
| Slow network | Download | 20 MiB | 1 | 337.8, 336.8, 337.4 | 337.4 | 62 kB/s | 1.00× |
| Slow network | Download | 20 MiB | 2 | 169.9, 169.5, 168.3 | 169.5 | 124 kB/s | 1.99× |
| Slow network | Download | 20 MiB | 4 | 102.1, 101.7, 102.1 | 102.1 | 205 kB/s | 3.31× |

### Faults-off uploads, warm re-run

The faults-off upload at N = 1 was the first case of the session, and its runs fell from 103 s to 35 s as the emulator warmed up. The three faults-off upload cells were therefore run again back to back, after the full benchmark:

| Network | Direction | Size | N | Runs (s) | Median (s) | Throughput | vs N = 1 |
|---|---|---|---|---|---|---|---|
| Faults off | Upload | 200 MiB | 1 | 39.3, 40.7, 40.7 | 40.7 | 5.16 MB/s | 1.00× |
| Faults off | Upload | 200 MiB | 2 | 36.6, 41.0, 41.0 | 41.0 | 5.11 MB/s | 0.99× |
| Faults off | Upload | 200 MiB | 4 | 34.6, 32.5, 44.7 | 34.6 | 6.06 MB/s | 1.18× |

Use these warm numbers for faults-off uploads, not the first table's 2×.

## Reading the numbers

- **Fast local link:** parallel pieces help little. Warm uploads: N = 2 is the same as N = 1, and N = 4 is about 1.2× faster. Downloads: 1.1× at N = 2, 1.26× at N = 4 (one N = 4 run took 43 s, an outlier; the median is unaffected). Here the limit is the device itself (hashing, fsync per piece, the emulator's virtual NIC), not the wait for each request.
- **Slow network:** the speed-up is close to linear (download 2.0× / 3.3×, upload 2.1× / 3.7×). **This overstates what a real slow link would give.** The mock server's `bandwidthKbps` throttles *each request* from its own start, so N pieces in flight get N × 512 kbps. On a real link of fixed capacity, the pieces would share the bandwidth, and parallelism could only hide the per-request latency (800 ± 400 ms per piece here, about 10 of 330 s at N = 1).
- **Upload timeouts on Slow network:** at 512 kbps a 2 MiB piece needs about 33 s to drain. The client's request body fits in socket buffers, so OkHttp's 30 s read timeout runs while the server is still reading it slowly, and pieces often time out at the end and are retried. This explains why the N = 1 upload (498 s) is much slower than the download (337 s), where a steady stream of bytes keeps the read timeout from firing. Every transfer still completed and verified.

## Recommendation

Keep the default at 1. On the fast path the gain is small (≤ 1.26×) and costs up to N piece buffers of memory. The slow-network gain mostly comes from the mock's per-request throttle. N = 2 or 4 is worth offering for high-latency links, which is what the setting's helper text says.

## Reproduce

```sh
cd server && npm start                      # in one terminal
cd android && scripts/benchmark-parallel.sh # results in android/build/benchmark-parallel.tsv (RUNS=3 by default)
```
