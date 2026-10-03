package com.maanit.stableshare.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class Settings(
    val serverUrl: String = SettingsRepository.DEFAULT_SERVER_URL,
    /** Transfers running at once, 1–4. Applies at the next slot acquisition. */
    val maxConcurrent: Int = SettingsRepository.DEFAULT_MAX_CONCURRENT,
    /** Chunk size for new uploads (and download manifests); existing transfers keep theirs. */
    val uploadChunkSizeBytes: Int = SettingsRepository.DEFAULT_CHUNK_SIZE,
    val autoRetryEnabled: Boolean = true,
    /** Transfers move only over unmetered networks (Wi-Fi); on a metered one they wait. */
    val wifiOnly: Boolean = false,
    /** Set once the first-run intro was finished or skipped (UI-SPEC §5.2). */
    val onboardingCompleted: Boolean = false,
)

/** User settings in DataStore. Values are validated on write and sanitised again on read. */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<Settings> = store.data
        .map { prefs ->
            Settings(
                serverUrl = prefs[KEY_SERVER_URL]?.let(::normalizeServerUrl) ?: DEFAULT_SERVER_URL,
                maxConcurrent = (prefs[KEY_MAX_CONCURRENT] ?: DEFAULT_MAX_CONCURRENT)
                    .coerceIn(MIN_CONCURRENT, MAX_CONCURRENT),
                uploadChunkSizeBytes = prefs[KEY_CHUNK_SIZE]?.takeIf { it in ALLOWED_CHUNK_SIZES }
                    ?: DEFAULT_CHUNK_SIZE,
                autoRetryEnabled = prefs[KEY_AUTO_RETRY] ?: true,
                wifiOnly = prefs[KEY_WIFI_ONLY] ?: false,
                onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    /** Returns false (and stores nothing) if [url] is not a usable http(s) URL. */
    suspend fun setServerUrl(url: String): Boolean {
        val normalized = normalizeServerUrl(url) ?: return false
        store.edit { it[KEY_SERVER_URL] = normalized }
        return true
    }

    /** Clamped to 1–4. */
    suspend fun setMaxConcurrent(value: Int) {
        store.edit { it[KEY_MAX_CONCURRENT] = value.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT) }
    }

    suspend fun setUploadChunkSizeBytes(bytes: Int) {
        require(bytes in ALLOWED_CHUNK_SIZES) { "chunk size must be one of $ALLOWED_CHUNK_SIZES" }
        store.edit { it[KEY_CHUNK_SIZE] = bytes }
    }

    suspend fun setAutoRetryEnabled(enabled: Boolean) {
        store.edit { it[KEY_AUTO_RETRY] = enabled }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        store.edit { it[KEY_WIFI_ONLY] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 4
        const val DEFAULT_MAX_CONCURRENT = 2
        private const val MIB = 1024 * 1024
        const val DEFAULT_CHUNK_SIZE = 2 * MIB
        val ALLOWED_CHUNK_SIZES = listOf(1 * MIB, 2 * MIB, 5 * MIB)

        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        private val KEY_CHUNK_SIZE = intPreferencesKey("upload_chunk_size_bytes")
        private val KEY_AUTO_RETRY = booleanPreferencesKey("auto_retry_enabled")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        /** Trims, adds http:// when no scheme is given, drops a trailing slash; null if invalid. */
        fun normalizeServerUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.query != null || url.fragment != null) return null
            return url.toString().trimEnd('/')
        }
    }
}
