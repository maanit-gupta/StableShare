package com.maanit.stableshare.worker

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import androidx.annotation.RequiresApi
import com.maanit.stableshare.StableShareApp
import com.maanit.stableshare.engine.CoordinatorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Hosts the coordinator loop in a user-initiated data transfer job (Android 14+, DESIGN.md §9).
 * It shows the same ongoing notification as the WorkManager host (its own id, removed when the
 * job ends); completion and failure notifications stay with TransferResultNotifier. Stops are
 * mapped by [TransferJobHost]; the job is never rescheduled by the system (onStopJob → false).
 */
@RequiresApi(34)
class TransferJobService : JobService() {
    private var scope: CoroutineScope? = null
    private var host: TransferJobHost? = null

    @OptIn(FlowPreview::class)
    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as StableShareApp).container
        val notifications = container.notifications
        show(params, notifications.build(CoordinatorStatus(0, 0, 0)))
        val host = TransferJobHost(container.transferEngine, container.scheduler).also { host = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        scope.launch {
            val updates = launch {
                container.transferEngine.status().sample(NOTIFICATION_INTERVAL_MS).collect { show(params, notifications.build(it)) }
            }
            val ran = try {
                host.run()
            } finally {
                updates.cancel()
            }
            if (!ran) Log.i(TAG, "transfer job: another host is running the loop")
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        host?.stopped(params.stopReason)
        scope?.cancel()
        return false
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private fun show(params: JobParameters, notification: android.app.Notification) {
        setNotification(params, TransferNotifications.JOB_NOTIFICATION_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
    }

    private companion object {
        const val TAG = "StableShare"
        const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}
