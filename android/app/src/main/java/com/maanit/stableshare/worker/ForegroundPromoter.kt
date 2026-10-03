package com.maanit.stableshare.worker

import com.maanit.stableshare.engine.CoordinatorStatus

/**
 * Keeps the coordinator's foreground notification in step with its status. If Android refuses
 * the foreground start (a run that began while the app was in the background, Android 12+), the
 * promotion is retried at most every [retryMs] — it succeeds once the app is in the foreground —
 * instead of being abandoned for the rest of the run.
 */
class ForegroundPromoter(
    private val promote: suspend (CoordinatorStatus) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryMs: Long = RETRY_MS,
) {
    private var promoted = false
    private var lastAttemptAt: Long? = null

    suspend fun update(status: CoordinatorStatus) {
        val now = clock()
        val last = lastAttemptAt
        if (!promoted && last != null && now - last < retryMs) return
        lastAttemptAt = now
        promoted = promote(status)
    }

    companion object {
        const val RETRY_MS = 10_000L
    }
}
