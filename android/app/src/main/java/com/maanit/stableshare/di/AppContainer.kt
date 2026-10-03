package com.maanit.stableshare.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.AndroidConnectivityChecker
import com.maanit.stableshare.data.net.ConnectivityChecker
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.net.ProtocolClient
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.domain.RetryPolicy
import okhttp3.OkHttpClient

/** Manual dependency injection: one instance of each collaborator per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

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

    val errorClassifier: ErrorClassifier by lazy { ErrorClassifier(connectivityChecker) }

    val retryPolicy: RetryPolicy by lazy { RetryPolicy() }
}
