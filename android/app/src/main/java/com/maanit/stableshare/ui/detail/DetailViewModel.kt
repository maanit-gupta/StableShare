package com.maanit.stableshare.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.data.db.ChunkEntity
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.engine.TransferController
import com.maanit.stableshare.engine.TransferProgressTracker
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.model.ActivityEntry
import com.maanit.stableshare.ui.model.ActivityMapper
import com.maanit.stableshare.ui.model.TransferItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the detail screen shows (UI-SPEC §5.8). */
data class DetailUi(
    val item: TransferItem,
    val chunks: List<ChunkEntity>,
    val doneChunks: Int,
    val failedChunks: Int,
    val activity: List<ActivityEntry>,
    /** Attempt number of the latest scheduled retry (RETRYING stats line). */
    val retryAttempt: Int,
    val maxConcurrent: Int,
    val verifiedSha: String?,
    val generated: Boolean,
    val now: Long,
)

sealed interface DetailState {
    data object Loading : DetailState
    data class Ready(val ui: DetailUi) : DetailState

    /** The row was removed (e.g. "Remove from history"). */
    data object Gone : DetailState
}

class DetailViewModel(
    private val id: String,
    repo: TransferRepository,
    tracker: TransferProgressTracker,
    restored: StateFlow<Set<String>>,
    settings: Flow<Settings>,
    private val controller: TransferController,
    private val isGenerated: (TransferEntity) -> Boolean,
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(clock())
            delay(250)
        }
    }

    private val stored = combine(repo.observeTransfers(), repo.observeChunks(id), repo.observeEvents(id)) { rows, chunks, events ->
        Triple(rows, chunks, events)
    }

    private val live = combine(tracker.progress, restored, settings, ticker) { progress, restoredIds, s, now ->
        LiveInputs(progress[id], id in restoredIds, s.maxConcurrent, now)
    }

    private data class LiveInputs(val progress: com.maanit.stableshare.engine.LiveProgress?, val restored: Boolean, val maxConcurrent: Int, val now: Long)

    val state: StateFlow<DetailState> = combine(stored, live) { (rows, chunks, events), l ->
        val row = rows.firstOrNull { it.id == id } ?: return@combine DetailState.Gone
        val position = TransferItem.queuePositions(rows)[id]
        val item = TransferItem.build(row, l.progress, position, l.restored, l.now)
        DetailState.Ready(
            DetailUi(
                item = item,
                chunks = chunks,
                doneChunks = chunks.count { it.status == ChunkStatus.DONE },
                failedChunks = chunks.count { it.status == ChunkStatus.FAILED },
                activity = ActivityMapper.map(events, MAX_TRIES),
                retryAttempt = ActivityMapper.latestRetryAttempt(events) ?: 1,
                maxConcurrent = l.maxConcurrent,
                verifiedSha = ActivityMapper.verifiedSha(events),
                generated = isGenerated(row),
                now = l.now,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    fun perform(action: TransferAction) {
        appScope.launch {
            runCatching { controller.perform(id, action) }.onFailure { Log.w("StableShare", "$action on $id failed", it) }
        }
    }
}
