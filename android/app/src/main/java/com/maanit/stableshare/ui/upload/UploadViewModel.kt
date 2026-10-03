package com.maanit.stableshare.ui.upload

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.R
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.LiveProgress
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.model.ErrorCopy
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What sits on the launch pad (UI-SPEC §5.6). */
sealed interface Selection {
    data object None : Selection

    /** A test file being written; [percent] 0–100. */
    data class Generating(val sizeMb: Int, val percent: Int) : Selection

    data class Ready(val uri: Uri, val name: String, val size: Long, val generated: Boolean) : Selection
}

/**
 * Upload screen state. Creating the transfer happens before the throw animation; the screen
 * reveals the new row at the "+1" moment via [reveal].
 */
class UploadViewModel(
    private val files: FileStore,
    private val uploadUri: suspend (Uri) -> TransferEntity,
    repo: TransferRepository,
    progress: Flow<Map<String, LiveProgress>>,
    restored: StateFlow<Set<String>>,
    private val classifier: ErrorClassifier,
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Writes a test file of the given MiB, reporting bytes written (FileStore.generateTestFile). */
    private val generateFile: suspend (Int, (Long) -> Unit) -> File = { mb, progress -> files.generateTestFile(mb, onProgress = progress) },
) : ViewModel() {

    private val _selection = MutableStateFlow<Selection>(Selection.None)
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    /** Ids created on this screen during this visit and already revealed, newest first. */
    private val revealed = MutableStateFlow<List<String>>(emptyList())

    private val failures = Channel<UiText>(Channel.BUFFERED)
    val failureMessages: Flow<UiText> = failures.receiveAsFlow()

    private var generation: Job? = null

    /** Uploads in QUEUED, TRANSFERRING, RETRYING, VERIFYING or PAUSED (the "In queue" pill). */
    val inQueue: StateFlow<Int> = repo.observeTransfers()
        .map { rows -> rows.count { it.type == TransferType.UPLOAD && it.state in QUEUE_STATES } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val ticker = flow {
        while (true) {
            emit(clock())
            delay(250)
        }
    }

    val rows: StateFlow<List<UploadRow>> = combine(repo.observeTransfers(), progress, restored, revealed, ticker) { all, live, restoredIds, ids, now ->
        val items = TransferItem.buildAll(all, live, restoredIds, now).associateBy { it.id }
        ids.mapNotNull { id -> items[id]?.let { UploadRow(it, files.isGeneratedFile(it.row.localUri)) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The system picker returned [uri]: keep a persistable grant and read its name and size. */
    fun pick(uri: Uri) {
        generation?.cancel()
        viewModelScope.launch {
            try {
                files.takePersistableReadPermission(uri)
                val info = files.querySource(uri)
                _selection.value = Selection.Ready(uri, info.name, info.size, generated = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _selection.value = Selection.None
                failures.trySend(couldntAdd(uri.lastPathSegment ?: uri.toString(), e))
            }
        }
    }

    /** Writes a test file of [sizeMb] MiB; the pad shows its progress, then the file. */
    fun generate(sizeMb: Int) {
        generation?.cancel()
        _selection.value = Selection.Generating(sizeMb, 0)
        val total = sizeMb.toLong() * FileStore.MIB
        generation = viewModelScope.launch {
            try {
                val file = generateFile(sizeMb) { written ->
                    _selection.update { s -> if (s is Selection.Generating) s.copy(percent = ((written * 100) / total).toInt()) else s }
                }
                _selection.value = Selection.Ready(Uri.fromFile(file), file.name, file.length(), generated = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _selection.value = Selection.None
                failures.trySend(couldntAdd("$sizeMb MB", e))
            }
        }
    }

    /**
     * Creates and enqueues the upload of the ready file (it survives leaving the screen). Returns
     * the new id, or null after posting "Couldn't add {name}. {reason}".
     */
    suspend fun create(): String? {
        val ready = _selection.value as? Selection.Ready ?: return null
        val result = appScope.async { runCatching { uploadUri(ready.uri) } }.await()
        return result.fold(
            onSuccess = { it.id },
            onFailure = { e ->
                if (e is CancellationException) throw e
                failures.trySend(couldntAdd(ready.name, e))
                null
            },
        )
    }

    /** The "+1" moment: the row joins the list and the pad empties. */
    fun reveal(id: String) {
        revealed.update { listOf(id) + it }
        _selection.value = Selection.None
    }

    private fun couldntAdd(name: String, e: Throwable): UiText {
        val code = runCatching { classifier.classify(e).code }.getOrDefault(ErrorCode.UNKNOWN)
        return UiText.res(R.string.couldnt_add, name, ErrorCopy.short(code, MAX_TRIES))
    }

    private companion object {
        val QUEUE_STATES = setOf(
            TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.RETRYING,
            TransferState.VERIFYING, TransferState.PAUSED,
        )
    }
}

data class UploadRow(val item: TransferItem, val generated: Boolean)
