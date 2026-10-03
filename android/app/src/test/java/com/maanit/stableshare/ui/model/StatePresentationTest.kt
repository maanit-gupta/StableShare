package com.maanit.stableshare.ui.model

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.LiveProgress
import com.maanit.stableshare.engine.NetworkState
import com.maanit.stableshare.engine.TransferPhase
import com.maanit.stableshare.ui.mascot.MascotMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** UI-SPEC §6 (and the §4.1 mood table) for every state and condition, resolved to real strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatePresentationTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val mb = 1024L * 1024
    private val now = 1_000_000L

    init {
        Locale.setDefault(Locale.US)
    }

    private fun row(
        state: TransferState,
        type: TransferType = TransferType.UPLOAD,
        error: ErrorCode? = null,
        bytesDone: Long = 84 * mb,
        nextRetryAt: Long? = null,
        size: Long = 200 * mb,
        totalChunks: Int = 100,
    ) = TransferEntity(
        id = "t", type = type, fileName = "report_q3.pdf", fileSize = size, mimeType = null, localUri = "file:///x",
        remoteId = "t", chunkSize = (2 * mb).toInt(), totalChunks = totalChunks, sha256 = null, sourceLastModified = null,
        etag = null, state = state, bytesDone = bytesDone, errorCode = error, errorMessage = null, attemptCount = 0,
        nextRetryAt = nextRetryAt, sessionCreated = true, createdAt = 0, updatedAt = 0, completedAt = null,
    )

    private fun live(phase: TransferPhase, speed: Double = 0.0, eta: Long? = null, inFlight: Long = 0) =
        LiveProgress(phase, 84 * mb, inFlight, 3, 200 * mb, speed, eta)

    private fun item(r: TransferEntity, l: LiveProgress? = null, position: Int? = null) =
        TransferItem.build(r, l, position, restored = false, now = now)

    private fun UiText.s() = resolve(res)
    private fun label(i: TransferItem) = StatePresentation.listLabel(i, 5).s()
    private fun stats(i: TransferItem, done: Int = 42) = StatePresentation.detailStats(i, 2, 3, 5, done).s()
    private fun title(i: TransferItem) = StatePresentation.detailTitle(i.condition, i.type).s()

    @Test
    fun queued() {
        val i = item(row(TransferState.QUEUED), position = 2)
        assertEquals(Condition.QUEUED, i.condition)
        assertEquals("Waiting, #2 in line", label(i))
        assertEquals(LabelTone.TERTIARY, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.MUTED, StatePresentation.barFill(i.condition))
        assertEquals(0.42f, StatePresentation.barFraction(i), 0.001f) // shows saved progress
        assertEquals("Waiting in line", title(i))
        assertEquals("#2 in line. Up to 2 move at once.", stats(i))
        assertEquals(MascotMood.FOCUSED, StatePresentation.mood(i.condition))
        assertEquals(PlaneSpot.PERCHED, StatePresentation.plane(i.condition))
        assertEquals(RingStyle(RingStroke.DASHED, RingTone.SECONDARY_60, null), StatePresentation.ring(i.condition))
    }

    @Test
    fun preparing() {
        val i = item(row(TransferState.TRANSFERRING, bytesDone = 0), live(TransferPhase.Preparing(50 * mb)))
        assertEquals(Condition.PREPARING, i.condition)
        assertEquals("Preparing, 25%", label(i))
        assertEquals(LabelTone.TERTIARY, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.ACCENT, StatePresentation.barFill(i.condition))
        assertEquals("Getting ready...", title(i))
        assertEquals("Calculating the file's checksum, 25%", stats(i))
        assertEquals(PlaneSpot.HOVER, StatePresentation.plane(i.condition))
        assertFalse(StatePresentation.dropsActive(i.condition))
    }

    @Test
    fun transferringUploadAndDownload() {
        val up = item(row(TransferState.TRANSFERRING), live(TransferPhase.Transferring, speed = 4.1 * mb, eta = 28, inFlight = mb / 2))
        assertEquals("Uploading…", label(up))
        assertEquals("Uploading...", title(up))
        assertEquals("4.1 MB/s, about 28 s left", stats(up))
        assertEquals(42, up.percent) // (84 + 0.5) / 200
        assertEquals(84 * mb + mb / 2, up.bytes)
        assertEquals(3, up.inFlightChunk)
        assertTrue(StatePresentation.dropsActive(up.condition))
        assertEquals(RingStyle(RingStroke.DASHED, RingTone.STROKE, 8_000), StatePresentation.ring(up.condition))
        assertEquals(PlaneSpot.RING_PROGRESS, StatePresentation.plane(up.condition))

        val down = item(row(TransferState.TRANSFERRING, TransferType.DOWNLOAD), live(TransferPhase.Transferring))
        assertEquals("Downloading…", label(down))
        assertEquals("Downloading...", title(down))
        assertEquals("Starting…", stats(down)) // before speed is known
    }

    @Test
    fun verifying() {
        val i = item(row(TransferState.VERIFYING, bytesDone = 200 * mb))
        assertEquals("Verifying…", label(i))
        assertEquals(LabelTone.SECONDARY, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.ACCENT_PULSE, StatePresentation.barFill(i.condition))
        assertEquals(1f, StatePresentation.barFraction(i), 0f)
        assertEquals(99, i.percent)
        assertEquals("Checking...", title(i))
        assertEquals("Making sure every byte matches", stats(i))
        assertEquals(RingStyle(RingStroke.DASHED, RingTone.STROKE, 2_000), StatePresentation.ring(i.condition))
        assertEquals(PlaneSpot.RING_LAP, StatePresentation.plane(i.condition))
    }

    @Test
    fun retryingCountsDown() {
        val i = item(row(TransferState.RETRYING, error = ErrorCode.TIMEOUT, nextRetryAt = now + 3_400))
        assertEquals(Condition.RETRYING, i.condition)
        assertEquals("Retrying in 4 s", label(i))
        assertEquals(LabelTone.WARNING, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.WARNING, StatePresentation.barFill(i.condition))
        assertEquals("Trying again...", title(i))
        assertEquals("Attempt 3 of 5. Next try in 4 s.", stats(i))
        assertEquals(MascotMood.WORRIED, StatePresentation.mood(i.condition))
        assertEquals(PlaneSpot.RING_WOBBLE, StatePresentation.plane(i.condition))
        // The in-process backoff deadline wins over the persisted one.
        val live = item(row(TransferState.RETRYING, error = ErrorCode.TIMEOUT, nextRetryAt = now + 3_400), live(TransferPhase.Retrying(now + 900)))
        assertEquals("Retrying in 1 s", label(live))
    }

    @Test
    fun waitingForNetwork() {
        val i = item(row(TransferState.RETRYING, error = ErrorCode.NETWORK_UNAVAILABLE))
        assertEquals(Condition.WAITING_NETWORK, i.condition)
        assertEquals("Waiting for network", label(i))
        assertEquals(LabelTone.SECONDARY, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.MUTED, StatePresentation.barFill(i.condition))
        assertEquals("Waiting for signal", title(i))
        assertEquals("This continues on its own when you're back online.", stats(i))
        assertEquals(MascotMood.SEARCHING, StatePresentation.mood(i.condition))
        assertEquals(PlaneSpot.PERCHED, StatePresentation.plane(i.condition))
    }

    @Test
    fun waitingForWifi() {
        val i = item(row(TransferState.RETRYING, error = ErrorCode.METERED_NETWORK))
        assertEquals(Condition.WAITING_WIFI, i.condition)
        assertEquals("Waiting for Wi-Fi", label(i))
        assertEquals(LabelTone.SECONDARY, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.MUTED, StatePresentation.barFill(i.condition))
        assertEquals("Waiting for Wi-Fi", title(i))
        assertEquals("Wi-Fi only is on. This continues when you connect to Wi-Fi.", stats(i))
        // Mascot, ring and plane exactly as RETRYING with NETWORK_UNAVAILABLE.
        val network = item(row(TransferState.RETRYING, error = ErrorCode.NETWORK_UNAVAILABLE)).condition
        assertEquals(StatePresentation.mood(network), StatePresentation.mood(i.condition))
        assertEquals(StatePresentation.ring(network), StatePresentation.ring(i.condition))
        assertEquals(StatePresentation.plane(network), StatePresentation.plane(i.condition))
        assertEquals(MascotMood.SEARCHING, StatePresentation.mood(i.condition))
        assertEquals(listOf(TransferAction.PAUSE, TransferAction.CANCEL), i.rowActions)
    }

    @Test
    fun queuedWhileWifiOnlyHoldsItBack() {
        val i = TransferItem.build(row(TransferState.QUEUED), null, queuePosition = 2, restored = false, now = now, wifiGated = true)
        assertEquals(Condition.QUEUED_WIFI, i.condition)
        assertEquals("Waiting for Wi-Fi", label(i))
        assertEquals(LabelTone.SECONDARY, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.MUTED, StatePresentation.barFill(i.condition))
        assertEquals(0.42f, StatePresentation.barFraction(i), 0.001f)
        assertEquals("Waiting for Wi-Fi", title(i))
        assertEquals("Wi-Fi only is on. This continues when you connect to Wi-Fi.", stats(i))
        // Mascot, ring and plane stay as QUEUED.
        val queued = item(row(TransferState.QUEUED), position = 2).condition
        assertEquals(StatePresentation.mood(queued), StatePresentation.mood(i.condition))
        assertEquals(StatePresentation.ring(queued), StatePresentation.ring(i.condition))
        assertEquals(StatePresentation.plane(queued), StatePresentation.plane(i.condition))
        assertEquals(listOf(TransferAction.PAUSE, TransferAction.CANCEL), i.rowActions)
    }

    @Test
    fun wifiGatingFollowsTheSettingAndTheNetwork() {
        assertFalse(TransferItem.wifiGated(wifiOnly = false, NetworkState.Metered))
        assertFalse(TransferItem.wifiGated(wifiOnly = true, NetworkState.Unmetered))
        assertTrue(TransferItem.wifiGated(wifiOnly = true, NetworkState.Metered))
        assertTrue(TransferItem.wifiGated(wifiOnly = true, NetworkState.Offline))
        // Only QUEUED rows change; a row that is moving, paused or waiting keeps its condition.
        listOf(TransferState.TRANSFERRING, TransferState.PAUSED, TransferState.FAILED).forEach {
            val gated = TransferItem.build(row(it, error = ErrorCode.TIMEOUT), null, null, false, now, wifiGated = true)
            assertEquals(item(row(it, error = ErrorCode.TIMEOUT)).condition, gated.condition)
        }
        val offline = TransferItem.build(row(TransferState.RETRYING, error = ErrorCode.NETWORK_UNAVAILABLE), null, null, false, now, wifiGated = true)
        assertEquals(Condition.WAITING_NETWORK, offline.condition)
    }

    @Test
    fun paused() {
        val i = item(row(TransferState.PAUSED))
        assertEquals("Paused", label(i))
        assertEquals(BarFill.PAUSED, StatePresentation.barFill(i.condition))
        assertEquals("Paused", title(i))
        assertEquals("Progress saved: 42 of 100 pieces.", stats(i))
        assertEquals(MascotMood.SLEEPY, StatePresentation.mood(i.condition))
        assertEquals(RingStyle(RingStroke.DASHED, RingTone.STROKE, null), StatePresentation.ring(i.condition))
        assertEquals(listOf(TransferAction.RESUME, TransferAction.CANCEL), i.rowActions)
    }

    @Test
    fun failedUsesShortAndLongReasons() {
        val i = item(row(TransferState.FAILED, error = ErrorCode.RETRIES_EXHAUSTED))
        assertEquals("Failed: Gave up after 5 tries", label(i))
        assertEquals(LabelTone.DANGER, StatePresentation.labelTone(i.condition))
        assertEquals(BarFill.DANGER, StatePresentation.barFill(i.condition))
        assertEquals("Something went wrong", title(i))
        assertEquals("StableShare tried 5 times. Check the server, then tap Retry. Finished pieces are kept.", stats(i))
        assertEquals(PlaneSpot.RING_FAILED, StatePresentation.plane(i.condition))
        assertEquals(RingStyle(RingStroke.DASHED, RingTone.DANGER, null), StatePresentation.ring(i.condition))
        assertEquals(listOf(TransferAction.RETRY, TransferAction.CANCEL), i.rowActions)

        val noCode = item(row(TransferState.FAILED))
        assertEquals("Failed: Unexpected error", label(noCode))
    }

    @Test
    fun completed() {
        val up = item(row(TransferState.COMPLETED, bytesDone = 200 * mb))
        assertEquals("Uploaded", label(up))
        assertEquals("Downloaded", label(item(row(TransferState.COMPLETED, TransferType.DOWNLOAD, bytesDone = 200 * mb))))
        assertEquals(LabelTone.SUCCESS, StatePresentation.labelTone(up.condition))
        assertEquals(BarFill.SUCCESS, StatePresentation.barFill(up.condition))
        assertEquals(1f, StatePresentation.barFraction(up), 0f)
        assertEquals(100, up.percent)
        assertEquals("Completed", title(up))
        assertEquals("Verified. The SHA-256 checksum matches.", stats(up))
        assertEquals(MascotMood.HAPPY, StatePresentation.mood(up.condition))
        assertEquals(RingStyle(RingStroke.SOLID, RingTone.STROKE, null), StatePresentation.ring(up.condition))
        assertEquals(PlaneSpot.RING_TOP, StatePresentation.plane(up.condition))
        assertTrue("no Pause/Resume/Retry/Cancel", up.rowActions.isEmpty())
    }

    @Test
    fun cancelled() {
        val i = item(row(TransferState.CANCELLED))
        assertEquals("Cancelled", title(i))
        assertEquals("Partial data was deleted.", stats(i))
        assertEquals(MascotMood.CALM, StatePresentation.mood(i.condition))
        assertEquals(RingStyle(RingStroke.DOTTED, RingTone.SECONDARY_60, null), StatePresentation.ring(i.condition))
        assertEquals(PlaneSpot.HIDDEN, StatePresentation.plane(i.condition))
        assertEquals(42, i.percent) // the last value, shown at 60% alpha
        assertTrue(i.rowActions.isEmpty())
    }

    @Test
    fun rowActionsComeFromTheStateMachineWithCancelLast() {
        assertEquals(listOf(TransferAction.PAUSE, TransferAction.CANCEL), TransferItem.actionsFor(TransferState.QUEUED))
        assertEquals(listOf(TransferAction.PAUSE, TransferAction.CANCEL), TransferItem.actionsFor(TransferState.TRANSFERRING))
        assertEquals(listOf(TransferAction.PAUSE, TransferAction.CANCEL), TransferItem.actionsFor(TransferState.RETRYING))
        assertEquals(listOf(TransferAction.CANCEL), TransferItem.actionsFor(TransferState.VERIFYING))
    }

    @Test
    fun queuePositionsFollowClaimOrder() {
        val rows = listOf(
            row(TransferState.QUEUED).copy(id = "c", createdAt = 30),
            row(TransferState.PAUSED).copy(id = "p", createdAt = 5),
            row(TransferState.QUEUED).copy(id = "a", createdAt = 10),
            row(TransferState.QUEUED).copy(id = "b", createdAt = 10),
        )
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), TransferItem.queuePositions(rows))
    }
}
