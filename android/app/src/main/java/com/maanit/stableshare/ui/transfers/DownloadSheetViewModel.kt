package com.maanit.stableshare.ui.transfers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.net.RemoteFile
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.model.ErrorCopy
import com.maanit.stableshare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface ServerFiles {
    data object Loading : ServerFiles
    data class Loaded(val files: List<RemoteFile>) : ServerFiles
    data object Unreachable : ServerFiles
}

/** Download sheet (UI-SPEC §5.7): the server's files and which of them already have a download. */
class DownloadSheetViewModel(
    private val listFiles: suspend () -> List<RemoteFile>,
    private val startDownload: suspend (String) -> Unit,
    repo: TransferRepository,
    settings: Flow<Settings>,
    private val classifier: ErrorClassifier,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _files = MutableStateFlow<ServerFiles>(ServerFiles.Loading)
    val files: StateFlow<ServerFiles> = _files.asStateFlow()

    /** Files tapped in this sheet; reverted if creating the download fails. */
    private val _added = MutableStateFlow<Set<String>>(emptySet())
    val added: StateFlow<Set<String>> = _added.asStateFlow()

    /** Server file ids that already have a non-terminal download. */
    val inProgress: StateFlow<Set<String>> = repo.observeTransfers()
        .map { rows ->
            rows.filter { it.type == TransferType.DOWNLOAD && !StateMachine.isTerminal(it.state) }
                .mapNotNullTo(HashSet()) { it.remoteId }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val serverUrl: StateFlow<String> = settings.map { it.serverUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val failures = Channel<UiText>(Channel.BUFFERED)

    /** "Couldn't add {name}. {short reason}" messages for the sheet's snackbar. */
    val failureMessages: Flow<UiText> = failures.receiveAsFlow()

    /** Called each time the sheet opens: a fresh list and no stale "Added" marks. */
    fun reset() {
        _added.value = emptySet()
        load()
    }

    fun load() {
        _files.value = ServerFiles.Loading
        viewModelScope.launch {
            _files.value = try {
                ServerFiles.Loaded(listFiles())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "listing server files failed: $e")
                ServerFiles.Unreachable
            }
        }
    }

    fun download(file: RemoteFile) {
        _added.update { it + file.fileId }
        appScope.launch {
            try {
                startDownload(file.fileId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _added.update { it - file.fileId }
                val code = runCatching { classifier.classify(e).code }.getOrDefault(ErrorCode.UNKNOWN)
                failures.trySend(UiText.res(com.maanit.stableshare.R.string.couldnt_add, file.name, ErrorCopy.short(code, MAX_TRIES)))
            }
        }
    }

    companion object {
        private const val TAG = "StableShare"

        /** "10.0.2.2:8080" — the host, with the port when it is not the scheme's default. */
        fun hostOf(serverUrl: String): String {
            val url = serverUrl.toHttpUrlOrNull() ?: return serverUrl
            return if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        }
    }
}
