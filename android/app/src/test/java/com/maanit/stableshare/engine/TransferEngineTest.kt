package com.maanit.stableshare.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import com.maanit.stableshare.engine.FakeTransferServer.Fault
import com.maanit.stableshare.engine.FakeTransferServer.Op
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Coordinator behaviour: pause/resume, cancel, system stop, process death, concurrency, wake-ups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferEngineTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val harnesses = mutableListOf<EngineHarness>()

    @Before
    fun setUp() {
        dir = tmp.newFolder()
    }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    private fun TestScope.harness(sleep: (suspend (Long) -> Unit)? = null) =
        EngineHarness(ApplicationProvider.getApplicationContext(), dir, clock = { testScheduler.currentTime }, sleep = sleep)
            .also { harnesses += it }

    private val chunk = EngineHarness.CHUNK
    private val active = setOf(TRANSFERRING, RETRYING, VERIFYING)

    private suspend fun EngineHarness.stateChanges(id: String) =
        events(id).filter { it.type == EventType.STATE_CHANGE }.map { it.fromState to it.toState }

    // ---- pause / resume ----

    @Test
    fun pauseAndResumeUploadNeverResendsEarlierChunks() = runTest {
        val h = harness()
        val (t, bytes) = h.upload(6 * chunk)
        val held = h.holdAt(Op.CHUNK, 3)
        val run = launch { h.engine.run() }
        held.await()
        assertTrue(h.controller.pause(t.id))
        run.join() // the coordinator cancelled the job and, with nothing runnable, exited
        assertEquals(PAUSED, h.state(t.id))
        assertEquals(3L * chunk, h.row(t.id).bytesDone)
        assertEquals(ChunkStatus.PENDING, h.repo.getChunks(t.id)[3].status)

        h.server.onRequest = {}
        assertTrue(h.controller.resume(t.id))
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        (0 until 3).forEach { assertEquals("chunk $it", 1, h.server.count(Op.CHUNK, it)) }
        assertEquals(2, h.server.count(Op.CHUNK, 3))
        assertArrayEquals(bytes, h.server.assembled(t.id))
    }

    @Test
    fun pauseAllPausesQueuedAndRunningTransfersAndTheLoopExits() = runTest {
        val h = harness() // maxConcurrent = 2, so the third upload stays QUEUED
        val ids = (1..3).map { h.upload(3 * chunk, seed = it).first.id }
        val held = h.holdAt(Op.CHUNK, 1)
        val run = launch { h.engine.run() }
        held.await()
        assertEquals(3, h.controller.pauseAll())
        run.join()
        ids.forEach { assertEquals(it, PAUSED, h.state(it)) }
        assertEquals("nothing left to pause", 0, h.controller.pauseAll())

        val userBefore = h.userInitiatedCalls.size
        assertTrue(h.controller.resume(ids[0]))
        assertEquals("Resume is a user-initiated start", userBefore + 1, h.userInitiatedCalls.size)
    }

    @Test
    fun pauseAndResumeDownloadNeverRefetchesEarlierChunks() = runTest {
        val h = harness()
        val (t, bytes) = h.download(6 * chunk)
        val held = h.holdAt(Op.RANGE, 4)
        val run = launch { h.engine.run() }
        held.await()
        h.controller.pause(t.id)
        run.join()
        assertEquals(4L * chunk, h.row(t.id).bytesDone)

        h.server.onRequest = {}
        h.controller.resume(t.id)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(listOf(1, 1, 1, 1, 2, 1), (0 until 6).map { h.server.count(Op.RANGE, it) })
        assertArrayEquals(bytes, h.localFile(t.id).readBytes())
    }

    // ---- cancel ----

    @Test
    fun cancelWhileTransferringUploadDeletesSessionAndIsNeverRevived() = runTest {
        val h = harness()
        val (t, _) = h.upload(4 * chunk)
        val held = h.holdAt(Op.CHUNK, 2)
        val run = launch { h.engine.run() }
        held.await()
        assertTrue(h.controller.cancel(t.id))
        run.join()

        assertCancelledForGood(h, t.id)
        assertEquals(1, h.server.count(Op.DELETE))
        assertFalse(h.server.sessions.containsKey(t.id))
        assertTrue(h.events(t.id).any { it.message == "Cleanup: server session deleted" })
    }

    @Test
    fun cancelWhileTransferringDownloadDeletesThePartFile() = runTest {
        val h = harness()
        val (t, _) = h.download(4 * chunk)
        val part = h.localFile(t.id)
        val held = h.holdAt(Op.RANGE, 2)
        val run = launch { h.engine.run() }
        held.await()
        assertTrue(part.exists())
        h.controller.cancel(t.id)
        run.join()

        assertCancelledForGood(h, t.id)
        assertFalse(part.exists())
    }

    @Test
    fun cancelWhileRetryingWinsOverThePendingRetry() = runTest {
        // The backoff never elapses on its own, so the transfer is guaranteed to sit in RETRYING.
        val h = harness(sleep = { awaitCancellation() })
        h.server.fault = { c -> if (c.op == Op.CHUNK && c.index == 1) Fault.Before(FakeTransferServer.http(503, "INJECTED_FAULT")) else null }
        val (t, _) = h.upload(3 * chunk)
        val run = launch { h.engine.run() }
        h.awaitRow(t.id) { it.state == RETRYING }
        assertTrue(h.controller.cancel(t.id))
        run.join()

        assertCancelledForGood(h, t.id)
        assertEquals(1, h.server.count(Op.CHUNK, 1))
        assertEquals(1, h.server.count(Op.DELETE))
    }

    @Test
    fun cancelWhilePausedCleansUpWithoutACoordinator() = runTest {
        val h = harness()
        val (up, _) = h.upload(3 * chunk)
        val (down, _) = h.download(3 * chunk, seed = 99)
        val part = h.localFile(down.id)
        h.server.onRequest = { c -> if (c.index == 1 && c.attempt == 1) awaitCancellation() }
        val run = launch { h.engine.run() }
        h.awaitRow(up.id) { it.bytesDone == chunk.toLong() }
        h.awaitRow(down.id) { it.bytesDone == chunk.toLong() }
        h.controller.pause(up.id)
        h.controller.pause(down.id)
        run.join()

        assertTrue(h.controller.cancel(up.id))
        assertTrue(h.controller.cancel(down.id))
        assertCancelledForGood(h, up.id)
        assertCancelledForGood(h, down.id)
        assertFalse(h.server.sessions.containsKey(up.id))
        assertFalse(part.exists())
    }

    /** CANCELLED, no progress written after the cancel, and neither reconciliation nor a new run revives it. */
    private suspend fun assertCancelledForGood(h: EngineHarness, id: String) {
        assertEquals(CANCELLED, h.state(id))
        val events = h.events(id)
        val cancelledAt = events.indexOfFirst { it.toState == CANCELLED }
        assertTrue(events.drop(cancelledAt).none { it.type == EventType.CHUNK_DONE || it.type == EventType.STATE_CHANGE && it.toState != CANCELLED })
        val chunks = h.repo.getChunks(id)
        val requests = h.server.calls.size

        h.repo.reconcileAfterProcessStart()
        assertFalse(h.controller.resume(id))
        assertFalse(h.controller.retry(id))
        assertFalse(h.controller.pause(id))
        h.engine.run()

        assertEquals(CANCELLED, h.state(id))
        assertEquals(chunks, h.repo.getChunks(id))
        assertEquals("no request after cancel", requests, h.server.calls.size)
        assertTrue(h.stateChanges(id).none { it.first == CANCELLED })
    }

    // ---- system stop ----

    @Test
    fun systemStopRequeuesWithoutErrorOrAttemptAndResumes() = runTest {
        val h = harness()
        val (t, bytes) = h.upload(3 * chunk)
        val held = h.holdAt(Op.CHUNK, 1)
        val run = launch { h.engine.run(stopReason = { "quota" }) }
        held.await()
        run.cancelAndJoin() // what WorkManager does to doWork() on a stop

        val row = h.row(t.id)
        assertEquals(QUEUED, row.state)
        assertNull(row.errorCode)
        assertEquals(0, row.attemptCount)
        assertTrue(h.events(t.id).any { it.message == "Interrupted by a system stop (quota); requeued" })
        assertEquals(WakeupPlan(WakeupPlan.AFTER_STOP_MS, requiresNetwork = true), h.wakeups.last())

        h.server.onRequest = {}
        h.engine.run()
        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(1, h.server.count(Op.CHUNK, 0))
        assertArrayEquals(bytes, h.server.assembled(t.id))
    }

    @Test
    fun systemStopLeavesUserPausedRowsAlone() = runTest {
        val h = harness()
        val (t, _) = h.upload(3 * chunk)
        val held = h.holdAt(Op.CHUNK, 1)
        val run = launch { h.engine.run() }
        held.await()
        // The user pauses, and the stop lands before the coordinator reacts.
        h.repo.transition(t.id, PAUSED)
        run.cancelAndJoin()
        assertEquals(PAUSED, h.state(t.id))
    }

    // ---- persisted backoff and wake-ups ----

    @Test
    fun persistedBackoffSchedulesAWakeupAndIsHonoured() = runTest {
        val h = harness()
        val (t, _) = h.upload(2 * chunk)
        val now = testScheduler.currentTime
        h.repo.transition(t.id, TRANSFERRING)
        h.repo.transition(t.id, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = now + 10_000)
        h.engine.run() // nothing runnable yet

        assertEquals(RETRYING, h.state(t.id))
        assertEquals(WakeupPlan(10_000, requiresNetwork = true), h.wakeups.last())
        advanceTimeBy(10_000)
        h.engine.run()
        assertEquals(COMPLETED, h.state(t.id))
        assertNull(h.wakeups.last())
    }

    @Test
    fun dueBackoffOfAnotherTransferIsPickedUpWhileRunning() = runTest {
        val h = harness()
        val (busy, _) = h.upload(2 * chunk, seed = 1)
        val (waiting, _) = h.upload(2 * chunk, seed = 2)
        val now = testScheduler.currentTime
        h.repo.transition(waiting.id, TRANSFERRING)
        h.repo.transition(waiting.id, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = now + 60_000)
        // `busy` holds the coordinator open until `waiting` has completed. That can only happen if
        // the coordinator's own deadline timer claims `waiting` while `busy` is still running.
        h.server.onRequest = { c ->
            if (c.id == busy.id && c.op == Op.CHUNK && c.index == 1) h.awaitRow(waiting.id) { it.state == COMPLETED }
        }
        h.engine.run()

        assertEquals(COMPLETED, h.state(busy.id))
        assertEquals(COMPLETED, h.state(waiting.id))
        assertTrue(h.stateChanges(waiting.id).contains(RETRYING to TRANSFERRING))
    }

    // ---- concurrency ----

    @Test
    fun neverMoreThanMaxConcurrentAndRaisingTheLimitApplies() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        val held = MutableStateFlow(0)
        var maxEngine = 0
        var maxDb = 0
        h.server.onRequest = { c ->
            if (c.op == Op.CHUNK) {
                maxEngine = max(maxEngine, h.engine.activeIds.value.size)
                maxDb = max(maxDb, h.repo.getInStates(TRANSFERRING, RETRYING, VERIFYING).size)
                held.value++
                try { gate.await() } finally { held.value-- }
            }
        }
        val ids = (1..6).map { h.upload(2 * chunk, seed = it).first.id }
        val run = launch { h.engine.run() }

        held.first { it == 2 }
        assertEquals(2, h.engine.activeIds.value.size)
        assertEquals(4, h.repo.getInStates(QUEUED).size)
        assertEquals(2, maxEngine)
        assertEquals(2, maxDb)

        h.settings.value = h.settings.value.copy(maxConcurrent = 3)
        held.first { it == 3 }
        assertEquals(3, h.engine.activeIds.value.size)
        assertEquals(3, h.repo.getInStates(QUEUED).size)

        gate.complete(Unit)
        run.join()
        assertTrue(ids.all { h.state(it) == COMPLETED })
        assertEquals(3, maxEngine)
        assertEquals(3, maxDb)
    }

    @Test
    fun loweringTheLimitLetsRunningTransfersFinish() = runTest {
        val h = harness()
        h.settings.value = h.settings.value.copy(maxConcurrent = 3)
        val gate = CompletableDeferred<Unit>()
        val held = MutableStateFlow(0)
        var maxAfterLowering = 0
        var lowered = false
        h.server.onRequest = { c ->
            if (c.op == Op.CHUNK && c.index == 0) {
                if (lowered) maxAfterLowering = max(maxAfterLowering, h.engine.activeIds.value.size)
                held.value++
                try { gate.await() } finally { held.value-- }
            }
        }
        val ids = (1..5).map { h.upload(2 * chunk, seed = it).first.id }
        val run = launch { h.engine.run() }
        held.first { it == 3 }
        h.settings.value = h.settings.value.copy(maxConcurrent = 1)
        lowered = true
        gate.complete(Unit)
        run.join()

        assertTrue(ids.all { h.state(it) == COMPLETED })
        assertTrue("after lowering, new work starts only below the limit", maxAfterLowering <= 1)
    }

    // ---- process death ----

    @Test
    fun processDeathMidChunkUploadResumesFromLastDoneChunk() = processDeath(Op.CHUNK) { h -> h.upload(6 * chunk).first.id }

    @Test
    fun processDeathMidChunkDownloadResumesFromLastDoneChunk() = processDeath(Op.RANGE) { h -> h.download(6 * chunk).first.id }

    /**
     * Task Manager "Stop" / Force stop: process A dies mid-chunk and the next process learns from the
     * exit record that the user stopped it. Its first run pauses the row instead of resuming it and
     * sends nothing; a later run in the same process does not re-apply the exit reason; the user's
     * resume then completes the transfer from the last DONE chunk.
     */
    @Test
    fun userStoppedProcessLeavesItsTransferPausedUntilResumed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, File(dir, "transfers.db").path).build()
        val server = FakeTransferServer()
        val a = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db).also { harnesses += it }
        val id = a.upload(6 * chunk).first.id

        val reached = CompletableDeferred<Unit>()
        server.onRequest = { c -> if (c.op == Op.CHUNK && c.index == 3 && c.attempt == 1) { reached.complete(Unit); awaitCancellation() } }
        val executor = Executors.newSingleThreadExecutor()
        val processA = CoroutineScope(executor.asCoroutineDispatcher() + Job())
        processA.launch { a.engine.run() }
        withTimeout(10_000) { reached.await() }
        val release = CountDownLatch(1)
        val frozen = CountDownLatch(1)
        executor.execute { frozen.countDown(); release.await() }
        assertTrue(frozen.await(10, TimeUnit.SECONDS))
        assertEquals(TRANSFERRING, a.state(id))

        server.onRequest = {}
        var exitReads = 0
        val b = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db, previousExitByUser = { exitReads++; true })
        val requestsBefore = server.count(Op.CHUNK, 3)
        withContext(Dispatchers.Default) { withTimeout(20_000) { b.engine.run() } }

        assertEquals("the user's stop is kept", PAUSED, b.state(id))
        assertTrue("a paused row is not shown as restored", b.engine.restoredIds.value.isEmpty())
        assertEquals("nothing was sent for it", requestsBefore, server.count(Op.CHUNK, 3))
        assertEquals(TRANSFERRING to PAUSED, b.stateChanges(id).last())

        withContext(Dispatchers.Default) { withTimeout(20_000) { b.engine.run() } }
        assertEquals("a second run does not touch it", PAUSED, b.state(id))
        assertEquals("the exit reason is read once per process", 1, exitReads)

        assertTrue(b.controller.resume(id))
        withContext(Dispatchers.Default) { withTimeout(20_000) { b.engine.run() } }
        assertEquals(COMPLETED, b.state(id))
        (0 until 3).forEach { assertEquals("chunk $it sent once", 1, server.count(Op.CHUNK, it)) }

        processA.cancel()
        release.countDown()
        withTimeout(10_000) { processA.coroutineContext.job.join() }
        executor.shutdown()
        assertEquals("the dead process never writes again", COMPLETED, b.state(id))
    }

    /**
     * Process A is "killed" mid-chunk by freezing its only thread forever (no cleanup runs, the row
     * stays TRANSFERRING). Process B — new engine, repository and tracker on the same database and
     * server — must reconcile, resume after the last DONE chunk and reach COMPLETED only via VERIFYING.
     */
    private fun processDeath(op: Op, create: suspend (EngineHarness) -> String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, File(dir, "transfers.db").path).build()
        val server = FakeTransferServer()
        val a = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db).also { harnesses += it }
        val id = create(a)

        val reached = CompletableDeferred<Unit>()
        server.onRequest = { c -> if (c.op == op && c.index == 3 && c.attempt == 1) { reached.complete(Unit); awaitCancellation() } }
        val executor = Executors.newSingleThreadExecutor()
        val processA = CoroutineScope(executor.asCoroutineDispatcher() + Job())
        processA.launch { a.engine.run() }
        withTimeout(10_000) { reached.await() }
        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { frozen.countDown(); release.await() }
        assertTrue(frozen.await(10, TimeUnit.SECONDS))

        assertEquals(TRANSFERRING, a.state(id))
        assertEquals(3L * chunk, a.row(id).bytesDone)

        server.onRequest = {}
        val b = EngineHarness(context, dir, System::currentTimeMillis, server = server, db = db)
        withContext(Dispatchers.Default) { withTimeout(20_000) { b.engine.run() } }

        assertEquals(COMPLETED, b.state(id))
        assertEquals("process B shows the row as restored", setOf(id), b.engine.restoredIds.value)
        assertTrue("process A reconciled nothing", a.engine.restoredIds.value.isEmpty())
        (0 until 3).forEach { assertEquals("chunk $it fetched/sent once", 1, server.count(op, it)) }
        assertEquals("the in-flight chunk is redone once", 2, server.count(op, 3))
        assertEquals(1, server.count(op, 4))
        val events = b.events(id)
        assertTrue(events.any { it.message == "Reconciled after process start: TRANSFERRING → QUEUED" })
        val changes = b.stateChanges(id)
        assertEquals(VERIFYING to COMPLETED, changes.last())
        assertEquals(TRANSFERRING to VERIFYING, changes[changes.size - 2])
        assertEquals(1, changes.count { it.second == COMPLETED })

        processA.cancel()
        release.countDown()
        withTimeout(10_000) { processA.coroutineContext.job.join() }
        executor.shutdown()
        assertEquals("the dead process never writes again", COMPLETED, b.state(id))
    }
}
