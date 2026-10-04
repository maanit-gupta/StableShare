package com.maanit.stableshare.fuzz

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
