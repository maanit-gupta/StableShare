package com.maanit.stableshare.worker

import android.app.job.JobParameters
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.engine.EngineHarness
import com.maanit.stableshare.engine.FakeTransferServer.Op
import com.maanit.stableshare.engine.StopKind
import com.maanit.stableshare.engine.WakeupPlan
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
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

/** How a stopped user-initiated job leaves its transfers (DESIGN.md §9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferJobHostTest {
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
        EngineHarness(ApplicationProvider.getApplicationContext(), dir, clock = { testScheduler.currentTime }).also { harnesses += it }

    private val chunk = EngineHarness.CHUNK

    /** Started by the controller as a user action, as the upload's own start was. */
    @Test
    fun userActionsStartUserInitiated() = runTest {
        val h = harness()
        h.upload(chunk)
        assertEquals(1, h.userInitiatedCalls.size)
    }

    private var userRestarts = 0

    /**
     * Starts the job's loop on an upload held mid-transfer, lets [beforeStop] run, then stops the
     * job with [reason]. Returns the upload's id and how many ensureRunning calls the stop made
     * ([userRestarts]: how many of them were user-initiated).
     */
    private suspend fun TestScope.stopMidTransfer(h: EngineHarness, reason: Int, beforeStop: suspend () -> Unit = {}): Pair<String, Int> {
        val (t, _) = h.upload(3 * chunk)
        val held = h.holdAt(Op.CHUNK, 1)
        val host = TransferJobHost(h.engine, h.scheduler)
        val run = launch { host.run() }
        held.await()
        beforeStop()
        val before = h.ensureRunningCalls.size
        val userBefore = h.userInitiatedCalls.size
        host.stopped(reason)
        run.cancelAndJoin() // what onStopJob does to the job's scope
        userRestarts = h.userInitiatedCalls.size - userBefore
        return t.id to h.ensureRunningCalls.size - before
    }

    @Test
    fun stopReasonsMapToKinds() {
        assertEquals(StopKind.PAUSE, TransferJobHost.kindFor(JobParameters.STOP_REASON_USER))
        assertEquals(StopKind.WAIT_FOR_NETWORK, TransferJobHost.kindFor(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        for (other in listOf(JobParameters.STOP_REASON_TIMEOUT, JobParameters.STOP_REASON_QUOTA, JobParameters.STOP_REASON_DEVICE_STATE, null)) {
            assertEquals(StopKind.REQUEUE, TransferJobHost.kindFor(other))
        }
    }

    @Test
    fun userStopPausesAndRestartsNothing() = runTest {
        val h = harness()
        val wakeupsBefore = h.wakeups.size
        val (id, restarts) = stopMidTransfer(h, JobParameters.STOP_REASON_USER)
        assertEquals(PAUSED, h.state(id))
        assertNull(h.row(id).errorCode)
        assertEquals(0, h.row(id).attemptCount)
        assertEquals(0, restarts)
        assertEquals("no wake-up after a user stop", wakeupsBefore, h.wakeups.size)
    }

    @Test
    fun otherStopRequeuesAndRestartsOnceInTheBackground() = runTest {
        val h = harness()
        val (id, restarts) = stopMidTransfer(h, JobParameters.STOP_REASON_TIMEOUT)
        assertEquals(QUEUED, h.state(id))
        assertNull(h.row(id).errorCode)
        assertEquals(1, restarts)
        assertEquals("the restart is not user-initiated", 0, userRestarts)
        assertTrue(h.events(id).any { it.type == EventType.INFO && it.message == "Interrupted by a system stop (job timeout); requeued" })
        assertEquals(WakeupPlan(WakeupPlan.AFTER_STOP_MS, requiresNetwork = true), h.wakeups.last())

        h.server.onRequest = {}
        h.engine.run()
        assertEquals(COMPLETED, h.state(id))
    }

    @Test
    fun connectivityStopWaitsForTheNetwork() = runTest {
        val h = harness()
        val (id, restarts) = stopMidTransfer(h, JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        val row = h.row(id)
        assertEquals(RETRYING, row.state)
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, row.errorCode)
        assertNull(row.nextRetryAt)
        assertEquals(0, row.attemptCount)
        assertEquals(0, restarts)
        assertEquals(WakeupPlan(WakeupPlan.AFTER_STOP_MS, requiresNetwork = true), h.wakeups.last())
    }

    @Test
    fun cancelledRowsAreNeverTouched() = runTest {
        for (reason in listOf(JobParameters.STOP_REASON_USER, JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY, JobParameters.STOP_REASON_TIMEOUT)) {
            val h = harness()
            lateinit var cancelled: String
            val (id, _) = stopMidTransfer(h, reason) {
                // The user cancels, and the stop lands before the coordinator reacts.
                cancelled = h.repo.getInStates(com.maanit.stableshare.domain.TransferState.TRANSFERRING).single().id
                h.repo.transition(cancelled, CANCELLED)
            }
            assertEquals(cancelled, id)
            assertEquals("reason $reason", CANCELLED, h.state(id))
            assertNull(h.row(id).errorCode)
            h.close()
            harnesses.remove(h)
            dir = tmp.newFolder()
        }
    }
}
