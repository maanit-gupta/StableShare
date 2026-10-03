package com.maanit.stableshare.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.maanit.stableshare.ui.splash.SPLASH_MS
import com.maanit.stableshare.ui.splash.SPLASH_REDUCED_MS
import com.maanit.stableshare.ui.splash.SplashScreen
import com.maanit.stableshare.ui.theme.StableShareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SplashScreenTest {
    @get:Rule val compose = createComposeRule()

    private var finished = 0

    private fun show(reduced: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { StableShareTheme(reducedMotion = reduced) { SplashScreen(onFinished = { finished++ }) } }
    }

    @Test
    fun reducedMotionShowsEverythingAtRestAndLeavesAfter600ms() {
        show(reduced = true)
        compose.mainClock.advanceTimeByFrame()
        // Static: wordmark and tagline are there from the first frame.
        compose.onNodeWithText("StableShare").assertIsDisplayed()
        compose.onNodeWithText("Big files, landed safely.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(SPLASH_REDUCED_MS - 100L)
        assertEquals(0, finished)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, finished)
    }

    @Test
    fun fullMotionRunsTheWhole1600ms() {
        show(reduced = false)
        compose.mainClock.advanceTimeBy(SPLASH_REDUCED_MS + 100L)
        assertEquals("the animated splash is still playing", 0, finished)
        compose.mainClock.advanceTimeBy(SPLASH_MS.toLong())
        assertEquals(1, finished)
    }

    @Test
    fun aTapSkipsToTheEnd() {
        show(reduced = false)
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("splash").performClick()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(1, finished)
        compose.mainClock.advanceTimeBy(SPLASH_MS.toLong())
        assertEquals("finishes once", 1, finished)
    }
}
