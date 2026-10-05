package com.maanit.stableshare.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.StableShareApp
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.engine.NetworkState
import com.maanit.stableshare.engine.TransferProgressTracker
import com.maanit.stableshare.ui.theme.StableShareTheme
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.ui.transfers.BannerSlot
import com.maanit.stableshare.ui.transfers.TransfersScreen
import com.maanit.stableshare.ui.transfers.TransfersViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI-SPEC §5.4: the Wi-Fi only banner, its place in the banner priority, and the waiting label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransfersBannerTest {
    @get:Rule val compose = createComposeRule()

    private val offline = "You're offline. Transfers will continue when you reconnect."
    private val wifiOnly = "Wi-Fi only is on. Transfers will continue on Wi-Fi."
    private val unreachable = "Can't reach the server at http://10.0.2.2:8080."

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()

    @After
    fun tearDown() = db.close()

    private fun banners(network: NetworkState, wifiOnly: Boolean, reachable: Boolean) {
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                BannerSlot(network, wifiOnly, reachable, "http://10.0.2.2:8080", onOpenSettings = {})
            }
        }
    }

    private fun assertOnly(text: String?) {
        listOf(offline, wifiOnly, unreachable).forEach {
            if (it == text) compose.onNodeWithText(it).assertExists() else compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun wifiOnlyOverMobileDataShowsTheWifiBanner() {
        banners(NetworkState.Metered, wifiOnly = true, reachable = true)
        assertOnly(wifiOnly)
    }

    @Test
    fun mobileDataWithoutWifiOnlyShowsNoBanner() {
        banners(NetworkState.Metered, wifiOnly = false, reachable = true)
        assertOnly(null)
    }

    @Test
    fun offlineComesFirst() {
        banners(NetworkState.Offline, wifiOnly = true, reachable = false)
        assertOnly(offline)
    }

    @Test
    fun wifiOnlyComesBeforeServerUnreachable() {
        banners(NetworkState.Metered, wifiOnly = true, reachable = false)
        assertOnly(wifiOnly)
    }

    @Test
    fun onWifiTheServerBannerIsBack() {
        banners(NetworkState.Unmetered, wifiOnly = true, reachable = false)
        assertOnly(unreachable)
    }

    @Test
    fun theScreenFollowsTheSettingAndTheNetwork() {
        val container = ApplicationProvider.getApplicationContext<StableShareApp>().container
        val repo = TransferRepository(db)
        runBlocking { repo.createUpload("report_q3.pdf", 4_096, null, "file:///report_q3.pdf", 1_024) }
        val settings = MutableStateFlow(Settings(wifiOnly = true, serverChosen = true))
        val network = MutableStateFlow(NetworkState.Metered)
        val vm = TransfersViewModel(
            repo = repo,
            tracker = TransferProgressTracker(),
            restored = MutableStateFlow(emptySet()),
            settings = settings,
            networkState = network,
            checkHealth = { _, _ -> flowOf(ServerHealth.Online(1, null)) },
            setLimit = {},
            controller = container.transferController,
            isGenerated = { false },
            appScope = container.applicationScope,
        )
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                TransfersScreen(vm, onOpenDetail = {}, onOpenUpload = {}, onOpenSettings = {}, downloadSheet = {}, serverSheet = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("report_q3.pdf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(wifiOnly).assertExists()
        compose.onNodeWithText("Waiting for Wi-Fi").assertExists()

        // Wi-Fi again: no banner, and the row is back in line.
        network.value = NetworkState.Unmetered
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Waiting, #1 in line").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(wifiOnly).assertDoesNotExist()

        // Mobile data with the switch off: transfers may run there, so no banner and no Wi-Fi wait.
        settings.value = Settings(wifiOnly = false, serverChosen = true)
        network.value = NetworkState.Metered
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Waiting, #1 in line").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(wifiOnly).assertDoesNotExist()
        compose.onNodeWithText("Waiting for Wi-Fi").assertDoesNotExist()
    }
}
