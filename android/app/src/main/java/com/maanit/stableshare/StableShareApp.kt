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
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannel()
        container.connectivityMonitor.start()
        container.engineBootstrap.start()
        container.resultNotifier.start()
    }
}
