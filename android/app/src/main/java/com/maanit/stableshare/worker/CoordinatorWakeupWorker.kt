package com.maanit.stableshare.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maanit.stableshare.engine.TransferScheduler

/** Fires when a persisted backoff is due or the network returns; it only (re)starts the coordinator. */
class CoordinatorWakeupWorker(
    context: Context,
    params: WorkerParameters,
    private val scheduler: TransferScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        scheduler.ensureRunning()
        return Result.success()
    }
}
