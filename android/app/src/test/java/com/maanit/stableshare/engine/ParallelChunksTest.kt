package com.maanit.stableshare.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import com.maanit.stableshare.engine.FakeTransferServer.Fault
import com.maanit.stableshare.engine.FakeTransferServer.Op
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Parallel chunks within one transfer (DESIGN.md §6.4), N = 2 or 4, through the real coordinator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParallelChunksTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val harnesses = mutableListOf<EngineHarness>()

    @Before
    fun setUp() {
        dir = tmp.newFolder()
    }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    private val chunk = EngineHarness.CHUNK
    private val serverError = FakeTransferServer.http(503, "INJECTED_FAULT")

    private fun settings(n: Int) = Settings(maxConcurrent = 2, uploadChunkSizeBytes = chunk, parallelChunks = n)

    private fun TestScope.harness(n: Int) =
        EngineHarness(ApplicationProvider.getApplicationContext(), dir, clock = { testScheduler.currentTime }, settings = settings(n))
            .also { harnesses += it }

    private suspend fun EngineHarness.done(id: String): Set<Int> =
        repo.getChunks(id).filter { it.status == ChunkStatus.DONE }.map { it.index }.toSet()

    /** Every CHUNK/RANGE request takes 100 ms (virtual), so up to N of them overlap. */
    private fun EngineHarness.slowChunks() {
        server.onRequest = { c -> if (c.op == Op.CHUNK || c.op == Op.RANGE) delay(100) }
    }

    /**
     * Like [slowChunks], but chunk requests first wait (up to 10 s of real time) until [n] are in
     * flight together, then the gate stays open. Workers read their chunk on real I/O threads while
     * the 100 ms delay is virtual, so without the gate a slow read under full-suite load let one
     * request finish before the last worker started, and the peak came out as n − 1.
     */
    private fun EngineHarness.overlappingChunks(n: Int) {
        val open = java.util.concurrent.atomic.AtomicBoolean(false)
        server.onRequest = { c ->
            if (c.op == Op.CHUNK || c.op == Op.RANGE) {
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!open.get() && server.activeChunkRequests.get() < n && System.nanoTime() < deadline) {
                    withContext(Dispatchers.IO) { Thread.sleep(1) }
                }
                open.set(true)
                delay(100)
            }
        }
    }

    /** Holds the first attempt of every chunk request with index ≥ [from] until it is cancelled. */
    private fun EngineHarness.holdFrom(op: Op, from: Int) {
        server.onRequest = { c -> if (c.op == op && (c.index ?: -1) >= from && c.attempt == 1) awaitCancellation() }
    }

    // ---- bounded parallelism and memory ----

    @Test
    fun uploadKeepsAtMostNChunkRequestsAndBuffersInFlight() = runTest {
        for (n in listOf(2, 4)) {
            val h = harness(n)
            val meter = BufferMeter(h)
            h.overlappingChunks(n)
            val (t, bytes) = h.upload(9 * chunk + 100, seed = n)
            h.engine.run()

            assertEquals(COMPLETED, h.state(t.id))
            assertArrayEquals(bytes, h.server.assembled(t.id))
            assertEquals("N = $n: requests in flight", n, h.server.peakChunkRequests.get())
            assertTrue("N = $n: peak buffers ${meter.peak.get()}", meter.peak.get() in 1..n)
            (0 until 10).forEach { assertEquals("chunk $it sent once", 1, h.server.count(Op.CHUNK, it)) }
            h.close()
            harnesses -= h
        }
    }

    @Test
    fun downloadKeepsAtMostNChunkRequestsAndBuffersInFlight() = runTest {
        for (n in listOf(2, 4)) {
            val h = harness(n)
            val meter = BufferMeter(h)
            h.overlappingChunks(n)
            val (t, bytes) = h.download(9 * chunk + 100, seed = n)
            h.engine.run()

            assertEquals(COMPLETED, h.state(t.id))
            assertArrayEquals(bytes, h.localFile(t.id).readBytes())
            assertEquals("N = $n: requests in flight", n, h.server.peakChunkRequests.get())
            assertTrue("N = $n: peak buffers ${meter.peak.get()}", meter.peak.get() in 1..n)
            (0 until 10).forEach { assertEquals("chunk $it fetched once", 1, h.server.count(Op.RANGE, it)) }
            h.close()
            harnesses -= h
        }
    }

    @Test
    fun nOneStillSendsOneChunkAtATime() = runTest {
        val h = harness(1)
        h.slowChunks()
        val (t, _) = h.upload(5 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(1, h.server.peakChunkRequests.get())
    }

    // ---- pause, cancel ----

    @Test
    fun pauseWithNInFlightMarksNoneDoneAndResumeSendsOnlyTheMissingChunks() = runTest {
        val h = harness(4)
        val (t, bytes) = h.upload(8 * chunk)
        h.holdFrom(Op.CHUNK, 4)
        val run = launch { h.engine.run() }
        h.server.inFlight.first { it == 4 }
        assertTrue(h.controller.pause(t.id))
        run.join()

        assertEquals(PAUSED, h.state(t.id))
        assertEquals(setOf(0, 1, 2, 3), h.done(t.id))
        assertEquals(4L * chunk, h.row(t.id).bytesDone)

        h.server.onRequest = {}
        assertTrue(h.controller.resume(t.id))
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertArrayEquals(bytes, h.server.assembled(t.id))
        (0 until 4).forEach { assertEquals("chunk $it", 1, h.server.count(Op.CHUNK, it)) }
        (4 until 8).forEach { assertEquals("chunk $it", 2, h.server.count(Op.CHUNK, it)) }
    }

    @Test
    fun cancelWithSeveralDownloadChunksInFlightIsFinal() = runTest {
        val h = harness(4)
        val (t, _) = h.download(8 * chunk)
        val part = h.localFile(t.id)
        h.holdFrom(Op.RANGE, 2)
        val run = launch { h.engine.run() }
        h.server.inFlight.first { it == 4 }
        assertTrue(h.controller.cancel(t.id))
        run.join()

        assertEquals(CANCELLED, h.state(t.id))
        assertEquals(setOf(0, 1), h.done(t.id))
        val events = h.events(t.id)
        val cancelledAt = events.indexOfFirst { it.toState == CANCELLED }
        assertTrue(events.drop(cancelledAt).none { it.type == EventType.CHUNK_DONE })
        assertFalse("part file deleted", part.exists())

        h.server.onRequest = {}
        h.repo.reconcileAfterProcessStart()
        h.engine.run()
        assertEquals(CANCELLED, h.state(t.id))
    }

    @Test
    fun cancelWithSeveralUploadChunksInFlightDeletesTheSession() = runTest {
        val h = harness(2)
        val (t, _) = h.upload(6 * chunk)
        h.holdFrom(Op.CHUNK, 0)
        val run = launch { h.engine.run() }
        h.server.inFlight.first { it == 2 }
        assertTrue(h.controller.cancel(t.id))
        run.join()

        assertEquals(CANCELLED, h.state(t.id))
        assertTrue(h.done(t.id).isEmpty())
        assertEquals(1, h.server.count(Op.DELETE))
        assertFalse(h.server.sessions.containsKey(t.id))
    }

    // ---- failures ----

    @Test
    fun aChunkExhaustingItsRetriesCancelsSiblingsAndFailsWithDoneChunksIntact() = runTest {
        val h = harness(4)
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 2) Fault.Before(serverError) else null }
        val (t, bytes) = h.upload(8 * chunk)
        h.server.onRequest = { c ->
            // Chunk 2 starts failing once 0, 1, 3 and 4 are DONE; 5–7 are then in flight until cancelled.
            if (c.op == Op.CHUNK && c.index == 2 && c.attempt == 1) h.awaitRow(t.id) { it.bytesDone == 4L * chunk }
            if (c.op == Op.CHUNK && c.index!! >= 5 && c.attempt == 1) awaitCancellation()
        }
        h.engine.run()

        val row = h.row(t.id)
        assertEquals(FAILED, row.state)
        assertEquals(ErrorCode.RETRIES_EXHAUSTED, row.errorCode)
        assertEquals(setOf(0, 1, 3, 4), h.done(t.id))
        assertEquals(5, h.server.count(Op.CHUNK, 2))
        assertEquals(5, h.repo.getChunks(t.id)[2].attempts)
        assertEquals(0, h.server.activeChunkRequests.get())
        val events = h.events(t.id)
        assertEquals(4, events.count { it.type == EventType.RETRY_SCHEDULED && it.chunkIndex == 2 })
        assertTrue("per-chunk backoff stays TRANSFERRING", events.none { it.toState == RETRYING })
        assertEquals(1, events.count { it.toState == FAILED })

        h.server.fault = { null }
        h.server.onRequest = {}
        assertTrue(h.controller.retry(t.id))
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertArrayEquals(bytes, h.server.assembled(t.id))
        listOf(0, 1, 3, 4).forEach { assertEquals("chunk $it not resent", 1, h.server.count(Op.CHUNK, it)) }
        assertEquals(6, h.server.count(Op.CHUNK, 2))
    }

    @Test
    fun aFatalChunkErrorFailsOnceWhileSiblingsAreMoving() = runTest {
        val h = harness(4)
        h.server.fault = { c -> if (c.op == Op.RANGE && c.index == 1) Fault.Before(FakeTransferServer.http(404, "FILE_NOT_FOUND")) else null }
        val (t, _) = h.download(6 * chunk)
        h.server.onRequest = { c ->
            if (c.op == Op.RANGE && c.index == 1) h.awaitRow(t.id) { it.bytesDone == chunk.toLong() }
            if (c.op == Op.RANGE && c.index!! >= 2 && c.attempt == 1) awaitCancellation()
        }
        h.engine.run()

        assertEquals(FAILED, h.state(t.id))
        assertEquals(ErrorCode.REMOTE_FILE_CHANGED, h.row(t.id).errorCode)
        assertEquals(setOf(0), h.done(t.id))
        assertEquals(1, h.events(t.id).count { it.toState == FAILED })
    }

    @Test
    fun networkLossWithSeveralWorkersMakesExactlyOneRetryingTransition() = runTest {
        val h = harness(4)
        val (t, bytes) = h.upload(8 * chunk)
        h.holdFrom(Op.CHUNK, 0)
        val run = launch { h.engine.run() }
        h.server.inFlight.first { it == 4 }
        h.net.online = false
        run.join() // the guard cancels the held calls after its debounce; the job ends waiting

        val waiting = h.row(t.id)
        assertEquals(RETRYING, waiting.state)
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, waiting.errorCode)
        assertEquals(0, waiting.attemptCount)
        assertEquals(1, h.events(t.id).count { it.toState == RETRYING })
        assertTrue(h.done(t.id).isEmpty())

        h.server.onRequest = {}
        h.net.online = true
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertArrayEquals(bytes, h.server.assembled(t.id))
    }

    @Test
    fun aCorruptDownloadChunkWithFourWorkersRefetchesOnlyThatChunk() = runTest {
        val h = harness(4)
        h.server.fault = { c -> if (c.op == Op.RANGE && c.index == 3 && c.attempt == 1) Fault.Corrupt else null }
        val (t, bytes) = h.download(8 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertArrayEquals(bytes, h.localFile(t.id).readBytes())
        assertEquals(listOf(1, 1, 1, 2, 1, 1, 1, 1), (0 until 8).map { h.server.count(Op.RANGE, it) })
        assertEquals(1, h.repo.getChunks(t.id)[3].attempts)
    }

    @Test
    fun aCorruptChunkOnDiskIsReverifiedAcrossTheLastTwoTimesNChunks() = runTest {
        val h = harness(4)
        val (t, bytes) = h.download(12 * chunk)
        h.holdFrom(Op.RANGE, 10)
        val run = launch { h.engine.run() }
        h.server.inFlight.first { it == 2 }
        h.awaitRow(t.id) { it.bytesDone == 10L * chunk }
        h.controller.pause(t.id)
        run.join()
        assertEquals((0 until 10).toSet(), h.done(t.id))
        // The 2 × 4 = 8 highest DONE chunks are 2–9 (with N = 1 only 8 and 9 would be re-hashed).
        h.corruptOnDisk(t.id, 2)

        h.server.onRequest = {}
        h.controller.resume(t.id)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertArrayEquals(bytes, h.localFile(t.id).readBytes())
        assertEquals("caught by the startup re-check, not the full-file check", 2, h.server.count(Op.RANGE, 2))
        assertTrue(h.events(t.id).none { it.toState == RETRYING })
    }

    @Test
    fun aLostResponseOnOneOfSeveralChunksIsConfirmedWithoutResending() = runTest {
        val h = harness(4)
        h.slowChunks()
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 2 && c.attempt == 1) Fault.After(SocketTimeoutException("read timed out")) else null }
        val (t, bytes) = h.upload(8 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertArrayEquals(bytes, h.server.assembled(t.id))
        assertEquals(1, h.server.count(Op.CHUNK, 2))
        assertEquals(0, h.repo.getChunks(t.id)[2].attempts)
        assertEquals(2, h.events(t.id).single { it.type == EventType.CHUNK_CONFIRMED_AFTER_LOST_RESPONSE }.chunkIndex)
    }

    @Test
    fun aRetryableChunkErrorBacksOffInPlaceWhileSiblingsFinish() = runTest {
        val h = harness(2)
        h.server.fault = { c ->
            if (c.op == Op.CHUNK && c.index == 1 && c.attempt <= 2) Fault.Before(ConnectException("Connection reset")) else null
        }
        val (t, bytes) = h.upload(6 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertArrayEquals(bytes, h.server.assembled(t.id))
        assertEquals(3, h.server.count(Op.CHUNK, 1))
        assertEquals(2, h.repo.getChunks(t.id)[1].attempts)
        val changes = h.events(t.id).filter { it.type == EventType.STATE_CHANGE }.map { it.fromState to it.toState }
        assertTrue(changes.none { it.second == RETRYING })
        assertEquals(TRANSFERRING to VERIFYING, changes[changes.size - 2])
    }

    @Test
    fun zeroByteFilesCompleteWithSeveralWorkers() = runTest {
        val h = harness(4)
        val (up, _) = h.upload(0)
        val (down, _) = h.download(0, fileId = "empty")
        h.engine.run()

        assertEquals(COMPLETED, h.state(up.id))
        assertEquals(COMPLETED, h.state(down.id))
        assertEquals(FileStore.sha256Hex(ByteArray(0)), h.row(up.id).sha256)
        assertEquals(0L, h.localFile(down.id).length())
    }

    // ---- process death ----

    @Test
    fun processDeathWithSeveralUploadChunksInFlightResumesFromTheDoneSet() = processDeath(Op.CHUNK) { it.upload(8 * chunk).first.id }

    @Test
    fun processDeathWithSeveralDownloadChunksInFlightResumesFromTheDoneSet() = processDeath(Op.RANGE) { it.download(8 * chunk).first.id }

    /** As TransferEngineTest.processDeath, with N = 4 and chunks 4–7 all in flight when process A dies. */
    private fun processDeath(op: Op, create: suspend (EngineHarness) -> String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, File(dir, "transfers.db").path).build()
        val server = FakeTransferServer()
        val a = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db, settings = settings(4))
            .also { harnesses += it }
        val id = create(a)

        a.holdFrom(op, 4)
        val executor = Executors.newSingleThreadExecutor()
        val processA = CoroutineScope(executor.asCoroutineDispatcher() + Job())
        processA.launch { a.engine.run() }
        withTimeout(10_000) { server.inFlight.first { it == 4 } }
        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { frozen.countDown(); release.await() }
        assertTrue(frozen.await(10, TimeUnit.SECONDS))

        assertEquals(TRANSFERRING, a.state(id))
        assertEquals((0 until 4).toSet(), a.done(id))

        server.onRequest = {}
        val b = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db, settings = settings(4))
        withContext(Dispatchers.Default) { withTimeout(20_000) { b.engine.run() } }

        assertEquals(COMPLETED, b.state(id))
        (0 until 4).forEach { assertEquals("chunk $it moved once", 1, server.count(op, it)) }
        (4 until 8).forEach { assertEquals("in-flight chunk $it redone once", 2, server.count(op, it)) }
        assertEquals(1, b.events(id).count { it.toState == COMPLETED })

        processA.cancel()
        release.countDown()
        withTimeout(10_000) { processA.coroutineContext.job.join() }
        executor.shutdown()
        assertEquals("the dead process never writes again", COMPLETED, b.state(id))
    }
}
