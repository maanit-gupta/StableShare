package com.maanit.stableshare.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.R
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.net.FaultSettings
import com.maanit.stableshare.data.net.ServerStats
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.model.ErrorCopy
import com.maanit.stableshare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Simulator presets (UI-SPEC §5.10). "Off" disables injection; every other preset enables it. */
enum class Preset(val label: Int, val faults: FaultSettings) {
    OFF(R.string.preset_off, FaultSettings(false, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    SLOW(R.string.preset_slow, FaultSettings(true, 800, 400, 512, 0.0, 0.0, 0.0, 0.0, 0.0)),
    FLAKY(R.string.preset_flaky, FaultSettings(true, 200, 100, 0, 0.15, 0.03, 0.05, 0.0, 0.0)),
    LOST(R.string.preset_lost, FaultSettings(true, 100, 50, 0, 0.0, 0.0, 0.0, 0.3, 0.0)),
    CORRUPTION(R.string.preset_corruption, FaultSettings(true, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.1)),
    CHAOS(R.string.preset_chaos, FaultSettings(true, 300, 200, 2048, 0.1, 0.02, 0.05, 0.05, 0.02)),
    ;

    companion object {
        /** The preset whose values match exactly, if any. */
        fun matching(f: FaultSettings): Preset? = entries.firstOrNull { it.faults == f }
    }
}

sealed interface ConnectionCheck {
    data object Idle : ConnectionCheck
    data object Checking : ConnectionCheck
    data object Connected : ConnectionCheck
    data class Failed(val reason: UiText) : ConnectionCheck
}

/** Settings (UI-SPEC §5.10). Every value is written straight to DataStore and applies at once. */
class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val health: suspend () -> Boolean,
    private val getFaults: suspend () -> FaultSettings,
    private val putFaults: suspend (FaultSettings) -> FaultSettings,
    private val resetFaults: suspend () -> FaultSettings,
    private val getStats: suspend () -> ServerStats,
    private val classifier: ErrorClassifier,
) : ViewModel() {

    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _check = MutableStateFlow<ConnectionCheck>(ConnectionCheck.Idle)
    val check: StateFlow<ConnectionCheck> = _check.asStateFlow()

    /** Slider values; null until GET /admin/faults answered. */
    private val _faults = MutableStateFlow<FaultSettings?>(null)
    val faults: StateFlow<FaultSettings?> = _faults.asStateFlow()

    private val _preset = MutableStateFlow<Preset?>(null)
    val preset: StateFlow<Preset?> = _preset.asStateFlow()

    private val _stats = MutableStateFlow<ServerStats?>(null)
    val stats: StateFlow<ServerStats?> = _stats.asStateFlow()

    private val messages = Channel<UiText>(Channel.BUFFERED)
    val snackbarMessages: Flow<UiText> = messages.receiveAsFlow()

    init {
        loadFaults()
    }

    /** Returns false (nothing saved) when [url] is not a usable address. */
    suspend fun saveServerUrl(url: String): Boolean = settingsRepo.setServerUrl(url)

    fun testConnection() {
        viewModelScope.launch {
            _check.value = ConnectionCheck.Checking
            _check.value = try {
                if (health()) ConnectionCheck.Connected else ConnectionCheck.Failed(ErrorCopy.short(ErrorCode.UNKNOWN, MAX_TRIES))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = runCatching { classifier.classify(e).code }.getOrDefault(ErrorCode.UNKNOWN)
                ConnectionCheck.Failed(ErrorCopy.short(code, MAX_TRIES))
            }
        }
    }

    fun setMaxConcurrent(n: Int) = viewModelScope.launch { settingsRepo.setMaxConcurrent(n) }

    fun setPieceSize(bytes: Int) = viewModelScope.launch { settingsRepo.setUploadChunkSizeBytes(bytes) }

    fun setAutoRetry(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoRetryEnabled(enabled) }

    /** The engine reacts by itself (EngineBootstrap restarts the coordinator, which reschedules its wake-up). */
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { settingsRepo.setWifiOnly(enabled) }

    fun loadFaults() {
        viewModelScope.launch {
            runCatching { getFaults() }.onSuccess {
                _faults.value = it
                _preset.value = Preset.matching(it)
            }
        }
    }

    fun selectPreset(p: Preset) {
        _preset.value = p
        _faults.value = p.faults
    }

    /** Editing a slider deselects the preset; injection is on whenever any value is non-zero. */
    fun editFaults(change: (FaultSettings) -> FaultSettings) {
        val current = _faults.value ?: Preset.OFF.faults
        val next = change(current)
        _faults.value = next.copy(enabled = next.copy(enabled = false) != Preset.OFF.faults.copy(enabled = false))
        _preset.value = null
    }

    fun apply() {
        val f = _faults.value ?: Preset.OFF.faults
        viewModelScope.launch {
            try {
                _faults.value = putFaults(f)
                messages.trySend(UiText.res(R.string.simulator_updated))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.trySend(UiText.res(R.string.simulator_unreachable))
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            try {
                _faults.value = resetFaults()
                _preset.value = Preset.OFF
                messages.trySend(UiText.res(R.string.simulator_was_reset))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.trySend(UiText.res(R.string.simulator_unreachable))
            }
        }
    }

    /** One GET /admin/stats; the screen calls it every 2 s while the simulator is on screen. */
    suspend fun refreshStats() {
        runCatching { getStats() }.onSuccess { _stats.value = it }
    }
}
