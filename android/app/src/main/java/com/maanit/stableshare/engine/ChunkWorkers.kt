package com.maanit.stableshare.engine

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Parallel chunks within one transfer (DESIGN.md §6.4): runs [work] for every chunk, dispatched in
 * index order, with at most [parallelism] running at once. The permit is taken BEFORE a worker
 * starts, so it bounds the chunk buffers in memory too (≤ N × chunk size). Returns once every
 * worker has finished; the first one to throw cancels its siblings (and every OkHttp call they
 * have open) and its exception is rethrown. Job cancellation (pause, cancel, stop) reaches them all.
 */
internal suspend fun <C> forEachChunkInParallel(chunks: List<C>, parallelism: Int, work: suspend (C) -> Unit) {
    require(parallelism >= 1) { "parallelism must be at least 1, was $parallelism" }
    coroutineScope {
        val permits = Semaphore(parallelism)
        for (chunk in chunks) {
            permits.acquire()
            launch {
                try {
                    work(chunk)
                } finally {
                    permits.release()
                }
            }
        }
    }
}
