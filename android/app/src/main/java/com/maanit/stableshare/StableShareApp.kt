package com.maanit.stableshare

import android.app.Application
import androidx.work.Configuration
import com.maanit.stableshare.di.AppContainer

/**
 * Creates the AppContainer, provides WorkManager's configuration (on-demand initialisation, so the
 * workers get the engine through [com.maanit.stableshare.worker.AppWorkerFactory]) and starts the
 * engine's process-level triggers.
 */
class StableShareApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            // WorkManager's JobScheduler ids stay below the UIDT job's fixed id (UidtJobScheduler.JOB_ID).
            .setJobSchedulerJobIdRange(0, WORK_MANAGER_MAX_JOB_ID)
            .build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannel()
        container.connectivityMonitor.start()
        container.engineBootstrap.start()
        container.resultNotifier.start()
        container.demoController.start(container.applicationScope)
    }

    companion object {
        const val WORK_MANAGER_MAX_JOB_ID = 999_999
    }
}
