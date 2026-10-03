package com.maanit.stableshare.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** UI-SPEC §5.10: "Transfers at the same time" applies immediately and persists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun concurrencySegmentedControlPersistsItsValue() {
        val container = ApplicationProvider.getApplicationContext<StableShareApp>().container
        var screen by mutableIntStateOf(0)
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                CompositionLocalProvider(LocalAppContainer provides container, LocalSnackbar provides SnackbarHostState()) {
                    // A new key gives a brand-new ViewModel, as if the screen were opened again.
                    key(screen) {
                        val vm: SettingsViewModel = viewModel(
                            key = "settings-$screen",
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
        fun segment(n: Int) = compose.onNode(hasText(n.toString()) and hasParent(hasTestTag("concurrency")).not() and hasAncestorTag("concurrency"))

        compose.waitUntil(5_000) { compose.onAllNodes(hasAncestorTag("concurrency")).fetchSemanticsNodes().isNotEmpty() }
        segment(2).performScrollTo().assertIsSelected()
        segment(3).assertIsNotSelected()

        // The radio button's own click action (the control sits at the edge of the small test window).
        segment(3).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("3") and hasAncestorTag("concurrency") and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, runBlocking { container.settingsRepository.current().maxConcurrent })

        screen++
        compose.waitUntil(5_000) { compose.onAllNodes(hasAncestorTag("concurrency")).fetchSemanticsNodes().isNotEmpty() }
        segment(3).performScrollTo().assertIsSelected()
        segment(2).assertIsNotSelected()
        assertEquals(3, runBlocking { container.settingsRepository.current().maxConcurrent })
    }

    private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))
}
