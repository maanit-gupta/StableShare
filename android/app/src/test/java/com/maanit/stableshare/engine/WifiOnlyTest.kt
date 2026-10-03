package com.maanit.stableshare.engine

import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.engine.FakeTransferServer.Fault
import com.maanit.stableshare.engine.FakeTransferServer.Op
import com.maanit.stableshare.engine.NetworkState.Metered
import com.maanit.stableshare.engine.NetworkState.Offline
import com.maanit.stableshare.engine.NetworkState.Unmetered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi only (DESIGN.md §7, §9): nothing is claimed or sent while the network is unusable, a
 * running request is cut 1500 ms after the network becomes unusable, and waiting rows resume by
 * themselves once it is usable again. Fake connectivity, virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WifiOnlyTest {

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

    private fun TestScope.bootstrap(h: EngineHarness) =
        EngineBootstrap(h.repo, h.settings, h.net, h.scheduler, backgroundScope).also { it.start() }

    private val chunk = EngineHarness.CHUNK

    private suspend fun EngineHarness.stateChanges(id: String) =
        events(id).filter { it.type == EventType.STATE_CHANGE }.map { it.fromState to it.toState }

    private suspend fun EngineHarness.assertWaiting(id: String, code: ErrorCode) {
        val row = row(id)
        assertEquals(RETRYING, row.state)
        assertEquals(code, row.errorCode)
        assertNull("waits for the network, not a timer", row.nextRetryAt)
    }

    /** No attempt consumed anywhere: not on the transfer, not on any chunk. */
    private suspend fun EngineHarness.assertNoAttempts(id: String) {
        assertEquals(0, row(id).attemptCount)
        assertTrue(repo.getChunks(id).all { it.attempts == 0 })
    }

    // ---- (a), (b): the coordinator's gate ----

    @Test
    fun wifiOnlyOffTransfersRunOnAMeteredNetwork() = runTest {
        val h = harness()
        h.net.state = Metered
        val (up, upBytes) = h.upload(3 * chunk)
        val (down, downBytes) = h.download(3 * chunk, seed = 5)
        h.engine.run()

        assertEquals(COMPLETED, h.state(up.id))
        assertEquals(COMPLETED, h.state(down.id))
        assertArrayEquals(upBytes, h.server.assembled(up.id))
        assertArrayEquals(downBytes, h.localFile(down.id).readBytes())
    }

    @Test
    fun wifiOnlyOnAMeteredNetworkClaimsNothingAndSendsNothing() = runTest {
        val h = harness()
        h.setWifiOnly(true)
        h.net.state = Metered
        val (up, _) = h.upload(3 * chunk)
        val (down, _) = h.download(3 * chunk, seed = 5) // the manifest is fetched by the controller
        val requests = h.server.calls.size

        h.engine.run() // returns: nothing may be claimed

        assertEquals(QUEUED, h.state(up.id))
        assertEquals(QUEUED, h.state(down.id))
        assertEquals("no request on mobile data", requests, h.server.calls.size)
        assertTrue(h.stateChanges(up.id).none { it.second == TRANSFERRING })
        assertEquals(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true, unmetered = true), h.wakeups.last())
    }

    // ---- (c), (d): Unmetered → Metered mid-chunk, then back ----

    private class Stopped(val up: TransferEntity, val upBytes: ByteArray, val down: TransferEntity, val downBytes: ByteArray)

    /** Upload and download both hold chunk 2 in flight; the network turns metered; both stop. */
    private suspend fun TestScope.stopBothOnMetered(h: EngineHarness): Stopped {
        h.setWifiOnly(true)
        val (up, upBytes) = h.upload(4 * chunk)
        val (down, downBytes) = h.download(4 * chunk, seed = 9)
        val cancelled = ConcurrentHashMap.newKeySet<Op>()
        h.server.onRequest = { c ->
            if (c.index == 2 && c.attempt == 1 && (c.op == Op.CHUNK || c.op == Op.RANGE)) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled += c.op
                }
            }
        }
        val run = launch { h.engine.run() }
        h.server.inFlight.first { it == 2 }
        val requests = h.server.calls.size
        val switchedAt = testScheduler.currentTime

        h.net.state = Metered
        run.join() // both jobs stopped and the gate is closed, so the coordinator exits

        assertEquals("the in-flight calls were cancelled", setOf(Op.CHUNK, Op.RANGE), cancelled.toSet())
        assertEquals("no request after the switch", requests, h.server.calls.size)
        for (id in listOf(up.id, down.id)) {
            h.assertWaiting(id, ErrorCode.METERED_NETWORK)
            h.assertNoAttempts(id)
            assertTrue("debounced", h.row(id).updatedAt - switchedAt >= NetworkGuard.DEBOUNCE_MS)
            val chunks = h.repo.getChunks(id)
            assertEquals(listOf(ChunkStatus.DONE, ChunkStatus.DONE, ChunkStatus.PENDING, ChunkStatus.PENDING), chunks.map { it.status })
            assertEquals(2L * chunk, h.row(id).bytesDone)
        }
        // The download's finished chunks are intact on disk.
        val part = h.localFile(down.id)
        h.repo.getChunks(down.id).take(2).forEach {
            assertTrue(h.files.verifyChunkOnDisk(part, it.offset, it.length, it.sha256!!))
        }
        assertEquals(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true, unmetered = true), h.wakeups.last())
        return Stopped(up, upBytes, down, downBytes)
    }

    @Test
    fun meteredMidChunkCancelsTheCallAndWaitsWithoutConsumingAttempts() = runTest {
        stopBothOnMetered(harness())
    }

    @Test
    fun backOnWifiTheWaitingTransfersResumeAndVerify() = runTest {
        val h = harness()
        val s = stopBothOnMetered(h)
        bootstrap(h)
        h.server.onRequest = {}
        val ensureBefore = h.ensureRunningCalls.size

        h.net.state = Unmetered
        h.awaitRow(s.up.id) { it.state == QUEUED }
        h.awaitRow(s.down.id) { it.state == QUEUED }
        h.engine.run()
        assertTrue("the edge starts the coordinator", h.ensureRunningCalls.size > ensureBefore)

        assertEquals(COMPLETED, h.state(s.up.id))
        assertEquals(COMPLETED, h.state(s.down.id))
        assertArrayEquals(s.upBytes, h.server.assembled(s.up.id))
        assertEquals(FileStore.sha256Hex(s.upBytes), h.row(s.up.id).sha256)
        assertArrayEquals(s.downBytes, h.localFile(s.down.id).readBytes())
        assertTrue(h.eventTypes(s.up.id).contains(EventType.VERIFIED))
        assertTrue(h.eventTypes(s.down.id).contains(EventType.VERIFIED))
        // Finished chunks were never sent or fetched again; the interrupted one exactly once more.
        (0..1).forEach {
            assertEquals("upload chunk $it", 1, h.server.count(Op.CHUNK, it))
            assertEquals("download chunk $it", 1, h.server.count(Op.RANGE, it))
        }
        assertEquals(2, h.server.count(Op.CHUNK, 2))
        assertEquals(2, h.server.count(Op.RANGE, 2))
        h.assertNoAttempts(s.up.id)
        h.assertNoAttempts(s.down.id)
    }

    // ---- (e) a blip ----

    @Test
    fun aOneSecondBlipDoesNotInterruptATransfer() = runTest {
        val h = harness()
        h.setWifiOnly(true)
        val (up, bytes) = h.upload(3 * chunk)
        val release = CompletableDeferred<Unit>()
        val held = h.holdUntil(Op.CHUNK, 1, release)
        val run = launch { h.engine.run() }
        held.await()

        h.net.state = Metered
        advanceTimeBy(1_000)
        h.net.state = Unmetered
        advanceTimeBy(2_000) // well past where a debounce started by the blip would have fired
        release.complete(Unit)
        run.join()

        assertEquals(COMPLETED, h.state(up.id))
        assertTrue(h.stateChanges(up.id).none { it.second == RETRYING })
        assertEquals(1, h.server.count(Op.CHUNK, 1))
        assertArrayEquals(bytes, h.server.assembled(up.id))
    }

    // ---- (f) the switch ----

    @Test
    fun turningWifiOnlyOnOverMobileDataStopsTheTransfer() = runTest {
        val h = harness()
        h.net.state = Metered
        val (up, _) = h.upload(3 * chunk)
        val held = h.holdAt(Op.CHUNK, 1)
        val run = launch { h.engine.run() }
        held.await()
        val requests = h.server.calls.size

        h.setWifiOnly(true)
        run.join()

        h.assertWaiting(up.id, ErrorCode.METERED_NETWORK)
        h.assertNoAttempts(up.id)
        assertEquals(ChunkStatus.DONE, h.repo.getChunks(up.id)[0].status)
        assertEquals(requests, h.server.calls.size)
    }

    // ---- (g) the waiting reason follows the network ----

    @Test
    fun waitingRowsAreRecodedBetweenMeteredAndOffline() = runTest {
        val h = harness()
        h.setWifiOnly(true)
        val (up, _) = h.upload(3 * chunk)
        val held = h.holdAt(Op.CHUNK, 1)
        val run = launch { h.engine.run() }
        held.await()
        h.net.state = Metered
        run.join()
        h.assertWaiting(up.id, ErrorCode.METERED_NETWORK)
        val changes = h.stateChanges(up.id)
        bootstrap(h)

        h.net.state = Offline
        h.awaitRow(up.id) { it.errorCode == ErrorCode.NETWORK_UNAVAILABLE }
        h.assertWaiting(up.id, ErrorCode.NETWORK_UNAVAILABLE)
        assertTrue(h.events(up.id).any { it.type == EventType.INFO && it.message == "Still waiting: now offline" })

        h.net.state = Metered
        h.awaitRow(up.id) { it.errorCode == ErrorCode.METERED_NETWORK }
        h.assertWaiting(up.id, ErrorCode.METERED_NETWORK)
        assertEquals("re-coding writes no state", changes, h.stateChanges(up.id))
        h.assertNoAttempts(up.id)
    }

    @Test
    fun goingOfflineDuringTheDebounceStopsWithNetworkUnavailable() = runTest {
        val h = harness()
        h.setWifiOnly(true)
        val (up, _) = h.upload(3 * chunk)
        val held = h.holdAt(Op.CHUNK, 1)
        val run = launch { h.engine.run() }
        held.await()

        h.net.state = Metered
        advanceTimeBy(500)
        h.net.state = Offline
        run.join()

        h.assertWaiting(up.id, ErrorCode.NETWORK_UNAVAILABLE)
        h.assertNoAttempts(up.id)
        assertEquals(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true, unmetered = true), h.wakeups.last())
    }

    // ---- (h) rules 4 and 6.5: nothing revives CANCELLED, nothing un-pauses PAUSED ----

    @Test
    fun pausedAndCancelledRowsAreNeverPromoted() = runTest {
        val h = harness()
        h.settings.value = h.settings.value.copy(maxConcurrent = 3)
        h.setWifiOnly(true)
        val (paused, _) = h.upload(3 * chunk, seed = 1)
        val (cancelled, _) = h.upload(3 * chunk, seed = 2)
        val (resumed, bytes) = h.upload(3 * chunk, seed = 3)
        h.server.onRequest = { c -> if (c.op == Op.CHUNK && c.index == 1 && c.attempt == 1) awaitCancellation() }
        val run = launch { h.engine.run() }
        h.server.inFlight.first { it == 3 }
        h.net.state = Metered
        run.join()
        listOf(paused, cancelled, resumed).forEach { h.assertWaiting(it.id, ErrorCode.METERED_NETWORK) }

        assertTrue(h.controller.pause(paused.id))
        assertTrue(h.controller.cancel(cancelled.id))
        val untouched = listOf(paused.id, cancelled.id).associateWith { id -> h.server.calls.count { it.id == id } }
        bootstrap(h)
        h.server.onRequest = {}

        h.net.state = Unmetered
        h.awaitRow(resumed.id) { it.state == QUEUED }
        h.engine.run()

        assertEquals(COMPLETED, h.state(resumed.id))
        assertArrayEquals(bytes, h.server.assembled(resumed.id))
        assertEquals(PAUSED, h.state(paused.id))
        assertEquals(CANCELLED, h.state(cancelled.id))
        untouched.forEach { (id, n) -> assertEquals("no request for $id", n, h.server.calls.count { it.id == id }) }
        assertTrue(h.stateChanges(paused.id).none { it.first == PAUSED })
        assertTrue(h.stateChanges(cancelled.id).none { it.first == CANCELLED })
    }

    // ---- a backoff interrupted by the network ----

    @Test
    fun aBackoffCutShortByMobileDataWaitsForWifiWithoutAnExtraAttempt() = runTest {
        // The backoff sleep never ends by itself, so only the network can end it.
        val h = harness(sleep = { awaitCancellation() })
        h.setWifiOnly(true)
        h.server.fault = { c ->
            if (c.op == Op.CHUNK && c.index == 1 && c.attempt == 1) Fault.Before(FakeTransferServer.http(503, "INJECTED_FAULT")) else null
        }
        val (up, bytes) = h.upload(3 * chunk)
        val run = launch { h.engine.run() }
        h.awaitRow(up.id) { it.state == RETRYING && it.nextRetryAt != null }

        h.net.state = Metered
        run.join()

        h.assertWaiting(up.id, ErrorCode.METERED_NETWORK)
        assertEquals(
            listOf(RETRYING to TRANSFERRING, TRANSFERRING to RETRYING),
            h.stateChanges(up.id).takeLast(2),
        )
        assertEquals("only the 503 counted", 1, h.row(up.id).attemptCount)
        assertEquals(1, h.repo.getChunks(up.id)[1].attempts)
        assertEquals(1, h.server.count(Op.CHUNK, 1))

        bootstrap(h)
        h.net.state = Unmetered
        h.awaitRow(up.id) { it.state == QUEUED }
        h.engine.run()
        assertEquals(COMPLETED, h.state(up.id))
        assertArrayEquals(bytes, h.server.assembled(up.id))
        assertEquals(1, h.row(up.id).attemptCount)
    }

    @Test
    fun restartWhileWaitingKeepsWaitingUntilWifi() = runTest {
        val h = harness()
        h.setWifiOnly(true)
        h.net.state = Metered
        val (up, _) = h.upload(2 * chunk)
        // A previous process died mid-transfer: reconciliation requeues it, the gate holds it.
        h.repo.claimNextQueued(1)
        h.engine.run()
        assertEquals(QUEUED, h.state(up.id))
        assertEquals(0, h.server.count(Op.CREATE))

        bootstrap(h)
        val before = h.ensureRunningCalls.size
        h.net.state = Unmetered
        h.engine.run()
        assertEquals(COMPLETED, h.state(up.id))
        assertTrue("a gated QUEUED row needs no promotion, yet the edge starts the coordinator", h.ensureRunningCalls.size > before)
    }
}
