package com.maanit.stableshare.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.maanit.stableshare.engine.CoordinatorStatus
import com.maanit.stableshare.engine.TransferEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Thin WorkManager wrapper around [TransferEngine.run] (DESIGN.md §6). It runs as a dataSync
 * foreground service when the platform allows it, refreshing the notification at most once a
 * second; if the foreground start is refused, the work still runs.
 */
class TransferCoordinatorWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: TransferEngine,
    private val notifications: TransferNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(CoordinatorStatus(0, 0, 0))

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val updates = launch {
            if (!promote(CoordinatorStatus(0, 0, 0))) return@launch
            engine.status().sample(NOTIFICATION_INTERVAL_MS).collect { promote(it) }
        }
        try {
            engine.run(stopReason = { describeStopReason(stopReason) })
        } finally {
            updates.cancel()
        }
        Result.success()
    }

    /** setForeground can be refused (background start restrictions, Android 12+); work goes on. */
    private suspend fun promote(status: CoordinatorStatus): Boolean = try {
        setForeground(notifications.foregroundInfo(status))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "running without a foreground notification: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "StableShare"
        const val NOTIFICATION_INTERVAL_MS = 1_000L

        fun describeStopReason(reason: Int): String = when (reason) {
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground service timeout"
            WorkInfo.STOP_REASON_QUOTA -> "quota"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "connectivity constraint"
            WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "device state"
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system processing"
            WorkInfo.STOP_REASON_PREEMPT -> "preempted"
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by app"
            WorkInfo.STOP_REASON_USER -> "user"
            WorkInfo.STOP_REASON_APP_STANDBY -> "app standby"
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background restriction"
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "estimated launch time changed"
            WorkInfo.STOP_REASON_NOT_STOPPED -> "not stopped"
            else -> "stop reason $reason"
        }
    }
}
