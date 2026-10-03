package com.maanit.stableshare.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.net.ProtocolClient
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data object Reachable : ServerStatus
    data class Unreachable(val reason: String) : ServerStatus
}

/** Phase 2 smoke-test screen state: the transfer list from Room plus a server health check. */
class HomeViewModel(
    repository: TransferRepository,
    settings: SettingsRepository,
    private val client: ProtocolClient,
) : ViewModel() {

    val transfers: StateFlow<List<TransferEntity>> = repository.observeTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverUrl: StateFlow<String> = settings.settings.map { it.serverUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Checking)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    init {
        checkServer()
    }

    fun checkServer() {
        viewModelScope.launch {
            _status.value = ServerStatus.Checking
            _status.value = try {
                if (client.health().ok) ServerStatus.Reachable else ServerStatus.Unreachable("health ok=false")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerStatus.Unreachable(e.message ?: e::class.java.simpleName)
            }
            Log.i(TAG, "health check: ${_status.value}")
        }
    }

    private companion object {
        const val TAG = "StableShare"
    }
}
