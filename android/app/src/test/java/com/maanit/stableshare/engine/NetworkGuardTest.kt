package com.maanit.stableshare.engine

import com.maanit.stableshare.data.net.NetworkUnusableException
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.engine.NetworkState.Metered
import com.maanit.stableshare.engine.NetworkState.Offline
import com.maanit.stableshare.engine.NetworkState.Unmetered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The request gate and the 1500 ms debounce (DESIGN.md §7), in virtual time. */
class NetworkGuardTest {

    private val net = FakeConnectivity(wifiOnly = true)
    private val guard = NetworkGuard(net)

    private val TestScope.now get() = testScheduler.currentTime

    private suspend fun stopped(block: suspend () -> Unit): NetworkUnusableException =
        try {
            block()
            throw AssertionError("expected NetworkUnusableException")
        } catch (e: NetworkUnusableException) {
            e
        }

    @Test
    fun requestRunsWhenTheNetworkIsUsable() = runTest {
        assertEquals(7, guard.guard { 7 })
        assertEquals(0, now)
    }

    @Test
    fun unusableAtTheStartWaitsTheDebounceThenGivesUpWithoutSending() = runTest {
        net.state = Metered
        var sent = false
        val e = stopped { guard.guard { sent = true } }
        assertEquals(ErrorCode.METERED_NETWORK, e.code)
        assertFalse(sent)
        assertEquals(NetworkGuard.DEBOUNCE_MS, now)
    }

    @Test
    fun aBlipBeforeTheStartOnlyDelaysTheRequestUntilWifiIsBack() = runTest {
        net.state = Metered
        val startedAt = async { guard.guard { now } }
        advanceTimeBy(1_000)
        net.state = Unmetered
        assertEquals(1_000, startedAt.await())
    }

    @Test
    fun anInFlightRequestIsCancelledOnceTheNetworkHasBeenUnusableForTheDebounce() = runTest {
        var cancelled = false
        val call = async {
            runCatching {
                guard.guard {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        }
        runCurrent()
        net.state = Metered
        advanceTimeBy(NetworkGuard.DEBOUNCE_MS - 1)
        assertFalse("not before the debounce", cancelled)

        val e = call.await().exceptionOrNull()
        assertTrue("$e", e is NetworkUnusableException && e.code == ErrorCode.METERED_NETWORK)
        assertTrue(cancelled)
        assertEquals(NetworkGuard.DEBOUNCE_MS, now)
    }

    @Test
    fun aBlipDuringARequestInterruptsNothing() = runTest {
        val release = CompletableDeferred<Unit>()
        val call = async {
            guard.guard {
                release.await()
                "done"
            }
        }
        runCurrent()
        net.state = Metered
        advanceTimeBy(1_000)
        net.state = Unmetered
        advanceTimeBy(5_000)
        release.complete(Unit)
        assertEquals("done", call.await())
    }

    @Test
    fun goingOfflineDuringTheDebounceStopsWithNetworkUnavailable() = runTest {
        val call = async { runCatching { guard.guard { awaitCancellation() } } }
        runCurrent()
        net.state = Metered
        advanceTimeBy(500)
        net.state = Offline
        val e = call.await().exceptionOrNull() as NetworkUnusableException
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, e.code)
        assertEquals(NetworkGuard.DEBOUNCE_MS, now)
    }

    @Test
    fun turningWifiOnlyOnOverMobileDataStopsARequest() = runTest {
        net.wifiOnly = false
        net.state = Metered
        val call = async { runCatching { guard.guard { awaitCancellation() } } }
        runCurrent()
        net.wifiOnly = true
        val e = call.await().exceptionOrNull() as NetworkUnusableException
        assertEquals(ErrorCode.METERED_NETWORK, e.code)
    }

    @Test
    fun requestErrorsPassThroughUntouched() = runTest {
        val thrown = runCatching { guard.guard<Unit> { throw IOException("reset") } }.exceptionOrNull()
        // Not turned into a wait: the classifier decides (stack-trace recovery may copy it).
        assertEquals(IOException::class, thrown!!::class)
        assertEquals("reset", thrown.message)
    }

    @Test
    fun aBackoffSleepEndsEarlyWhenTheNetworkBecomesUnusable() = runTest {
        val nap = async { guard.sleep(30_000) { delay(it) } }
        runCurrent()
        net.state = Metered
        assertEquals(ErrorCode.METERED_NETWORK, nap.await())
        assertEquals(NetworkGuard.DEBOUNCE_MS, now)
    }

    @Test
    fun aBackoffSleepOnAUsableNetworkRunsOut() = runTest {
        assertNull(guard.sleep(2_000) { delay(it) })
        assertEquals(2_000, now)
    }
}
