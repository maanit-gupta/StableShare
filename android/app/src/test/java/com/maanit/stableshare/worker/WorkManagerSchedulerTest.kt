package com.maanit.stableshare.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.maanit.stableshare.engine.WakeupPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wake-up's network constraint: unmetered with Wi-Fi only, any connected network otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkManagerSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerScheduler({ workManager }, engineAcceptingWork = { false })
    }

    private fun wakeupNetworkType(): NetworkType =
        workManager.getWorkInfosForUniqueWork(WorkManagerScheduler.WAKEUP).get().single { !it.state.isFinished }
            .constraints.requiredNetworkType

    @Test
    fun wifiOnlyWakeupWaitsForAnUnmeteredNetwork() {
        scheduler.schedule(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true, unmetered = true))
        assertEquals(NetworkType.UNMETERED, wakeupNetworkType())
    }

    @Test
    fun otherwiseAnyConnectedNetworkWillDo() {
        scheduler.schedule(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true))
        assertEquals(NetworkType.CONNECTED, wakeupNetworkType())
    }

    @Test
    fun turningWifiOnlyOnReplacesThePendingWakeup() {
        scheduler.schedule(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true))
        scheduler.schedule(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, requiresNetwork = true, unmetered = true))
        assertEquals(NetworkType.UNMETERED, wakeupNetworkType())
        scheduler.schedule(null)
        assertTrue(workManager.getWorkInfosForUniqueWork(WorkManagerScheduler.WAKEUP).get().all { it.state.isFinished })
    }
}
