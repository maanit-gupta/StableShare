package com.maanit.stableshare.worker

import android.app.job.JobParameters
import com.maanit.stableshare.engine.StopKind
import com.maanit.stableshare.engine.TransferEngine
import com.maanit.stableshare.engine.TransferScheduler
import com.maanit.stableshare.engine.ensureRunning
import kotlinx.coroutines.CancellationException

/**
 * The platform-free part of [TransferJobService]: runs the shared loop and maps a JobScheduler
 * stop (DESIGN.md §9). The service calls [stopped] with the stop reason, then cancels [run].
 * - STOP_REASON_USER (Task Manager, app settings): active transfers are paused, nothing restarts.
 * - STOP_REASON_CONSTRAINT_CONNECTIVITY: the usual wait for the network (RETRYING
 *   NETWORK_UNAVAILABLE); the network wake-up and the connectivity monitor resume it.
 * - Anything else: an interruption. Active transfers go back to QUEUED and the coordinator is
 *   asked to run again once, as a background start (WorkManager); Android's background
 *   foreground-service limits may delay that run, and the after-stop wake-up is the backstop.
 */
class TransferJobHost(
    private val engine: TransferEngine,
    private val scheduler: TransferScheduler,
) {
    @Volatile private var stopReason: Int? = null

    /** Records why the system stopped the job; call before cancelling [run]. */
    fun stopped(reason: Int) {
        stopReason = reason
    }

    /** One loop run; false if another host held the lease (it runs the loop instead). */
    suspend fun run(): Boolean = try {
        engine.run(stopReason = { describe(stopReason) }, stopKind = { kindFor(stopReason) })
    } catch (e: CancellationException) {
        // The lease is released by now, so this run can start.
        if (kindFor(stopReason) == StopKind.REQUEUE) scheduler.ensureRunning()
        throw e
    }

    companion object {
        fun kindFor(reason: Int?): StopKind = when (reason) {
            JobParameters.STOP_REASON_USER -> StopKind.PAUSE
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> StopKind.WAIT_FOR_NETWORK
            else -> StopKind.REQUEUE
        }

        fun describe(reason: Int?): String = when (reason) {
            null -> "job cancelled"
            JobParameters.STOP_REASON_USER -> "stopped by the user"
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "job connectivity constraint"
            JobParameters.STOP_REASON_TIMEOUT -> "job timeout"
            JobParameters.STOP_REASON_QUOTA -> "job quota"
            JobParameters.STOP_REASON_DEVICE_STATE -> "device state"
            JobParameters.STOP_REASON_SYSTEM_PROCESSING -> "system processing"
            JobParameters.STOP_REASON_PREEMPT -> "preempted"
            JobParameters.STOP_REASON_CANCELLED_BY_APP -> "cancelled by app"
            JobParameters.STOP_REASON_APP_STANDBY -> "app standby"
            JobParameters.STOP_REASON_BACKGROUND_RESTRICTION -> "background restriction"
            else -> "job stop reason $reason"
        }
    }
}
