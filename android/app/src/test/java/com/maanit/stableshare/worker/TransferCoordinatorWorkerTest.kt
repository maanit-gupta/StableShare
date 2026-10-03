package com.maanit.stableshare.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.engine.EngineHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferCoordinatorWorkerTest {

    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val harness by lazy { EngineHarness(context, tmp.newFolder(), System::currentTimeMillis) }

    @After
    fun tearDown() = harness.close()

    private fun factory(scheduled: MutableList<String> = mutableListOf()) = AppWorkerFactory(
        engine = { harness.engine },
        scheduler = { com.maanit.stableshare.engine.TransferScheduler { scheduled += "ensureRunning" } },
        notifications = { TransferNotifications(context).apply { ensureChannel() } },
    )

    @Test
    fun workerDrivesTheEngineToCompletionAndSucceeds() = runBlocking {
        val (up, upBytes) = harness.upload(3 * EngineHarness.CHUNK + 11)
        val (down, downBytes) = harness.download(2 * EngineHarness.CHUNK, seed = 5)

        val worker = TestListenableWorkerBuilder<TransferCoordinatorWorker>(context)
            .setWorkerFactory(factory())
            .build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(TransferState.COMPLETED, harness.state(up.id))
        assertEquals(TransferState.COMPLETED, harness.state(down.id))
        assertArrayEquals(upBytes, harness.server.assembled(up.id))
        assertArrayEquals(downBytes, harness.localFile(down.id).readBytes())
        assertTrue(harness.engine.activeIds.value.isEmpty())
    }

    @Test
    fun wakeupWorkerStartsTheCoordinator() = runBlocking {
        val calls = mutableListOf<String>()
        val worker = TestListenableWorkerBuilder<CoordinatorWakeupWorker>(context)
            .setWorkerFactory(factory(calls))
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf("ensureRunning"), calls)
    }

    @Test
    fun foregroundInfoUsesTheDataSyncType() {
        val info = TransferNotifications(context).apply { ensureChannel() }
            .foregroundInfo(com.maanit.stableshare.engine.CoordinatorStatus(2, 50, 200))
        assertEquals(TransferNotifications.NOTIFICATION_ID, info.notificationId)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertTrue(info.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }
}
