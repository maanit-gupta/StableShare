package com.maanit.stableshare.engine

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import com.maanit.stableshare.engine.FakeTransferServer.Fault
import com.maanit.stableshare.engine.FakeTransferServer.Op
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Upload pipeline driven through the real coordinator (TransferEngine) against the fake server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadPipelineTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val harnesses = mutableListOf<EngineHarness>()

    @Before
    fun setUp() {
        dir = tmp.newFolder()
    }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    private fun TestScope.harness(configure: EngineHarness.() -> Unit = {}) =
        EngineHarness(ApplicationProvider.getApplicationContext(), dir, clock = { testScheduler.currentTime })
            .also { harnesses += it; it.configure() }

    private val chunk = EngineHarness.CHUNK
    private val serverError = FakeTransferServer.http(503, "INJECTED_FAULT")

    @Test
    fun happyPathCompletesWithServerVerifiedHash() = runTest {
        val h = harness()
        val (t, bytes) = h.upload(5 * chunk + 100)
        h.engine.run()

        val row = h.row(t.id)
        assertEquals(COMPLETED, row.state)
        assertEquals(FileStore.sha256Hex(bytes), row.sha256)
        assertEquals(bytes.size.toLong(), row.bytesDone)
        assertArrayEquals(bytes, h.server.assembled(t.id))
        assertEquals(FileStore.sha256Hex(bytes), h.server.sessions.getValue(t.id).completedSha)
        (0 until 6).forEach { assertEquals("chunk $it sent once", 1, h.server.count(Op.CHUNK, it)) }
        assertEquals(1, h.server.count(Op.COMPLETE))
        assertTrue(h.repo.getChunks(t.id).all { it.status == ChunkStatus.DONE })
        // VERIFIED is logged before the COMPLETED transition, which only follows VERIFYING (rule 3).
        val events = h.events(t.id)
        val verified = events.indexOfFirst { it.type == EventType.VERIFIED }
        val completed = events.indexOfFirst { it.toState == COMPLETED }
        assertTrue(verified in 0 until completed)
        assertEquals(VERIFYING, events[completed].fromState)
        assertTrue(h.ensureRunningCalls.isNotEmpty())
    }

    @Test
    fun lostChunkResponseIsConfirmedByStatusWithoutResending() = runTest {
        val h = harness()
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 2 && c.attempt == 1) Fault.After(SocketTimeoutException("read timed out")) else null }
        val (t, _) = h.upload(4 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals("no duplicate PUT", 1, h.server.count(Op.CHUNK, 2))
        assertEquals(0, h.repo.getChunks(t.id)[2].attempts)
        val confirmed = h.events(t.id).single { it.type == EventType.CHUNK_CONFIRMED_AFTER_LOST_RESPONSE }
        assertEquals(2, confirmed.chunkIndex)
    }

    @Test
    fun lostCompleteResponseIsConfirmedByStatus() = runTest {
        val h = harness()
        h.server.fault = { c -> if (c.op == Op.COMPLETE && c.attempt == 1) Fault.After(SocketTimeoutException("lost")) else null }
        val (t, _) = h.upload(2 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(1, h.server.count(Op.COMPLETE))
        assertTrue(h.events(t.id).any { it.type == EventType.VERIFIED })
    }

    @Test
    fun twoServerErrorsThenSuccess() = runTest {
        val h = harness()
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 1 && c.attempt <= 2) Fault.Before(serverError) else null }
        val (t, _) = h.upload(3 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(3, h.server.count(Op.CHUNK, 1))
        assertEquals(2, h.repo.getChunks(t.id)[1].attempts)
        assertEquals(2, h.eventTypes(t.id).count { it == EventType.RETRY_SCHEDULED })
        assertEquals(2, h.events(t.id).count { it.toState == RETRYING })
    }

    @Test
    fun serverErrorsForeverExhaustRetriesKeepingProgressAndManualRetrySendsOnlyTheRest() = runTest {
        val h = harness()
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 2) Fault.Before(serverError) else null }
        val (t, bytes) = h.upload(4 * chunk)
        h.engine.run()

        val failed = h.row(t.id)
        assertEquals(FAILED, failed.state)
        assertEquals(ErrorCode.RETRIES_EXHAUSTED, failed.errorCode)
        assertEquals(5, h.server.count(Op.CHUNK, 2))
        assertEquals(2L * chunk, failed.bytesDone)
        assertEquals(listOf(ChunkStatus.DONE, ChunkStatus.DONE), h.repo.getChunks(t.id).take(2).map { it.status })

        h.server.fault = { null }
        assertTrue(h.controller.retry(t.id))
        assertEquals(0, h.row(t.id).attemptCount)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals("chunk 0 not resent", 1, h.server.count(Op.CHUNK, 0))
        assertEquals("chunk 1 not resent", 1, h.server.count(Op.CHUNK, 1))
        assertEquals(6, h.server.count(Op.CHUNK, 2))
        assertEquals(1, h.server.count(Op.CHUNK, 3))
        assertArrayEquals(bytes, h.server.assembled(t.id))
    }

    @Test
    fun autoRetryOffFailsOnTheFirstRetryableError() = runTest {
        val h = harness { settings.value = settings.value.copy(autoRetryEnabled = false) }
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 1) Fault.Before(serverError) else null }
        val (t, _) = h.upload(3 * chunk)
        h.engine.run()

        assertEquals(FAILED, h.state(t.id))
        assertEquals(ErrorCode.SERVER_ERROR, h.row(t.id).errorCode)
        assertEquals(1, h.server.count(Op.CHUNK, 1))
    }

    @Test
    fun networkLossWaitsWithoutConsumingAttemptsAndResumesWhenOnline() = runTest {
        val h = harness()
        h.server.fault = { c ->
            if (c.op == Op.CHUNK && c.index == 1 && c.attempt == 1) {
                h.net.online = false
                Fault.Before(ConnectException("Network is unreachable"))
            } else {
                null
            }
        }
        val (t, _) = h.upload(3 * chunk)
        h.engine.run() // ends: the only transfer waits for the network

        val waiting = h.row(t.id)
        assertEquals(RETRYING, waiting.state)
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, waiting.errorCode)
        assertNull(waiting.nextRetryAt)
        assertEquals(0, waiting.attemptCount)
        assertEquals(0, h.repo.getChunks(t.id)[1].attempts)
        assertEquals(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true), h.wakeups.last())

        // The process-level trigger: an offline → online edge promotes waiting rows and starts the coordinator.
        val bootstrap = EngineBootstrap(h.repo, h.settings, h.net, h.scheduler, backgroundScope)
        bootstrap.start()
        val before = h.ensureRunningCalls.size
        h.net.online = true
        h.awaitRow(t.id) { it.state == com.maanit.stableshare.domain.TransferState.QUEUED }
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertTrue(h.ensureRunningCalls.size > before)
        assertEquals(2, h.server.count(Op.CHUNK, 1))
        assertEquals(0, h.row(t.id).attemptCount)
    }

    @Test
    fun sessionLostMidUploadIsRecreatedOnce() = runTest {
        val h = harness()
        h.server.onRequest = { c -> if (c.op == Op.CHUNK && c.index == 2 && c.attempt == 1) h.server.sessions.remove(c.id) }
        val (t, bytes) = h.upload(4 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(2, h.server.count(Op.CREATE))
        assertEquals("server lost chunk 0, so it is sent again", 2, h.server.count(Op.CHUNK, 0))
        assertArrayEquals(bytes, h.server.assembled(t.id))
        assertTrue(h.events(t.id).any { it.message.contains("recreating it once") })
    }

    @Test
    fun sessionLostTwiceFails() = runTest {
        val h = harness()
        h.server.onRequest = { c -> if (c.op == Op.CHUNK && c.index == 1) h.server.sessions.remove(c.id) }
        val (t, _) = h.upload(3 * chunk)
        h.engine.run()

        assertEquals(FAILED, h.state(t.id))
        assertEquals(ErrorCode.SESSION_NOT_FOUND, h.row(t.id).errorCode)
        assertEquals(2, h.server.count(Op.CREATE))
    }

    @Test
    fun missingChunksOnCompleteAreResent() = runTest {
        val h = harness()
        h.server.onRequest = { c -> if (c.op == Op.COMPLETE && c.attempt == 1) h.server.sessions.getValue(c.id).chunks.remove(1) }
        val (t, bytes) = h.upload(3 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(2, h.server.count(Op.CHUNK, 1))
        assertEquals(1, h.server.count(Op.CHUNK, 0))
        assertEquals(2, h.server.count(Op.COMPLETE))
        assertArrayEquals(bytes, h.server.assembled(t.id))
    }

    @Test
    fun sourceChangedMidUploadFails() = runTest {
        val h = harness()
        lateinit var source: File
        h.server.onRequest = { c -> if (c.op == Op.CHUNK && c.index == 1 && c.attempt == 1) source.appendBytes(ByteArray(10)) }
        val (t, _) = h.upload(3 * chunk)
        source = File(Uri.parse(t.localUri).path!!)
        h.engine.run()

        assertEquals(FAILED, h.state(t.id))
        assertEquals(ErrorCode.SOURCE_CHANGED, h.row(t.id).errorCode)
        assertEquals(0, h.server.count(Op.CHUNK, 2))
    }

    @Test
    fun sourceDeletedMidUploadFails() = runTest {
        val h = harness()
        lateinit var source: File
        h.server.onRequest = { c -> if (c.op == Op.CHUNK && c.index == 1 && c.attempt == 1) source.delete() }
        val (t, _) = h.upload(3 * chunk)
        source = File(Uri.parse(t.localUri).path!!)
        h.engine.run()

        assertEquals(FAILED, h.state(t.id))
        assertEquals(ErrorCode.SOURCE_MISSING, h.row(t.id).errorCode)
    }

    @Test
    fun zeroByteUploadCompletesWithoutChunkRequests() = runTest {
        val h = harness()
        val (t, _) = h.upload(0)
        h.engine.run()

        val row = h.row(t.id)
        assertEquals(COMPLETED, row.state)
        assertEquals(FileStore.sha256Hex(ByteArray(0)), row.sha256)
        assertEquals(0, h.server.count(Op.CHUNK))
        assertEquals(1, h.server.count(Op.CREATE))
        assertEquals(1, h.server.count(Op.COMPLETE))
    }
}
