package com.maanit.stableshare.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.AndroidConnectivityChecker
import com.maanit.stableshare.data.net.ConnectivityChecker
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.net.ProtocolClient
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.domain.RetryPolicy
import com.maanit.stableshare.engine.AndroidConnectivityMonitor
import com.maanit.stableshare.engine.DownloadPipeline
import com.maanit.stableshare.engine.EngineBootstrap
import com.maanit.stableshare.engine.PipelineEnv
import com.maanit.stableshare.engine.RestoredTransfers
import com.maanit.stableshare.engine.TransferController
import com.maanit.stableshare.engine.TransferEngine
import com.maanit.stableshare.engine.TransferProgressTracker
import com.maanit.stableshare.engine.UploadPipeline
import com.maanit.stableshare.ui.LocalNetworkPermission
import com.maanit.stableshare.worker.AppWorkerFactory
import com.maanit.stableshare.worker.TransferNotifications
import com.maanit.stableshare.worker.WorkManagerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Manual dependency injection: one instance of each collaborator per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** UI facts that last for the life of the process (not persisted). */
    val session = UiSession()

    /** Outlives screens: cancel cleanup, bootstrap triggers. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") })
    }

    val transferRepository: TransferRepository by lazy { TransferRepository(database) }

    val fileStore: FileStore by lazy { FileStore(appContext) }

    val okHttpClient: OkHttpClient by lazy { ProtocolClient.buildOkHttp() }

    /** The base URL is read from settings on every call, so a change applies immediately. */
    val protocolClient: ProtocolClient by lazy {
        ProtocolClient(okHttpClient, baseUrl = { settingsRepository.current().serverUrl })
    }

    val connectivityChecker: ConnectivityChecker by lazy { AndroidConnectivityChecker(appContext) }

    val connectivityMonitor: AndroidConnectivityMonitor by lazy { AndroidConnectivityMonitor(appContext) }

    val errorClassifier: ErrorClassifier by lazy { ErrorClassifier(connectivityChecker) }

    val retryPolicy: RetryPolicy by lazy { RetryPolicy() }

    val progressTracker: TransferProgressTracker by lazy { TransferProgressTracker() }

    val restoredTransfers: RestoredTransfers by lazy { RestoredTransfers() }

    val notifications: TransferNotifications by lazy { TransferNotifications(appContext) }

    private val pipelineEnv: PipelineEnv by lazy {
        PipelineEnv(
            repo = transferRepository,
            classifier = errorClassifier,
            retryPolicy = retryPolicy,
            settings = { settingsRepository.current() },
            tracker = progressTracker,
            localNetworkGranted = { LocalNetworkPermission.isGranted(appContext) },
        )
    }

    val scheduler: WorkManagerScheduler by lazy {
        WorkManagerScheduler(
            workManager = { WorkManager.getInstance(appContext) },
            engineAcceptingWork = { transferEngine.isAcceptingWork() },
        )
    }

    val transferEngine: TransferEngine by lazy {
        TransferEngine(
            repo = transferRepository,
            settings = settingsRepository.settings,
            connectivity = connectivityMonitor,
            upload = UploadPipeline(protocolClient, fileStore, pipelineEnv),
            download = DownloadPipeline(protocolClient, fileStore, pipelineEnv),
            tracker = progressTracker,
            wakeups = scheduler,
            restored = restoredTransfers,
        )
    }

    val transferController: TransferController by lazy {
        TransferController(
            repo = transferRepository,
            api = protocolClient,
            files = fileStore,
            settings = { settingsRepository.current() },
            scheduler = scheduler,
            engine = transferEngine,
        )
    }

    val workerFactory: AppWorkerFactory by lazy {
        AppWorkerFactory(engine = { transferEngine }, scheduler = { scheduler }, notifications = { notifications })
    }

    val engineBootstrap: EngineBootstrap by lazy {
        EngineBootstrap(
            transferRepository, settingsRepository.settings, connectivityMonitor, scheduler, applicationScope, restoredTransfers,
        )
    }
}

/** Per-process UI state: the animated splash plays once per cold start; permissions are asked once per process. */
class UiSession {
    @Volatile var splashShown = false
    @Volatile var permissionsAsked = false
}
