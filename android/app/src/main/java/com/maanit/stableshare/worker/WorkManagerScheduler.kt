package com.maanit.stableshare.worker

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.maanit.stableshare.engine.TransferScheduler
import com.maanit.stableshare.engine.WakeupPlan
import com.maanit.stableshare.engine.WakeupScheduler
import java.util.concurrent.TimeUnit

/**
 * WorkManager plumbing for the coordinator (DESIGN.md §6).
 *
 * `ensureRunning` enqueues unique work "transfer-coordinator" with APPEND_OR_REPLACE. With KEEP, a
 * request arriving while the coordinator is deciding to exit (it has seen no work, but WorkManager
 * still reports it RUNNING) would be dropped and the new transfer stranded. APPEND chains a fresh
 * run after the current one; REPLACE takes over if the previous chain failed or was cancelled. When
 * the in-process engine is running and not exiting, nothing is enqueued: it sees the row itself.
 */
class WorkManagerScheduler(
    private val workManager: () -> WorkManager,
    private val engineAcceptingWork: () -> Boolean,
) : TransferScheduler, WakeupScheduler {

    override fun ensureRunning(userInitiated: Boolean) {
        if (engineAcceptingWork()) return
        val request = OneTimeWorkRequestBuilder<TransferCoordinatorWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(COORDINATOR)
            .build()
        workManager().enqueueUniqueWork(COORDINATOR, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedule(plan: WakeupPlan?) {
        if (plan == null) {
            workManager().cancelUniqueWork(WAKEUP)
            return
        }
        val constraints = Constraints.Builder()
            .apply { if (plan.requiresNetwork) setRequiredNetworkType(networkType(plan)) }
            .build()
        val request = OneTimeWorkRequestBuilder<CoordinatorWakeupWorker>()
            .setInitialDelay(plan.delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(WAKEUP)
            .build()
        workManager().enqueueUniqueWork(WAKEUP, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val COORDINATOR = "transfer-coordinator"
        const val WAKEUP = "transfer-coordinator-wakeup"

        /** Wi-Fi only waits for an unmetered network, otherwise any connected one. */
        fun networkType(plan: WakeupPlan): NetworkType =
            if (plan.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
    }
}
