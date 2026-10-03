package com.maanit.stableshare.ui.model

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.db.TransferEventEntity
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.engine.EngineHarness
import com.maanit.stableshare.engine.FakeTransferServer
import com.maanit.stableshare.engine.FakeTransferServer.Fault
import com.maanit.stableshare.engine.FakeTransferServer.Op
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI-SPEC §8. Events come from the real repository and engine, so a change to a stored message
 * format that the mapper parses breaks this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivityMapperTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val closers = mutableListOf<() -> Unit>()

    @After
    fun tearDown() = closers.forEach { it() }

    private fun texts(events: List<TransferEventEntity>) =
        ActivityMapper.map(events, maxTries = 5).map { it.message.resolve(context.resources) }

    @Test
    fun engineRunWithARetryCollapsesPiecesAndShowsTheRetry() = runTest {
        val h = EngineHarness(context, tmp.newFolder(), clock = { testScheduler.currentTime })
        closers += h::close
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 2 && c.attempt == 1) Fault.Before(FakeTransferServer.http(503, "INJECTED_FAULT")) else null }
        val (t, _) = h.upload(6 * EngineHarness.CHUNK)
        h.engine.run()
        assertEquals(TransferState.COMPLETED, h.state(t.id))

        val events = h.events(t.id)
        val shown = texts(events).asReversed() // chronological for readability
        val expected = listOf(
            "Added to the queue",
            "Started moving",
            "Pieces 1–2 done",
            "Piece 3 failed: Server error",
            "Hit a problem: Server error",
            "Trying again in ",
            "Started moving",
            "Pieces 3–6 done",
            "Checking the finished file",
            "Checksum verified",
            "Completed",
        )
        // Stored INFO lines (e.g. the server's received-chunk report) may sit in between.
        var at = 0
        for (line in shown) if (at < expected.size && line.startsWith(expected[at])) at++
        assertEquals("all expected entries in order; got $shown", expected.size, at)
        val retry = shown.single { it.startsWith("Trying again in ") }
        assertTrue(retry, Regex("""Trying again in \d+ s \(attempt 1 of 5\)""").matches(retry))
        assertEquals(1, ActivityMapper.latestRetryAttempt(events))
        assertEquals(h.row(t.id).sha256, ActivityMapper.verifiedSha(events))

        val dots = ActivityMapper.map(events, 5)
        assertEquals(ActivityDot.DANGER, dots.single { it.message.resolve(context.resources).startsWith("Piece 3 failed") }.dot)
        assertEquals(ActivityDot.YELLOW, dots.single { it.message.resolve(context.resources).startsWith("Trying again") }.dot)
        assertEquals(ActivityDot.STROKE, dots.first().dot)
        assertTrue("newest first", dots.zipWithNext().all { (a, b) -> a.timestamp >= b.timestamp })
    }

    @Test
    fun transitionsWithoutCopyAreHiddenAndRestartIsShown() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        closers += db::close
        var now = 0L
        val repo = TransferRepository(db) { ++now }
        val id = repo.createUpload("a.bin", 10, null, "file:///a.bin", 5).id
        repo.transition(id, TransferState.TRANSFERRING)
        repo.reconcileAfterProcessStart() // TRANSFERRING → QUEUED (hidden) + restart INFO
        repo.transition(id, TransferState.PAUSED)
        repo.transition(id, TransferState.QUEUED)
        repo.transition(id, TransferState.TRANSFERRING)
        repo.transition(id, TransferState.FAILED, ErrorCode.RETRIES_EXHAUSTED, "Chunk 0 failed 5 times")
        repo.transition(id, TransferState.QUEUED)
        repo.transition(id, TransferState.TRANSFERRING)
        repo.transition(id, TransferState.RETRYING, ErrorCode.NETWORK_UNAVAILABLE, "offline", nextRetryAt = null)
        repo.promoteWaitingForNetwork() // RETRYING → QUEUED (hidden)
        repo.transition(id, TransferState.TRANSFERRING)
        repo.logEvent(id, EventType.CHUNK_CONFIRMED_AFTER_LOST_RESPONSE, "Chunk 1: response lost, but GET status lists it; not resending", chunkIndex = 1)
        repo.logEvent(id, EventType.ERROR, "[DISK_FULL] write failed")
        repo.logEvent(id, EventType.INFO, "Cleanup: server session deleted")
        repo.transition(id, TransferState.CANCELLED)

        assertEquals(
            listOf(
                "Added to the queue",
                "Started moving",
                "Restored after the app restarted",
                "Paused",
                "Resumed",
                "Started moving",
                "Stopped: Gave up after 5 tries",
                "Retry requested",
                "Started moving",
                "Hit a problem: No connection",
                "Started moving",
                "Piece 2 confirmed by the server after a lost reply",
                "Your phone ran out of space. Free some up, then tap Retry.",
                "Cleanup: server session deleted",
                "Cancelled",
            ),
            texts(repo.getEvents(id)).asReversed(),
        )
    }

    @Test
    fun singleChunkRunsUseTheSingularForm() {
        fun done(id: Long, index: Int) = TransferEventEntity(id, "t", id, EventType.CHUNK_DONE, null, null, index, "Chunk $index done")
        val info = TransferEventEntity(3, "t", 3, EventType.INFO, null, null, null, "note")
        assertEquals(listOf("Piece 4 done", "note", "Piece 1 done"), texts(listOf(done(1, 0), info, done(4, 3))))
    }
}
