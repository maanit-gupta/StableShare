package com.maanit.stableshare.engine

import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import com.maanit.stableshare.engine.FakeTransferServer.Op
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** The process-wide coordinator lease (DESIGN.md §9): one loop at a time, and no lost hand-offs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RunLeaseTest {

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

    @Test
    fun twoHostsStartingAtOnceRunOneLoopAndNeverExceedTheLimit() = runTest {
        val h = harness() // maxConcurrent = 2
        val gate = CompletableDeferred<Unit>()
        val held = MutableStateFlow(0)
        var maxInFlight = 0
        var maxDb = 0
        h.server.onRequest = { c ->
            if (c.op == Op.CHUNK) {
                held.value++
                maxInFlight = max(maxInFlight, held.value)
                maxDb = max(maxDb, h.repo.getInStates(TRANSFERRING, RETRYING, VERIFYING).size)
                try { gate.await() } finally { held.value-- }
            }
        }
        val ids = (1..5).map { h.upload(2 * chunk, seed = it).first.id }

        val hosts = listOf(async { h.engine.run() }, async { h.engine.run() })
        held.first { it == 2 }
        // Give a second loop, had one started, every chance to claim more rows.
        repeat(20) { yield() }
        assertEquals(2, h.repo.getInStates(TRANSFERRING, RETRYING, VERIFYING).size)

        gate.complete(Unit)
        val ran = hosts.awaitAll()
        assertEquals("exactly one host ran the loop", listOf(true, false), ran.sorted().reversed())
        assertTrue(ids.all { h.state(it) == COMPLETED })
        assertEquals(2, maxInFlight)
        assertEquals(2, maxDb)
    }

    @Test
    fun aHostStartedWhileTheLoopIsExitingIsServedByARerun() = runTest {
        val h = harness()
        val (first, _) = h.upload(2 * chunk, seed = 1)
        val fired = AtomicBoolean(false)
        var lateId: String? = null
        var lateHostRan: Boolean? = null
        // The wake-up is scheduled after the loop's last emptiness check, with the lease still
        // held: the worst moment for a new row and a new host to arrive.
        h.onWakeup = {
            if (fired.compareAndSet(false, true)) runBlocking {
                lateId = h.upload(2 * chunk, seed = 2).first.id
                assertTrue(h.lease.isHeld())
                lateHostRan = h.engine.run()
            }
        }

        assertTrue(h.engine.run())

        assertEquals(COMPLETED, h.state(first.id))
        assertEquals("the late host handed off instead of running", false, lateHostRan)
        assertEquals("the holder ran the loop again for the late row", COMPLETED, h.state(lateId!!))
        assertFalse(h.lease.isHeld())
    }

    @Test
    fun leaseNeverRunsTwoBlocksAtOnceAndServesTheLastRequest() = runBlocking(Dispatchers.Default) {
        val lease = RunLease()
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val requests = AtomicLong()
        val lastSeenAtStart = AtomicLong()
        val ranCount = AtomicInteger()

        (1..8).map {
            launch {
                repeat(300) {
                    requests.incrementAndGet()
                    val ran = lease.runOrHandOff {
                        lastSeenAtStart.set(requests.get())
                        maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        yield()
                        inside.decrementAndGet()
                    }
                    if (ran) ranCount.incrementAndGet()
                }
            }
        }.forEach { it.join() }

        assertEquals(1, maxInside.get())
        assertTrue(ranCount.get() >= 1)
        assertEquals("a block started after the final request", requests.get(), lastSeenAtStart.get())
        assertFalse(lease.isHeld())
    }

    @Test
    fun aRequestDuringTheBlockMakesTheHolderRunAgain() = runTest {
        val lease = RunLease()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val holder = async {
            lease.runOrHandOff {
                runs++
                if (runs == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        assertFalse(lease.runOrHandOff { error("must not run while held") })
        release.complete(Unit)
        assertTrue(holder.await())
        assertEquals(2, runs)
        assertTrue(lease.runOrHandOff { runs++ })
        assertEquals(3, runs)
    }
}
