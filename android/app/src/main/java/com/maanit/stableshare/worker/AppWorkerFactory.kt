package com.maanit.stableshare.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.maanit.stableshare.engine.TransferEngine
import com.maanit.stableshare.engine.TransferScheduler

/** Hands the process-wide engine to the workers (manual DI; see StableShareApp). */
class AppWorkerFactory(
    private val engine: () -> TransferEngine,
    private val scheduler: () -> TransferScheduler,
    private val notifications: () -> TransferNotifications,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            TransferCoordinatorWorker::class.java.name ->
                TransferCoordinatorWorker(appContext, workerParameters, engine(), notifications())
            CoordinatorWakeupWorker::class.java.name ->
                CoordinatorWakeupWorker(appContext, workerParameters, scheduler())
            else -> null
        }
}
