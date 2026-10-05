package com.maanit.stableshare.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryFilter { ALL, COMPLETED, CANCELLED }

/** One finished row: timings follow the Details card (started = created, finished = completed or cancelled). */
data class HistoryItem(val row: TransferEntity, val generated: Boolean, val instant: Boolean = false, val demo: Boolean = false) {
    val finishedAt: Long get() = row.completedAt ?: row.updatedAt
    val durationMs: Long get() = (finishedAt - row.createdAt).coerceAtLeast(0)
    val bytes: Long get() = if (row.state == TransferState.COMPLETED) row.fileSize else row.bytesDone
    val bytesPerSecond: Double get() = if (durationMs > 0) bytes * 1_000.0 / durationMs else 0.0
}

data class HistoryUi(
    val loaded: Boolean = false,
    val filter: HistoryFilter = HistoryFilter.ALL,
    /** Rows after the filter, newest first. */
    val items: List<HistoryItem> = emptyList(),
    /** True if anything is in history at all (shows "Clear all" and the chips). */
    val hasHistory: Boolean = false,
)

/** History (UI-SPEC §5.9): finished and cancelled transfers. Removal is a repository delete of terminal rows. */
class HistoryViewModel(
    private val repo: TransferRepository,
    private val isGenerated: (TransferEntity) -> Boolean,
    private val appScope: CoroutineScope,
    /** Ids a demo started (the "Demo" pill). */
    demoIds: Flow<Set<String>> = flowOf(emptySet()),
) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilter.ALL)

    val ui: StateFlow<HistoryUi> = combine(repo.observeHistory(), repo.observeInstantUploadIds(), demoIds, filter) { rows, instantIds, demo, f ->
        val shown = when (f) {
            HistoryFilter.ALL -> rows
            HistoryFilter.COMPLETED -> rows.filter { it.state == TransferState.COMPLETED }
            HistoryFilter.CANCELLED -> rows.filter { it.state == TransferState.CANCELLED }
        }
        HistoryUi(
            loaded = true,
            filter = f,
            items = shown.map { HistoryItem(it, isGenerated(it), it.id in instantIds, it.id in demo) }.sortedByDescending { it.finishedAt },
            hasHistory = rows.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    val selectedFilter: StateFlow<HistoryFilter> = filter.asStateFlow()

    fun setFilter(f: HistoryFilter) {
        filter.value = f
    }

    fun remove(id: String) {
        appScope.launch { repo.deleteTransfer(id) }
    }

    fun clearAll() {
        appScope.launch { repo.clearHistory() }
    }
}
