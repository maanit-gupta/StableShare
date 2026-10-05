package com.maanit.stableshare.data.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    /** Chunks in flight at once within one transfer (1, 2 or 4); read once when a job starts. */
    val parallelChunks: Int = SettingsRepository.DEFAULT_PARALLEL_CHUNKS,
    /** The selected server; [serverUrl] is the URL it resolves to. */
    val serverProfile: ServerProfile = ServerProfile.HOSTED,
    /** Kept while another profile is selected, so switching back restores them. */
    val lanHost: String = "",
    val lanPort: Int = ServerProfiles.DEFAULT_LAN_PORT,
    val customUrl: String = "",
    /** False until a server was picked on first launch; existing users are migrated to true. */
    val serverChosen: Boolean = false,
)

/** User settings in DataStore. Values are validated on write and sanitised again on read. */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<Settings> = store.data
        .map { prefs ->
            val choice = ServerChoice(
                profile = prefs[KEY_SERVER_PROFILE]?.let { name -> ServerProfile.entries.find { it.name == name } }
                    ?: ServerProfile.HOSTED,
                lanHost = prefs[KEY_LAN_HOST] ?: "",
                lanPort = prefs[KEY_LAN_PORT] ?: ServerProfiles.DEFAULT_LAN_PORT,
                customUrl = prefs[KEY_CUSTOM_URL] ?: "",
            )
            Settings(
                serverUrl = ServerProfiles.urlOf(choice) ?: DEFAULT_SERVER_URL,
                maxConcurrent = (prefs[KEY_MAX_CONCURRENT] ?: DEFAULT_MAX_CONCURRENT)
                    .coerceIn(MIN_CONCURRENT, MAX_CONCURRENT),
                uploadChunkSizeBytes = prefs[KEY_CHUNK_SIZE]?.takeIf { it in ALLOWED_CHUNK_SIZES }
                    ?: DEFAULT_CHUNK_SIZE,
                autoRetryEnabled = prefs[KEY_AUTO_RETRY] ?: true,
                wifiOnly = prefs[KEY_WIFI_ONLY] ?: false,
                onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
                parallelChunks = prefs[KEY_PARALLEL_CHUNKS]?.takeIf { it in ALLOWED_PARALLEL_CHUNKS }
                    ?: DEFAULT_PARALLEL_CHUNKS,
                serverProfile = choice.profile,
                lanHost = choice.lanHost,
                lanPort = choice.lanPort,
                customUrl = choice.customUrl,
                serverChosen = prefs[KEY_SERVER_CHOSEN] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    /**
     * A whole URL: picks the profile it belongs to ([ServerProfiles.classify]) and marks the
     * server chosen. Returns false (and stores nothing) if [url] is not a usable http(s) URL.
     */
    suspend fun setServerUrl(url: String): Boolean {
        val choice = ServerProfiles.classify(url) ?: return false
        store.edit { it.putChoice(choice); it[KEY_SERVER_CHOSEN] = true }
        return true
    }

    /** HOSTED or EMULATOR always; LAN and CUSTOM only if their saved value is usable. */
    suspend fun setServerProfile(profile: ServerProfile): Boolean {
        val s = current()
        if (ServerProfiles.urlOf(ServerChoice(profile, s.lanHost, s.lanPort, s.customUrl)) == null) return false
        store.edit { it[KEY_SERVER_PROFILE] = profile.name; it[KEY_SERVER_CHOSEN] = true }
        return true
    }

    /** Selects [choice] with the setter for its profile. Returns false (and stores nothing) if it is not usable. */
    suspend fun setServerChoice(choice: ServerChoice): Boolean = when (choice.profile) {
        ServerProfile.HOSTED, ServerProfile.EMULATOR -> setServerProfile(choice.profile)
        ServerProfile.LAN -> setLanServer(choice.lanHost, choice.lanPort)
        ServerProfile.CUSTOM -> setCustomUrl(choice.customUrl)
    }

    /** Remembers [ids] as paused by a switch away from [serverUrl], to resume when it is selected again. */
    suspend fun parkTransfers(serverUrl: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        store.edit { it[KEY_PARKED] = it[KEY_PARKED].orEmpty() + ids.map { id -> "$serverUrl $id" } }
    }

    /** The ids parked for [serverUrl]; they are forgotten here. */
    suspend fun takeParkedTransfers(serverUrl: String): List<String> {
        var taken = emptyList<String>()
        store.edit { prefs ->
            val (mine, others) = prefs[KEY_PARKED].orEmpty().partition { it.substringBeforeLast(' ') == serverUrl }
            taken = mine.map { it.substringAfterLast(' ') }
            prefs[KEY_PARKED] = others.toSet()
        }
        return taken
    }

    /** Selects LAN at `http://<host>:<port>`. Returns false (and stores nothing) if either is invalid. */
    suspend fun setLanServer(host: String, port: Int = ServerProfiles.DEFAULT_LAN_PORT): Boolean {
        ServerProfiles.lanUrl(host, port) ?: return false
        store.edit {
            it.putChoice(ServerChoice(ServerProfile.LAN, lanHost = host.trim(), lanPort = port))
            it[KEY_SERVER_CHOSEN] = true
        }
        return true
    }

    /** Selects CUSTOM at [url], normalised. Returns false (and stores nothing) if it is invalid. */
    suspend fun setCustomUrl(url: String): Boolean {
        val normalized = normalizeServerUrl(url) ?: return false
        store.edit {
            it.putChoice(ServerChoice(ServerProfile.CUSTOM, customUrl = normalized))
            it[KEY_SERVER_CHOSEN] = true
        }
        return true
    }

    suspend fun setServerChosen(chosen: Boolean) {
        store.edit { it[KEY_SERVER_CHOSEN] = chosen }
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

    suspend fun setParallelChunks(value: Int) {
        require(value in ALLOWED_PARALLEL_CHUNKS) { "parallel chunks must be one of $ALLOWED_PARALLEL_CHUNKS" }
        store.edit { it[KEY_PARALLEL_CHUNKS] = value }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    companion object {
        const val DEFAULT_SERVER_URL = ServerProfiles.HOSTED_URL
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 4
        const val DEFAULT_MAX_CONCURRENT = 2
        private const val MIB = 1024 * 1024
        const val DEFAULT_CHUNK_SIZE = 2 * MIB
        val ALLOWED_CHUNK_SIZES = listOf(1 * MIB, 2 * MIB, 5 * MIB)
        const val DEFAULT_PARALLEL_CHUNKS = 1
        val ALLOWED_PARALLEL_CHUNKS = listOf(1, 2, 4)

        /** Before server profiles; read once by [serverProfileMigration], then removed. */
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_SERVER_PROFILE = stringPreferencesKey("server_profile")
        private val KEY_LAN_HOST = stringPreferencesKey("lan_host")
        private val KEY_LAN_PORT = intPreferencesKey("lan_port")
        private val KEY_CUSTOM_URL = stringPreferencesKey("custom_url")
        private val KEY_SERVER_CHOSEN = booleanPreferencesKey("server_chosen")

        /** "<server url> <transfer id>" for each transfer a server switch paused (URLs hold no spaces). */
        private val KEY_PARKED = stringSetPreferencesKey("switch_parked_transfers")
        private val KEY_MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        private val KEY_CHUNK_SIZE = intPreferencesKey("upload_chunk_size_bytes")
        private val KEY_AUTO_RETRY = booleanPreferencesKey("auto_retry_enabled")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_PARALLEL_CHUNKS = intPreferencesKey("parallel_chunks")

        fun normalizeServerUrl(input: String): String? = ServerProfiles.normalizeServerUrl(input)

        /**
         * First run with server profiles: maps the old saved URL to a profile (an http:// URL to
         * the hosted host becomes HOSTED). Anyone with saved settings already picked a server,
         * so only a fresh install leaves serverChosen false.
         */
        val serverProfileMigration: DataMigration<Preferences> = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = currentData[KEY_SERVER_PROFILE] == null

            override suspend fun migrate(currentData: Preferences): Preferences {
                val prefs = currentData.toMutablePreferences()
                val choice = currentData[KEY_SERVER_URL]?.let(ServerProfiles::classify) ?: ServerChoice(ServerProfile.HOSTED)
                prefs.putChoice(choice)
                if (KEY_SERVER_CHOSEN !in currentData) prefs[KEY_SERVER_CHOSEN] = currentData.asMap().isNotEmpty()
                prefs.remove(KEY_SERVER_URL)
                return prefs.toPreferences()
            }

            override suspend fun cleanUp() = Unit
        }

        /** Writes the profile and the value it carries; the other profile's value is kept. */
        private fun MutablePreferences.putChoice(choice: ServerChoice) {
            this[KEY_SERVER_PROFILE] = choice.profile.name
            when (choice.profile) {
                ServerProfile.LAN -> { this[KEY_LAN_HOST] = choice.lanHost; this[KEY_LAN_PORT] = choice.lanPort }
                ServerProfile.CUSTOM -> this[KEY_CUSTOM_URL] = choice.customUrl
                ServerProfile.HOSTED, ServerProfile.EMULATOR -> Unit
            }
        }
    }
}
