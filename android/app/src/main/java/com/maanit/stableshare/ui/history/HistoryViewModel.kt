package com.maanit.stableshare.ui.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.R
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.model.ErrorCopy
import com.maanit.stableshare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HistoryFilter { ALL, COMPLETED, CANCELLED }

/** One finished row: timings follow the Details card (started = created, finished = completed or cancelled). */
data class HistoryItem(val row: TransferEntity, val generated: Boolean, val instant: Boolean = false, val demo: Boolean = false) {
    val finishedAt: Long get() = row.completedAt ?: row.updatedAt
    val durationMs: Long get() = (finishedAt - row.createdAt).coerceAtLeast(0)
    val bytes: Long get() = if (row.state == TransferState.COMPLETED) row.fileSize else row.bytesDone
    val bytesPerSecond: Double get() = if (durationMs > 0) bytes * 1_000.0 / durationMs else 0.0
}

/** Rows that finished on one local [date] (UI-SPEC §12.7 day headers). */
data class HistoryDay(val date: LocalDate, val items: List<HistoryItem>)

data class HistoryUi(
    val loaded: Boolean = false,
    val filter: HistoryFilter = HistoryFilter.ALL,
    /** Rows after the filter, newest first. */
    val items: List<HistoryItem> = emptyList(),
    /** [items] grouped by the day they finished, newest first. */
    val days: List<HistoryDay> = emptyList(),
    /** Rows per filter chip, not counting rows waiting on an Undo. */
    val counts: Map<HistoryFilter, Int> = emptyMap(),
    /** Every row in History, whatever the filter ("Clear all"). */
    val allIds: List<String> = emptyList(),
) {
    /** True if anything is in history at all (shows "Clear all" and the chips). */
    val hasHistory: Boolean get() = allIds.isNotEmpty()
}

/**
 * History (UI-SPEC §5.9, §12.7): finished and cancelled transfers. Removal hides rows at once and
 * deletes them (a repository delete of terminal rows) only when the screen [commit]s; [undo] shows
 * them again.
 */
class HistoryViewModel(
    private val repo: TransferRepository,
    private val isGenerated: (TransferEntity) -> Boolean,
    private val appScope: CoroutineScope,
    /** Ids a demo started (the "Demo" pill). */
    demoIds: Flow<Set<String>> = flowOf(emptySet()),
    /** Queues a new upload, as the Upload screen does. */
    private val uploadUri: suspend (Uri) -> TransferEntity = { error("upload not wired") },
    /** Queues a new download of a server file ID, as the Download sheet does. */
    private val download: suspend (String) -> TransferEntity = { error("download not wired") },
    /** Whether an upload source can still be read. */
    private val sourceReadable: suspend (Uri) -> Boolean = { false },
    private val classifier: ErrorClassifier? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilter.ALL)
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    val ui: StateFlow<HistoryUi> = combine(repo.observeHistory(), repo.observeInstantUploadIds(), demoIds, filter, hidden) { all, instantIds, demo, f, gone ->
        val rows = all.filter { it.id !in gone }
        val completed = rows.filter { it.state == TransferState.COMPLETED }
        val cancelled = rows.filter { it.state == TransferState.CANCELLED }
        val shown = when (f) {
            HistoryFilter.ALL -> rows
            HistoryFilter.COMPLETED -> completed
            HistoryFilter.CANCELLED -> cancelled
        }
        val items = shown.map { HistoryItem(it, isGenerated(it), it.id in instantIds, it.id in demo) }.sortedByDescending { it.finishedAt }
        val z = zone()
        HistoryUi(
            loaded = true,
            filter = f,
            items = items,
            days = items.groupBy { Instant.ofEpochMilli(it.finishedAt).atZone(z).toLocalDate() }.map { (date, day) -> HistoryDay(date, day) },
            counts = mapOf(HistoryFilter.ALL to rows.size, HistoryFilter.COMPLETED to completed.size, HistoryFilter.CANCELLED to cancelled.size),
            allIds = rows.map { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    val selectedFilter: StateFlow<HistoryFilter> = filter.asStateFlow()

    fun setFilter(f: HistoryFilter) {
        filter.value = f
    }

    /** Hides [ids] until [commit] or [undo]. */
    fun hide(ids: Collection<String>) {
        hidden.update { it + ids }
    }

    fun undo(ids: Collection<String>) {
        hidden.update { it - ids.toSet() }
    }

    /** Deletes the hidden rows for good (outlives the screen). */
    fun commit(ids: Collection<String>) {
        appScope.launch {
            ids.forEach { repo.deleteTransfer(it) }
            hidden.update { it - ids.toSet() }
        }
    }

    /** "Upload again" while the source is readable; "Download again" while the row has a server file ID. */
    suspend fun canRepeat(row: TransferEntity): Boolean = when (row.type) {
        TransferType.UPLOAD -> runCatching { sourceReadable(Uri.parse(row.localUri)) }.getOrDefault(false)
        TransferType.DOWNLOAD -> row.remoteId != null
    }

    /** Queues [row] again as a new transfer. Returns null on success, else "Couldn't add {name}. {reason}". */
    suspend fun repeat(row: TransferEntity): UiText? {
        val result = appScope.async {
            runCatching {
                when (row.type) {
                    TransferType.UPLOAD -> uploadUri(Uri.parse(row.localUri))
                    TransferType.DOWNLOAD -> download(requireNotNull(row.remoteId))
                }
            }
        }.await()
        val e = result.exceptionOrNull() ?: return null
        if (e is CancellationException) throw e
        val code = runCatching { classifier?.classify(e)?.code }.getOrNull() ?: ErrorCode.UNKNOWN
        return UiText.res(R.string.couldnt_add, row.fileName, ErrorCopy.short(code, MAX_TRIES))
    }
}
