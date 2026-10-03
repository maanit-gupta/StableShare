package com.maanit.stableshare.ui.debug

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.net.RemoteFile
import com.maanit.stableshare.data.net.TransferApi
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.SettingsRepository
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.engine.LiveProgress
import com.maanit.stableshare.engine.TransferController
import com.maanit.stableshare.engine.TransferProgressTracker
import com.maanit.stableshare.engine.TransferScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data object Reachable : ServerStatus
    data class Unreachable(val reason: String) : ServerStatus
}

data class TransferRow(val transfer: TransferEntity, val live: LiveProgress?)

/**
 * Temporary Phase 3 debug screen state (replaced in Phase 4). Actions run in the application
 * scope so a cancel's cleanup or a file generation survives leaving the screen.
 */
class DebugViewModel(
    repository: TransferRepository,
    settings: SettingsRepository,
    tracker: TransferProgressTracker,
    private val api: TransferApi,
    private val health: suspend () -> Boolean,
    private val controller: TransferController,
    private val scheduler: TransferScheduler,
    private val appScope: CoroutineScope,
) : ViewModel() {

    val transfers: StateFlow<List<TransferRow>> =
        combine(repository.observeTransfers(), tracker.progress) { rows, live -> rows.map { TransferRow(it, live[it.id]) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverUrl: StateFlow<String> = settings.settings.map { it.serverUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Checking)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    /** Progress or error text of the last background action (generation, manifest fetch…). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _status.value = ServerStatus.Checking
            _status.value = try {
                if (health()) ServerStatus.Reachable else ServerStatus.Unreachable("health ok=false")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerStatus.Unreachable(e.message ?: e::class.java.simpleName)
            }
            if (_status.value == ServerStatus.Reachable) {
                _files.value = runCatching { api.listFiles() }.getOrElse { emptyList() }
            }
            Log.i(TAG, "health check: ${_status.value}, ${_files.value.size} server file(s)")
        }
    }

    /** Called once the local-network permission is granted: anything stuck can try again. */
    fun onNetworkPermissionGranted() {
        refresh()
        scheduler.ensureRunning()
    }

    fun generateAndUpload(sizeMb: Int) = background("Generating $sizeMb MB file") {
        controller.uploadGeneratedFile(sizeMb) { written ->
            _message.value = "Generating $sizeMb MB file: ${written / (1024 * 1024)} MB"
        }
        "Queued upload of a generated $sizeMb MB file"
    }

    fun download(file: RemoteFile) = background("Fetching manifest of ${file.name}") {
        controller.download(file.fileId)
        "Queued download of ${file.name}"
    }

    fun perform(id: String, action: TransferAction) = background(null) {
        if (controller.perform(id, action)) null else "$action not applied: the transfer changed state meanwhile"
    }

    private fun background(start: String?, block: suspend () -> String?) {
        if (start != null) _message.value = start
        appScope.launch {
            _message.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "action failed", e)
                "Failed: ${e.message ?: e::class.java.simpleName}"
            }
        }
    }

    private companion object {
        const val TAG = "StableShare"
    }
}
