package com.maanit.stableshare.di

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.net.ProtocolClient
import com.maanit.stableshare.data.net.ServerHealthChecker
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.domain.RetryPolicy
import com.maanit.stableshare.engine.AndroidConnectivityMonitor
import com.maanit.stableshare.engine.DownloadPipeline
import com.maanit.stableshare.engine.EngineBootstrap
import com.maanit.stableshare.engine.GuardedTransferApi
import com.maanit.stableshare.engine.HostSelectingScheduler
import com.maanit.stableshare.engine.NetworkGuard
import com.maanit.stableshare.engine.NetworkState
import com.maanit.stableshare.engine.PipelineEnv
import com.maanit.stableshare.engine.PreviousProcessExit
import com.maanit.stableshare.engine.RestoredTransfers
import com.maanit.stableshare.engine.RunLease
import com.maanit.stableshare.engine.ServerSwitch
import com.maanit.stableshare.engine.TransferController
import com.maanit.stableshare.engine.TransferEngine
import com.maanit.stableshare.engine.TransferProgressTracker
import com.maanit.stableshare.engine.TransferScheduler
import com.maanit.stableshare.engine.UploadPipeline
import com.maanit.stableshare.ui.LocalNetworkPermission
import com.maanit.stableshare.worker.AppWorkerFactory
import com.maanit.stableshare.worker.TransferNotifications
import com.maanit.stableshare.worker.TransferResultNotifier
import com.maanit.stableshare.worker.UidtJobScheduler
import com.maanit.stableshare.worker.WorkManagerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
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
        SettingsRepository(
            PreferenceDataStoreFactory.create(migrations = listOf(SettingsRepository.serverProfileMigration)) {
                appContext.preferencesDataStoreFile("settings")
            },
        )
    }

    val transferRepository: TransferRepository by lazy { TransferRepository(database) }

    val fileStore: FileStore by lazy { FileStore(appContext) }

    val okHttpClient: OkHttpClient by lazy { ProtocolClient.buildOkHttp() }

    /** The base URL is read from settings on every call, so a change applies immediately. */
    val protocolClient: ProtocolClient by lazy {
        ProtocolClient(okHttpClient, baseUrl = { settingsRepository.current().serverUrl })
    }

    val connectivityMonitor: AndroidConnectivityMonitor by lazy {
        AndroidConnectivityMonitor(appContext, settingsRepository.settings.map { it.wifiOnly }, applicationScope)
    }

    val serverHealthChecker: ServerHealthChecker by lazy {
        ServerHealthChecker(okHttpClient, isOnline = { connectivityMonitor.networkState.value != NetworkState.Offline })
    }

    val errorClassifier: ErrorClassifier by lazy { ErrorClassifier(connectivityMonitor) }

    private val networkGuard: NetworkGuard by lazy { NetworkGuard(connectivityMonitor) }

    /** The pipelines' client: no request moves over a network transfers may not use (Wi-Fi only). */
    private val pipelineApi by lazy { GuardedTransferApi(protocolClient, networkGuard) }

    val retryPolicy: RetryPolicy by lazy { RetryPolicy() }

    val progressTracker: TransferProgressTracker by lazy { TransferProgressTracker() }

    val restoredTransfers: RestoredTransfers by lazy { RestoredTransfers() }

    val notifications: TransferNotifications by lazy { TransferNotifications(appContext) }

    val resultNotifier: TransferResultNotifier by lazy {
        TransferResultNotifier(
            transfers = transferRepository.observeTransfers(),
            completed = notifications::completed,
            failed = notifications::failed,
            post = notifications::postResult,
            scope = applicationScope,
        )
    }

    private val pipelineEnv: PipelineEnv by lazy {
        PipelineEnv(
            repo = transferRepository,
            classifier = errorClassifier,
            retryPolicy = retryPolicy,
            settings = { settingsRepository.current() },
            tracker = progressTracker,
            network = networkGuard,
            localNetworkGranted = { LocalNetworkPermission.isGranted(appContext) },
        )
    }

    /** The coordinator's WorkManager host and every wake-up. */
    private val workManagerScheduler: WorkManagerScheduler by lazy {
        WorkManagerScheduler(
            workManager = { WorkManager.getInstance(appContext) },
            engineAcceptingWork = { transferEngine.isAcceptingWork() },
        )
    }

    /** Starts the coordinator: a user-initiated job for user actions on Android 14+, else WorkManager. */
    val scheduler: TransferScheduler by lazy {
        HostSelectingScheduler(
            sdkInt = Build.VERSION.SDK_INT,
            appVisible = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            leaseHeld = runLease::isHeld,
            engineAcceptingWork = { transferEngine.isAcceptingWork() },
            uidt = {
                if (Build.VERSION.SDK_INT >= HostSelectingScheduler.UIDT_MIN_SDK) UidtJobScheduler(appContext).schedule() else "below Android 14"
            },
            fallback = workManagerScheduler,
            log = { Log.w("StableShare", it) },
        )
    }

    /** One coordinator loop per process, whichever host (worker or job) starts it (DESIGN.md §9). */
    val runLease = RunLease()

    val transferEngine: TransferEngine by lazy {
        TransferEngine(
            repo = transferRepository,
            settings = settingsRepository.settings,
            connectivity = connectivityMonitor,
            upload = UploadPipeline(pipelineApi, fileStore, pipelineEnv),
            download = DownloadPipeline(pipelineApi, fileStore, pipelineEnv),
            tracker = progressTracker,
            wakeups = workManagerScheduler,
            restored = restoredTransfers,
            lease = runLease,
            previousExitByUser = { previousProcessExit.stoppedByUser },
        )
    }

    private val previousProcessExit by lazy { PreviousProcessExit(appContext) }

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

    val serverSwitch: ServerSwitch by lazy { ServerSwitch(settingsRepository, transferRepository, transferController) }

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
