package com.maanit.stableshare.ui.screens

import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.StableShareApp
import com.maanit.stableshare.di.AppContainer
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.ui.StableShareRoot
import com.maanit.stableshare.ui.theme.StableShareTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whole-app flows through the real navigation graph and the real AppContainer (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppFlowTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<StableShareApp>().container
    }

    private fun launch() {
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                StableShareRoot(container, showSplash = false, openRequests = MutableStateFlow<Intent?>(null))
            }
        }
    }

    private fun onboardingFlag() = runBlocking { container.settingsRepository.current().onboardingCompleted }

    private fun awaitTransfers() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nothing moving right now").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun skipSetsTheFlagAndLandsOnTransfers() {
        assertFalse(onboardingFlag())
        launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Send big files, calmly").assertExists()
        compose.onNodeWithText("Skip").performClick()
        awaitTransfers()
        assertTrue(onboardingFlag())
        compose.onNodeWithText("Nothing in the air").assertExists()
    }

    @Test
    fun getStartedOnTheLastPageSetsTheFlagAndLandsOnTransfers() {
        launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Next").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Stop anytime, pick up where you left off").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Every file is checked").assertExists()
        compose.onAllNodesWithText("Skip").fetchSemanticsNodes().let { assertTrue("no Skip on page 3", it.isEmpty()) }
        assertFalse(onboardingFlag())
        compose.onNodeWithText("Get started").performClick()
        awaitTransfers()
        assertTrue(onboardingFlag())
    }

    @Test
    fun completedOnboardingOpensTransfersDirectly() {
        runBlocking { container.settingsRepository.setOnboardingCompleted(true) }
        launch()
        awaitTransfers()
        assertEquals(0, compose.onAllNodesWithText("Skip").fetchSemanticsNodes().size)
    }

    @Test
    fun cancelAsksForConfirmationFirst() {
        runBlocking { container.settingsRepository.setOnboardingCompleted(true) }
        val id = runBlocking {
            container.transferRepository.createUpload("holiday.mov", 10_000_000, null, "file:///nowhere/holiday.mov", 2 * 1024 * 1024).id
        }
        // Paused, so no coordinator picks it up during the test.
        runBlocking { container.transferRepository.transition(id, TransferState.PAUSED) }
        launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("holiday.mov").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Cancel holiday.mov").performClick()
        compose.onNodeWithText("Cancel this transfer?").assertExists()
        compose.onNodeWithText("holiday.mov will stop and its partial data will be deleted. This can't be undone.").assertExists()
        compose.onNodeWithText("Keep going").performClick()
        compose.onNodeWithText("Cancel this transfer?").assertDoesNotExist()
        assertEquals(TransferState.PAUSED, state(id))

        compose.onNodeWithContentDescription("Cancel holiday.mov").performClick()
        compose.onNodeWithText("Cancel transfer").performClick()
        compose.waitUntil(5_000) { state(id) == TransferState.CANCELLED }
    }

    private fun state(id: String) = runBlocking { container.transferRepository.getTransfer(id)!!.state }
}
