package com.maanit.stableshare.fuzz

import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING

/** The state machine table (docs/DESIGN.md §4), copied by hand so the fuzz tests do not trust StateMachine. */
val LEGAL_TRANSITIONS: Map<TransferState, Set<TransferState>> = mapOf(
    QUEUED to setOf(TRANSFERRING, PAUSED, CANCELLED),
    TRANSFERRING to setOf(VERIFYING, RETRYING, PAUSED, FAILED, CANCELLED, QUEUED),
    RETRYING to setOf(TRANSFERRING, QUEUED, PAUSED, FAILED, CANCELLED),
    VERIFYING to setOf(COMPLETED, RETRYING, FAILED, CANCELLED, QUEUED),
    PAUSED to setOf(QUEUED, CANCELLED),
    FAILED to setOf(QUEUED, CANCELLED),
    COMPLETED to emptySet(),
    CANCELLED to emptySet(),
)

/**
 * Seeds for the fuzz tests. `-Pfuzz.seeds=N` runs seeds 1..N (default 200), `-Pfuzz.seed=S`
 * replays only S. Seeds listed in `fuzz-regressions.txt` (`<layer> <seed>` per line) always run.
 */
object FuzzSeeds {

    fun forLayer(layer: String): List<Long> {
        System.getProperty("fuzz.seed")?.takeIf { it.isNotBlank() }?.let { return listOf(it.trim().toLong()) }
        val count = System.getProperty("fuzz.seeds")?.trim()?.toLongOrNull() ?: DEFAULT_SEEDS
        return (regressions(layer) + (1..count)).distinct()
    }

    /** Runs [body] once per seed and fails with the seed and the replay command on the first failure. */
    inline fun run(layer: String, testClass: String, body: (Long) -> Unit) {
        for (seed in forLayer(layer)) {
            try {
                body(seed)
            } catch (t: Throwable) {
                throw AssertionError(
                    "$layer failed at seed $seed: ${t.message}\n" +
                        "Replay: ./gradlew :app:testDebugUnitTest --tests '$testClass' -Pfuzz.seed=$seed",
                    t,
                )
            }
        }
    }

    private fun regressions(layer: String): List<Long> {
        val text = FuzzSeeds::class.java.classLoader?.getResource("fuzz-regressions.txt")?.readText() ?: return emptyList()
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")) }
            .filter { it.size == 2 && it[0] == layer }
            .map { it[1].toLong() }
            .toList()
    }

    const val DEFAULT_SEEDS = 200L
}
