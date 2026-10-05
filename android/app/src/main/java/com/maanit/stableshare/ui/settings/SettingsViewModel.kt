package com.maanit.stableshare.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.R
import com.maanit.stableshare.data.net.FaultSettings
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.data.net.ServerStats
import com.maanit.stableshare.data.settings.ServerChoice
import com.maanit.stableshare.data.settings.ServerProfile
import com.maanit.stableshare.data.settings.ServerProfiles
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /** Waking (hosted, still pending) or a final result. */
    data class Result(val health: ServerHealth) : ConnectionCheck
}

/** Settings (UI-SPEC §5.10). Every value is written straight to DataStore and applies at once. */
class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val checkHealth: (ServerProfile, String) -> Flow<ServerHealth>,
    private val hasActiveTransfers: suspend () -> Boolean,
    /** Pauses and parks the old server's transfers, saves [ServerChoice], resumes the new one's. */
    private val switchServer: suspend (ServerChoice) -> Boolean,
    private val getFaults: suspend () -> FaultSettings,
    private val putFaults: suspend (FaultSettings) -> FaultSettings,
    private val resetFaults: suspend () -> FaultSettings,
    private val getStats: suspend () -> ServerStats,
) : ViewModel() {

    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _check = MutableStateFlow<ConnectionCheck>(ConnectionCheck.Idle)
    val check: StateFlow<ConnectionCheck> = _check.asStateFlow()
    private var checkJob: Job? = null

    /** A switch waiting for "Switch server?" because transfers are active. */
    private val _pendingSwitch = MutableStateFlow<ServerChoice?>(null)
    val pendingSwitch: StateFlow<ServerChoice?> = _pendingSwitch.asStateFlow()
    private var selecting: ServerChoice? = null

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

    /**
     * Saves [choice] and checks it. An unusable address shows Invalid and saves nothing; a change of
     * server while transfers are active waits for [confirmSwitch].
     */
    fun selectServer(choice: ServerChoice) {
        // Leaving a field and tapping Test both ask for the same choice.
        if (choice == selecting || choice == _pendingSwitch.value) return
        selecting = choice
        viewModelScope.launch {
            try {
                val url = ServerProfiles.urlOf(choice)
                if (url == null) {
                    checkJob?.cancel()
                    _check.value = ConnectionCheck.Result(ServerHealth.Invalid)
                } else if (url != settingsRepo.current().serverUrl && hasActiveTransfers()) {
                    _pendingSwitch.value = choice
                } else {
                    switchAndCheck(choice)
                }
            } finally {
                selecting = null
            }
        }
    }

    fun confirmSwitch() {
        val choice = _pendingSwitch.value ?: return
        _pendingSwitch.value = null
        viewModelScope.launch { switchAndCheck(choice) }
    }

    fun cancelSwitch() {
        _pendingSwitch.value = null
    }

    private suspend fun switchAndCheck(choice: ServerChoice) {
        if (switchServer(choice)) testConnection() else _check.value = ConnectionCheck.Result(ServerHealth.Invalid)
    }

    /** Checks the saved server; a new check replaces one still running. */
    fun testConnection() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _check.value = ConnectionCheck.Checking
            val s = settingsRepo.settings.first()
            checkHealth(s.serverProfile, s.serverUrl).collect { _check.value = ConnectionCheck.Result(it) }
        }
    }

    fun setMaxConcurrent(n: Int) = viewModelScope.launch { settingsRepo.setMaxConcurrent(n) }

    fun setParallelChunks(n: Int) = viewModelScope.launch { settingsRepo.setParallelChunks(n) }

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
