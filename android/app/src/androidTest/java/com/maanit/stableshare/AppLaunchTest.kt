package com.maanit.stableshare

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real app starts on a device and shows either the intro or the Transfers screen. */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToOnboardingOrTransfers() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Send big files, calmly").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Transfers").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
