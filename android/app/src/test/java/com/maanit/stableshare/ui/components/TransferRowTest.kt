package com.maanit.stableshare.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.LiveProgress
import com.maanit.stableshare.engine.TransferPhase
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.theme.StableShareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Each state renders its status label and only the buttons StateMachine.allowedActions offers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferRowTest {
    @get:Rule val compose = createComposeRule()

    private val mb = 1024L * 1024
    private val name = "report_q3.pdf"

    init {
        Locale.setDefault(Locale.US)
    }

    private fun row(state: TransferState, error: ErrorCode? = null, nextRetryAt: Long? = null, bytes: Long = 84 * mb) = TransferEntity(
        id = "t", type = TransferType.UPLOAD, fileName = name, fileSize = 200 * mb, mimeType = null, localUri = "file:///x",
        remoteId = "t", chunkSize = (2 * mb).toInt(), totalChunks = 100, sha256 = null, sourceLastModified = null,
        etag = null, state = state, bytesDone = bytes, errorCode = error, errorMessage = null, attemptCount = 0,
        nextRetryAt = nextRetryAt, sessionCreated = true, createdAt = 0, updatedAt = 0, completedAt = null,
    )

    private val actions = mutableListOf<TransferAction>()

    private fun show(item: TransferItem) {
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                TransferRow(item, generated = false, onOpen = {}, onAction = { actions += it })
            }
        }
    }

    private fun item(r: TransferEntity, live: LiveProgress? = null, position: Int? = null, restored: Boolean = false) =
        TransferItem.build(r, live, position, restored, now = 0)

    private fun assertButtons(vararg expected: String) {
        val all = listOf("Pause", "Resume", "Retry", "Cancel")
        all.forEach { verb ->
            compose.onAllNodesWithContentDescription("$verb $name").assertCountEquals(if (verb in expected) 1 else 0)
        }
    }

    @Test
    fun queued() {
        show(item(row(TransferState.QUEUED), position = 3))
        compose.onNodeWithText("Waiting, #3 in line").assertExists()
        compose.onNodeWithText("84 of 200 MB").assertExists()
        compose.onNodeWithText("42%").assertExists()
        assertButtons("Pause", "Cancel")
    }

    @Test
    fun transferringShowsTheLiveLine() {
        show(item(row(TransferState.TRANSFERRING), LiveProgress(TransferPhase.Transferring, 84 * mb, 0, 3, 200 * mb, 4.1 * mb, 28)))
        compose.onNodeWithText("Uploading…").assertExists()
        // Number and unit never split across lines.
        compose.onNodeWithText("4.1\u00A0MB/\u2060s, 28\u00A0s left").assertExists()
        assertButtons("Pause", "Cancel")
    }

    @Test
    fun preparing() {
        show(item(row(TransferState.TRANSFERRING, bytes = 0), LiveProgress(TransferPhase.Preparing(20 * mb), 0, 0, null, 200 * mb, 0.0, null)))
        compose.onNodeWithText("Preparing, 10%").assertExists()
        assertButtons("Pause", "Cancel")
    }

    @Test
    fun verifyingOffersOnlyCancel() {
        show(item(row(TransferState.VERIFYING, bytes = 200 * mb)))
        compose.onNodeWithText("Verifying…").assertExists()
        compose.onNodeWithText("99%").assertExists()
        assertButtons("Cancel")
    }

    @Test
    fun retrying() {
        show(item(row(TransferState.RETRYING, ErrorCode.TIMEOUT, nextRetryAt = 4_000)))
        compose.onNodeWithText("Retrying in 4 s").assertExists()
        assertButtons("Pause", "Cancel")
    }

    @Test
    fun waitingForNetwork() {
        show(item(row(TransferState.RETRYING, ErrorCode.NETWORK_UNAVAILABLE)))
        compose.onNodeWithText("Waiting for network").assertExists()
        assertButtons("Pause", "Cancel")
    }

    @Test
    fun waitingForWifi() {
        show(item(row(TransferState.RETRYING, ErrorCode.METERED_NETWORK)))
        compose.onNodeWithText("Waiting for Wi-Fi").assertExists()
        compose.onNodeWithText("Waiting for network").assertDoesNotExist()
        assertButtons("Pause", "Cancel")
    }

    @Test
    fun queuedWhileWifiOnlyHoldsItBack() {
        show(TransferItem.build(row(TransferState.QUEUED), null, queuePosition = 3, restored = false, now = 0, wifiGated = true))
        compose.onNodeWithText("Waiting for Wi-Fi").assertExists()
        compose.onNodeWithText("Waiting, #3 in line").assertDoesNotExist()
        compose.onNodeWithText("84 of 200 MB").assertExists()
        assertButtons("Pause", "Cancel")
    }

    @Test
    fun pausedOffersResume() {
        show(item(row(TransferState.PAUSED)))
        compose.onNodeWithText("Paused").assertExists()
        assertButtons("Resume", "Cancel")
        compose.onNodeWithContentDescription("Resume $name").performClick()
        assertEquals(listOf(TransferAction.RESUME), actions)
    }

    @Test
    fun failedOffersRetry() {
        show(item(row(TransferState.FAILED, ErrorCode.CONNECTION_LOST)))
        compose.onNodeWithText("Failed: Connection dropped").assertExists()
        assertButtons("Retry", "Cancel")
        compose.onNodeWithContentDescription("Cancel $name").performClick()
        assertEquals(listOf(TransferAction.CANCEL), actions)
    }

    @Test
    fun completedHasNoActionsAndShowsTheTotalOnly() {
        show(item(row(TransferState.COMPLETED, bytes = 200 * mb)))
        compose.onNodeWithText("Uploaded").assertExists()
        compose.onNodeWithText("200 MB").assertExists()
        compose.onNodeWithText("100%").assertDoesNotExist() // the check badge replaces the percentage
        assertButtons()
    }

    @Test
    fun cancelledHasNoActions() {
        show(item(row(TransferState.CANCELLED)))
        compose.onNodeWithText("Cancelled").assertExists()
        assertButtons()
    }

    @Test
    fun restoredPill() {
        show(item(row(TransferState.QUEUED), position = 1, restored = true))
        compose.onNodeWithText("Restored after restart").assertExists()
    }
}
