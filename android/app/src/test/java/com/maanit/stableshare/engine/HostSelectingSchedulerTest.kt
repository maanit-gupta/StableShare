package com.maanit.stableshare.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which host a coordinator start uses (DESIGN.md §9). */
class HostSelectingSchedulerTest {
    private val uidtCalls = mutableListOf<Unit>()
    private val workManagerCalls = mutableListOf<Boolean>()
    private val logs = mutableListOf<String>()

    private fun scheduler(
        sdk: Int = 34,
        visible: Boolean = true,
        leaseHeld: Boolean = false,
        accepting: Boolean = false,
        uidt: () -> String? = { null },
    ) = HostSelectingScheduler(
        sdkInt = sdk,
        appVisible = { visible },
        leaseHeld = { leaseHeld },
        engineAcceptingWork = { accepting },
        uidt = { uidtCalls += Unit; uidt() },
        fallback = TransferScheduler { workManagerCalls += it },
        log = { logs += it },
    )

    @Test
    fun belowAndroid14UsesWorkManager() {
        scheduler(sdk = 33).ensureRunning(userInitiated = true)
        assertEquals(0, uidtCalls.size)
        assertEquals(listOf(true), workManagerCalls)
    }

    @Test
    fun userActionWhileVisibleWithFreeLeaseUsesUidt() {
        scheduler(sdk = 34).ensureRunning(userInitiated = true)
        scheduler(sdk = 37).ensureRunning(userInitiated = true)
        assertEquals(2, uidtCalls.size)
        assertEquals(emptyList<Boolean>(), workManagerCalls)
    }

    @Test
    fun backgroundStartsUseWorkManager() {
        scheduler().ensureRunning(userInitiated = false)
        scheduler().ensureRunning()
        assertEquals(0, uidtCalls.size)
        assertEquals(listOf(false, false), workManagerCalls)
    }

    @Test
    fun notVisibleUsesWorkManager() {
        scheduler(visible = false).ensureRunning(userInitiated = true)
        assertEquals(0, uidtCalls.size)
        assertEquals(listOf(true), workManagerCalls)
    }

    @Test
    fun runningLoopAcceptingWorkStartsNoNewHost() {
        scheduler(leaseHeld = true, accepting = true).ensureRunning(userInitiated = true)
        scheduler(accepting = true).ensureRunning(userInitiated = false)
        assertEquals(0, uidtCalls.size)
        assertEquals(emptyList<Boolean>(), workManagerCalls)
    }

    @Test
    fun leaseHeldByAnExitingLoopHandsOffThroughWorkManagerNotUidt() {
        // Held but no longer accepting: the WorkManager run hands off to the holder (RunLease rerun).
        scheduler(leaseHeld = true, accepting = false).ensureRunning(userInitiated = true)
        assertEquals(0, uidtCalls.size)
        assertEquals(listOf(true), workManagerCalls)
    }

    @Test
    fun uidtFailureFallsBackToWorkManagerAndLogs() {
        scheduler(uidt = { "JobScheduler returned RESULT_FAILURE" }).ensureRunning(userInitiated = true)
        scheduler(uidt = { throw IllegalStateException("not visible") }).ensureRunning(userInitiated = true)
        assertEquals(2, uidtCalls.size)
        assertEquals(listOf(true, true), workManagerCalls)
        assertEquals(2, logs.size)
        assertEquals(true, logs[0].contains("RESULT_FAILURE"))
        assertEquals(true, logs[1].contains("IllegalStateException"))
    }
}
