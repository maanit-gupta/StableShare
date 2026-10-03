package com.maanit.stableshare.worker

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.CoordinatorStatus
import com.maanit.stableshare.ui.AppIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** UI-SPEC §5.12 copy, channels and tap targets, and when results are announced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications = TransferNotifications(context).apply { ensureChannel() }
    private val mb = 1024L * 1024

    init {
        Locale.setDefault(Locale.US)
    }

    private fun row(id: String, state: TransferState, type: TransferType = TransferType.UPLOAD, error: ErrorCode? = null) = TransferEntity(
        id = id, type = type, fileName = "report_q3.pdf", fileSize = 200 * mb, mimeType = null, localUri = "file:///x",
        remoteId = id, chunkSize = 1, totalChunks = 1, sha256 = null, sourceLastModified = null, etag = null, state = state,
        bytesDone = 0, errorCode = error, errorMessage = null, attemptCount = 0, nextRetryAt = null, sessionCreated = false,
        createdAt = 0, updatedAt = 0, completedAt = null,
    )

    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun ongoingShowsFilesPercentAndSpeedAndOpensTransfers() {
        val n = notifications.build(CoordinatorStatus(active = 2, bytes = 84 * mb, totalBytes = 200 * mb, pending = 3, bytesPerSecond = 4.1 * mb))
        assertEquals("Moving 3 files", n.title)
        assertEquals("42% overall, 4.1 MB/s", n.text)
        assertEquals(TransferNotifications.CHANNEL_ID, n.channelId)
        assertEquals(42, n.extras.getInt(Notification.EXTRA_PROGRESS))
        val intent = shadowOf(n.contentIntent).savedIntent
        assertTrue(intent.getBooleanExtra(AppIntents.EXTRA_OPEN_TRANSFERS, false))

        val one = notifications.build(CoordinatorStatus(1, 0, 10 * mb))
        assertEquals("Moving 1 file", one.title)
        assertEquals("0% overall", one.text)
    }

    @Test
    fun completedAndFailedOpenTheTransfer() {
        val done = notifications.completed(row("a", TransferState.COMPLETED, TransferType.DOWNLOAD))
        assertEquals("Download complete", done.title)
        assertEquals("report_q3.pdf was verified.", done.text)
        assertEquals(TransferNotifications.RESULTS_CHANNEL_ID, done.channelId)
        assertEquals("a", shadowOf(done.contentIntent).savedIntent.getStringExtra(AppIntents.EXTRA_TRANSFER_ID))

        val failed = notifications.failed(row("b", TransferState.FAILED, error = ErrorCode.SOURCE_MISSING))
        assertEquals("Upload failed", failed.title)
        assertEquals("report_q3.pdf: File not found. Tap to see options.", failed.text)
        assertEquals("b", shadowOf(failed.contentIntent).savedIntent.getStringExtra(AppIntents.EXTRA_TRANSFER_ID))
    }

    @Test
    fun onlyTransitionsSeenInThisProcessAreAnnounced() = runTest(UnconfinedTestDispatcher()) {
        val rows = MutableStateFlow(listOf(row("old", TransferState.COMPLETED), row("x", TransferState.TRANSFERRING), row("y", TransferState.VERIFYING)))
        val posted = mutableListOf<Pair<String, String>>()
        TransferResultNotifier(
            transfers = rows,
            completed = { notifications.completed(it) },
            failed = { notifications.failed(it) },
            post = { id, n -> posted += id to n.title },
            scope = backgroundScope,
        ).start()
        assertTrue("the baseline announces nothing", posted.isEmpty())

        rows.value = listOf(row("old", TransferState.COMPLETED), row("x", TransferState.FAILED, error = ErrorCode.TIMEOUT), row("y", TransferState.COMPLETED))
        assertEquals(listOf("x" to "Upload failed", "y" to "Upload complete"), posted)

        rows.value = rows.value.toList() // no change, no repeat
        assertEquals(2, posted.size)
    }
}
