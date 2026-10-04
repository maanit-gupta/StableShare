package com.maanit.stableshare.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.StableShareApp
import com.maanit.stableshare.ui.LocalAppContainer
import com.maanit.stableshare.ui.LocalSnackbar
import com.maanit.stableshare.ui.settings.SettingsScreen
import com.maanit.stableshare.ui.settings.SettingsViewModel
import com.maanit.stableshare.ui.theme.StableShareTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** UI-SPEC §5.10: "Transfers at the same time" and "Wi-Fi only" apply immediately and persist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val container = ApplicationProvider.getApplicationContext<StableShareApp>().container
    private val screen = mutableIntStateOf(0)

    /**
     * Changing "Transfers at the same time" or "Wi-Fi only" makes EngineBootstrap call WorkManager;
     * without a test instance its default init throws on a background coroutine, which a later
     * runTest then reports as an uncaught exception (seen as a flaky failure in the full suite).
     */
    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun showSettings() {
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                CompositionLocalProvider(LocalAppContainer provides container, LocalSnackbar provides SnackbarHostState()) {
                    // A new key gives a brand-new ViewModel, as if the screen were opened again.
                    key(screen.intValue) {
                        val vm: SettingsViewModel = viewModel(
                            key = "settings-${screen.intValue}",
                            factory = viewModelFactory {
                                initializer {
                                    val offline: suspend () -> Nothing = { throw IOException("no server in tests") }
                                    SettingsViewModel(
                                        settingsRepo = container.settingsRepository,
                                        health = { false },
                                        getFaults = offline,
                                        putFaults = { offline() },
                                        resetFaults = offline,
                                        getStats = offline,
                                        classifier = container.errorClassifier,
                                    )
                                }
                            },
                        )
                        SettingsScreen(vm, scrollToTransfers = false, onShowIntro = {}, onOpenLicences = {})
                    }
                }
            }
        }
    }

    /** Leaves and opens the screen again (a new ViewModel reading DataStore). */
    private fun reopen() {
        screen.intValue++
    }

    @Test
    fun concurrencySegmentedControlPersistsItsValue() {
        showSettings()
        fun segment(n: Int) = compose.onNode(hasText(n.toString()) and hasParent(hasTestTag("concurrency")).not() and hasAncestorTag("concurrency"))

        compose.waitUntil(5_000) { compose.onAllNodes(hasAncestorTag("concurrency")).fetchSemanticsNodes().isNotEmpty() }
        segment(2).performScrollTo().assertIsSelected()
        segment(3).assertIsNotSelected()

        // The radio button's own click action (the control sits at the edge of the small test window).
        segment(3).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("3") and hasAncestorTag("concurrency") and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, runBlocking { container.settingsRepository.current().maxConcurrent })

        reopen()
        compose.waitUntil(5_000) { compose.onAllNodes(hasAncestorTag("concurrency")).fetchSemanticsNodes().isNotEmpty() }
        segment(3).performScrollTo().assertIsSelected()
        segment(2).assertIsNotSelected()
        assertEquals(3, runBlocking { container.settingsRepository.current().maxConcurrent })
    }

    @Test
    fun parallelChunksSegmentedControlPersistsItsValue() {
        showSettings()
        fun segment(n: Int) = compose.onNode(hasText(n.toString()) and hasAncestorTag("parallelChunks"))
        fun selected(n: Int) = compose.onAllNodes(hasText(n.toString()) and hasAncestorTag("parallelChunks") and androidx.compose.ui.test.isSelected())

        compose.waitUntil(5_000) { compose.onAllNodes(hasAncestorTag("parallelChunks")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pieces at once per transfer").assertExists()
        compose.onNodeWithText("More pieces at once can be faster on a good connection. Applies to transfers that start after you change it.").assertExists()
        assertEquals("only 1, 2 and 4", 3, compose.onAllNodes(hasAncestorTag("parallelChunks") and hasClickAction()).fetchSemanticsNodes().size)
        segment(1).performScrollTo().assertIsSelected()
        segment(4).assertIsNotSelected()

        segment(4).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { selected(4).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, runBlocking { container.settingsRepository.current().parallelChunks })

        reopen()
        compose.waitUntil(5_000) { compose.onAllNodes(hasAncestorTag("parallelChunks")).fetchSemanticsNodes().isNotEmpty() }
        segment(4).performScrollTo().assertIsSelected()
        segment(1).assertIsNotSelected()
        assertEquals(4, runBlocking { container.settingsRepository.current().parallelChunks })
    }

    @Test
    fun wifiOnlySwitchSitsBelowRetryAndPersistsItsValue() {
        showSettings()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("wifiOnly").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wifiOnly").performScrollTo().assertIsOff()
        compose.onNodeWithText("Wi-Fi only").assertExists()
        compose.onNodeWithText("Transfers wait for Wi-Fi and won't use mobile data.").assertExists()
        val retryTop = compose.onNodeWithTag("autoRetry").getUnclippedBoundsInRoot().top
        val wifiTop = compose.onNodeWithTag("wifiOnly").getUnclippedBoundsInRoot().top
        assertTrue("Wi-Fi only sits below Retry automatically", wifiTop > retryTop)

        compose.onNodeWithTag("wifiOnly").performSemanticsAction(SemanticsActions.OnClick)
        // The switch shows the stored value, so On means DataStore has it.
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("wifiOnly") and isOn()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { container.settingsRepository.current().wifiOnly })

        reopen()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("wifiOnly").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wifiOnly").performScrollTo().assertIsOn()
        assertTrue(runBlocking { container.settingsRepository.current().wifiOnly })
    }

    private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))
}
