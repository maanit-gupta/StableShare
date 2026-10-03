package com.maanit.stableshare.domain

import kotlin.random.Random

/**
 * Exponential backoff with full jitter (DESIGN.md §7).
 * `attempt` counts this chunk's failures so far, starting at 1:
 * delay(attempt) = uniform(0, min(cap, base × 2^(attempt − 1))).
 */
class RetryPolicy(
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val maxAttemptsPerChunk: Int = 5,
    private val random: Random = Random.Default,
) {
    init {
        require(baseDelayMs > 0 && maxDelayMs >= baseDelayMs && maxAttemptsPerChunk > 0)
    }

    /** Upper bound of the jitter window for this attempt. */
    fun ceilingFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt starts at 1, was $attempt" }
        val shift = (attempt - 1).coerceAtMost(30) // 2^30 s is far past any cap
        val exp = baseDelayMs.toDouble() * (1L shl shift)
        return minOf(maxDelayMs.toDouble(), exp).toLong()
    }

    fun delayFor(attempt: Int): Long = random.nextLong(0, ceilingFor(attempt) + 1)

    /** A server Retry-After is a lower bound, still capped at maxDelayMs. */
    fun delayHonouringRetryAfter(attempt: Int, retryAfterMs: Long?): Long {
        val jittered = delayFor(attempt)
        if (retryAfterMs == null || retryAfterMs <= 0) return jittered
        return maxOf(jittered, retryAfterMs).coerceAtMost(maxDelayMs)
    }

    /** True if another try is allowed after `failedAttempts` failures of the same chunk. */
    fun canRetry(failedAttempts: Int): Boolean = failedAttempts < maxAttemptsPerChunk
}
