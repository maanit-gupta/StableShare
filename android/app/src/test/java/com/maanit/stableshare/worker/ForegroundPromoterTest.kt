package com.maanit.stableshare.worker

import com.maanit.stableshare.engine.CoordinatorStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression (found in Phase 4): a coordinator started in the background had its foreground
 * start refused once and then never showed the ongoing notification for the rest of the run.
 */
class ForegroundPromoterTest {
    private var now = 0L
    private val attempts = mutableListOf<Long>()
    private var allowed = false
    private val promoter = ForegroundPromoter(promote = { attempts += now; allowed }, clock = { now }, retryMs = 10_000)
    private val status = CoordinatorStatus(1, 0, 100)

    @Test
    fun aRefusedPromotionIsRetriedAfterTheBackoffAndThenFollowsEveryUpdate() = runBlocking {
        promoter.update(status) // refused (app in the background)
        now = 1_000; promoter.update(status)
        now = 9_999; promoter.update(status)
        assertEquals("no retries inside the window", listOf(0L), attempts)

        now = 10_000; promoter.update(status) // still refused
        assertEquals(listOf(0L, 10_000L), attempts)

        allowed = true // the user opened the app
        now = 15_000; promoter.update(status)
        now = 20_000; promoter.update(status)
        now = 21_000; promoter.update(status)
        now = 22_000; promoter.update(status)
        assertEquals("promoted at 20 s, then every update refreshes", listOf(0L, 10_000L, 20_000L, 21_000L, 22_000L), attempts)
    }

    @Test
    fun aPromotedRunUpdatesEveryTime() = runBlocking {
        allowed = true
        repeat(3) { promoter.update(status); now += 1_000 }
        assertEquals(listOf(0L, 1_000L, 2_000L), attempts)
    }
}
