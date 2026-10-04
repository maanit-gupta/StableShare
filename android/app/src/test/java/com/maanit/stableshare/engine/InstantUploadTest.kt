package com.maanit.stableshare.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import com.maanit.stableshare.engine.FakeTransferServer.Op
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Instant upload (plan 6.2b): the server already holds a COMPLETED file with the same SHA-256 and
 * size, so the create answers `instant` and the client sends no chunks, but still goes through
 * VERIFYING and compares the server's hash with its own before COMPLETED (rule 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstantUploadTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val harnesses = mutableListOf<EngineHarness>()

    @Before
    fun setUp() {
        dir = tmp.newFolder()
    }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    private fun TestScope.harness() =
        EngineHarness(ApplicationProvider.getApplicationContext(), dir, clock = { testScheduler.currentTime })
            .also { harnesses += it }

    private val chunk = EngineHarness.CHUNK
    private val size = 4 * chunk + 100

    /** Uploads [size] bytes once, normally, so the server holds a COMPLETED copy. */
    private suspend fun EngineHarness.uploadOriginal(): String {
        val (t, _) = upload(size)
        engine.run()
        assertEquals(COMPLETED, state(t.id))
        return t.id
    }

    @Test
    fun identicalFileCompletesWithoutSendingAnyChunk() = runTest {
        val h = harness()
        val original = h.uploadOriginal()
        val (t, bytes) = h.upload(size)
        h.engine.run()

        val row = h.row(t.id)
        assertEquals(COMPLETED, row.state)
        assertEquals(FileStore.sha256Hex(bytes), row.sha256)
        assertEquals(bytes.size.toLong(), row.bytesDone)
        assertEquals("no chunk requests", 0, h.server.count(Op.CHUNK, id = t.id))
        assertEquals(1, h.server.count(Op.COMPLETE, id = t.id))
        assertTrue(h.repo.getChunks(t.id).all { it.status == ChunkStatus.DONE })
        assertArrayEquals(bytes, h.server.assembled(t.id))
        assertTrue(h.server.sessions.getValue(t.id).instant)
        assertFalse(h.server.sessions.getValue(original).instant)

        val events = h.events(t.id)
        assertEquals(1, events.count { it.type == EventType.INSTANT_UPLOAD })
        val instant = events.indexOfFirst { it.type == EventType.INSTANT_UPLOAD }
        val verifying = events.indexOfFirst { it.toState == VERIFYING }
        val verified = events.indexOfFirst { it.type == EventType.VERIFIED }
        val completed = events.indexOfFirst { it.toState == COMPLETED }
        assertTrue("INSTANT_UPLOAD → VERIFYING → VERIFIED → COMPLETED", instant in 0 until verifying && verifying < verified && verified < completed)
        assertEquals(VERIFYING, events[completed].fromState)
        assertTrue(h.repo.observeHasInstantUpload(t.id).first())
        assertFalse(h.repo.observeHasInstantUpload(original).first())
        assertEquals(setOf(t.id), h.repo.observeInstantUploadIds().first())
    }

    @Test
    fun serverHashDifferentFromLocalFailsWithFileHashMismatch() = runTest {
        val h = harness()
        h.uploadOriginal()
        h.server.reportedSha = { "0".repeat(64) }
        val (t, _) = h.upload(size)
        h.engine.run()

        val row = h.row(t.id)
        assertEquals(FAILED, row.state)
        assertEquals(ErrorCode.FILE_HASH_MISMATCH, row.errorCode)
        assertEquals(0, h.server.count(Op.CHUNK, id = t.id))
        assertTrue(h.events(t.id).none { it.type == EventType.VERIFIED })
    }

    @Test
    fun cancelDuringInstantUploadEndsCancelled() = runTest {
        val h = harness()
        val original = h.uploadOriginal()
        val (t, _) = h.upload(size)
        val held = CompletableDeferred<Unit>()
        h.server.onRequest = { c -> if (c.op == Op.COMPLETE && c.id == t.id) { held.complete(Unit); awaitCancellation() } }
        val run = launch { h.engine.run() }
        held.await()
        assertEquals(VERIFYING, h.state(t.id))
        assertTrue(h.controller.cancel(t.id))
        run.join()

        assertEquals(CANCELLED, h.state(t.id))
        val events = h.events(t.id)
        assertTrue(events.none { it.type == EventType.VERIFIED || it.toState == COMPLETED })
        assertFalse(h.server.sessions.containsKey(t.id))
        assertTrue("the original upload is untouched", h.server.sessions.containsKey(original))

        h.repo.reconcileAfterProcessStart()
        h.engine.run()
        assertEquals(CANCELLED, h.state(t.id))
    }

    @Test
    fun nonIdenticalFileUploadsNormally() = runTest {
        val h = harness()
        h.uploadOriginal()
        val (t, bytes) = h.upload(size, seed = 7)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        (0 until 5).forEach { assertEquals("chunk $it sent once", 1, h.server.count(Op.CHUNK, it, id = t.id)) }
        assertArrayEquals(bytes, h.server.assembled(t.id))
        assertTrue(h.events(t.id).none { it.type == EventType.INSTANT_UPLOAD })
        assertFalse(h.server.sessions.getValue(t.id).instant)
    }

    @Test
    fun serverWithoutInstantUploadsStillUploadsIdenticalFiles() = runTest {
        val h = harness()
        h.uploadOriginal()
        h.server.instantUploads = false
        val (t, _) = h.upload(size)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(5, h.server.count(Op.CHUNK, id = t.id))
        assertTrue(h.events(t.id).none { it.type == EventType.INSTANT_UPLOAD })
    }

    /**
     * Process A dies (its only thread frozen) while the instant upload's `complete` is in flight:
     * the row is VERIFYING. Process B reconciles it to QUEUED, the repeated create still answers
     * instant, and B verifies and completes, still without a single chunk and with one INSTANT_UPLOAD.
     */
    @Test
    fun processDeathAfterInstantDetectionResumesToVerification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, File(dir, "transfers.db").path).build()
        val server = FakeTransferServer()
        val a = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db).also { harnesses += it }
        withContext(Dispatchers.Default) { withTimeout(20_000) { a.uploadOriginal() } }
        val (t, _) = a.upload(size)

        val reached = CompletableDeferred<Unit>()
        server.onRequest = { c -> if (c.op == Op.COMPLETE && c.id == t.id) { reached.complete(Unit); awaitCancellation() } }
        val executor = Executors.newSingleThreadExecutor()
        val processA = CoroutineScope(executor.asCoroutineDispatcher() + Job())
        processA.launch { a.engine.run() }
        withTimeout(10_000) { reached.await() }
        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { frozen.countDown(); release.await() }
        assertTrue(frozen.await(10, TimeUnit.SECONDS))
        assertEquals(VERIFYING, a.state(t.id))

        server.onRequest = {}
        val b = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db)
        withContext(Dispatchers.Default) { withTimeout(20_000) { b.engine.run() } }

        assertEquals(COMPLETED, b.state(t.id))
        assertEquals(0, server.count(Op.CHUNK, id = t.id))
        assertEquals(2, server.count(Op.CREATE, id = t.id))
        val events = b.events(t.id)
        assertEquals("logged once across both processes", 1, events.count { it.type == EventType.INSTANT_UPLOAD })
        assertTrue(events.any { it.message == "Reconciled after process start: VERIFYING → QUEUED" })
        val changes = events.filter { it.type == EventType.STATE_CHANGE }.map { it.fromState to it.toState }
        assertEquals(VERIFYING to COMPLETED, changes.last())
        assertEquals(TRANSFERRING to VERIFYING, changes[changes.size - 2])

        processA.cancel()
        release.countDown()
        withTimeout(10_000) { processA.coroutineContext.job.join() }
        executor.shutdown()
        assertEquals("the dead process never writes again", COMPLETED, b.state(t.id))
    }
}
