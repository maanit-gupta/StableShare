package com.maanit.stableshare.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.detail.Detail
import com.maanit.stableshare.ui.detail.DetailUi
import com.maanit.stableshare.ui.detail.DetailsCard
import com.maanit.stableshare.ui.history.HistoryItem
import com.maanit.stableshare.ui.history.HistoryRow
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.theme.StableShareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Instant upload copy (UI-SPEC §5.4.1, §5.8.6, §5.9): the "Already on server" pill and the "Data sent" row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstantUploadUiTest {
    @get:Rule val compose = createComposeRule()

    private val row = TransferEntity(
        id = "t", type = TransferType.UPLOAD, fileName = "same.bin", fileSize = 4_000, mimeType = null, localUri = "file:///same.bin",
        remoteId = "t", chunkSize = 1_000, totalChunks = 4, sha256 = "ab".repeat(32), sourceLastModified = null, etag = null,
        state = TransferState.COMPLETED, bytesDone = 4_000, errorCode = null, errorMessage = null, attemptCount = 0,
        nextRetryAt = null, sessionCreated = true, createdAt = 0, updatedAt = 1_000, completedAt = 1_000,
    )

    private fun ui(instant: Boolean) = DetailUi(
        item = TransferItem.build(row, null, null, restored = false, now = 1_000, instant = instant),
        chunks = emptyList(),
        doneChunks = 4,
        failedChunks = 0,
        activity = emptyList(),
        retryAttempt = 1,
        maxConcurrent = 2,
        verifiedSha = row.sha256,
        generated = false,
        now = 1_000,
    )

    @Test
    fun detailsCardShowsDataSentForInstantUploads() {
        compose.setContent { StableShareTheme(reducedMotion = true) { DetailsCard(ui(instant = true)) } }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Data sent").assertExists()
        compose.onNodeWithText("None, the server already had this file").assertExists()
    }

    @Test
    fun detailsCardHasNoDataSentRowOtherwise() {
        compose.setContent { StableShareTheme(reducedMotion = true) { DetailsCard(ui(instant = false)) } }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Size").assertExists()
        compose.onNodeWithText("Data sent").assertDoesNotExist()
    }

    @Test
    fun detailScreenShowsThePillUnderTheFileLine() {
        compose.setContent { StableShareTheme(reducedMotion = true) { Detail(ui(instant = true), onBack = {}, perform = {}) } }
        compose.onNodeWithText("Already on server").performScrollTo().assertExists()
    }

    @Test
    fun detailScreenHasNoPillOtherwise() {
        compose.setContent { StableShareTheme(reducedMotion = true) { Detail(ui(instant = false), onBack = {}, perform = {}) } }
        compose.onNodeWithText("Already on server").assertDoesNotExist()
    }

    @Test
    fun historyRowShowsThePill() {
        compose.setContent { StableShareTheme(reducedMotion = true) { HistoryRow(HistoryItem(row, generated = false, instant = true), {}, {}) } }
        compose.onNodeWithText("Already on server").assertExists()
    }

    @Test
    fun historyRowHasNoPillOtherwise() {
        compose.setContent { StableShareTheme(reducedMotion = true) { HistoryRow(HistoryItem(row, generated = false), {}, {}) } }
        compose.onNodeWithText("Already on server").assertDoesNotExist()
    }
}
