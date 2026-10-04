package com.maanit.stableshare.worker

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.annotation.RequiresApi

/**
 * Schedules the user-initiated data transfer job (Android 14+, DESIGN.md §9). The network
 * constraint is mandatory for UIDT jobs; it is "any network" even with Wi-Fi only on, because
 * NetworkGuard already keeps the engine off metered networks and a lost unmetered constraint would
 * stop the job instead of letting the transfers wait. No other constraints (UIDT allows no delay).
 */
@RequiresApi(34)
class UidtJobScheduler(private val context: Context) {

    /** Null once scheduled; otherwise why not (the caller falls back to WorkManager). */
    fun schedule(): String? {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return "no JobScheduler"
        // Re-scheduling the same id would stop a job that is still winding down; WorkManager is
        // always safe here (a run that finds the lease held hands off to the holder).
        if (jobs.getPendingJob(JOB_ID) != null) return "the transfer job is already scheduled"
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, TransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        return if (jobs.schedule(info) == JobScheduler.RESULT_SUCCESS) null else "JobScheduler returned RESULT_FAILURE"
    }

    companion object {
        /** Outside WorkManager's JobScheduler id range (StableShareApp.WORK_MANAGER_MAX_JOB_ID). */
        const val JOB_ID = 1_000_001
    }
}
