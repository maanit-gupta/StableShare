package com.maanit.stableshare.ui.transfers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.TransferController
import com.maanit.stableshare.engine.TransferProgressTracker
import com.maanit.stableshare.ui.model.Condition
import com.maanit.stableshare.ui.model.TransferItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransfersUi(
    val loaded: Boolean = false,
    val active: List<TransferItem> = emptyList(),
    val waiting: List<TransferItem> = emptyList(),
    val attention: List<TransferItem> = emptyList(),
    /** TRANSFERRING + VERIFYING + RETRYING. */
    val activeCount: Int = 0,
    /** QUEUED + PAUSED. */
    val waitingCount: Int = 0,
    val speed: Double? = null,
    val limit: Int = 2,
    val serverUrl: String = "",
    /** Upload ids whose source is a generated test file (tagged "BIN"). */
    val generated: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = loaded && active.isEmpty() && waiting.isEmpty() && attention.isEmpty()
}

/**
 * Transfers screen (UI-SPEC §5.4). Rows combine the database, live progress, the restored set
 * and a 250 ms clock (countdowns, the 2.5 s COMPLETED linger). Actions run in the application
 * scope so a cancel's cleanup survives leaving the screen; the state machine decides what they do.
 */
class TransfersViewModel(
    repo: TransferRepository,
    tracker: TransferProgressTracker,
    restored: StateFlow<Set<String>>,
    settings: Flow<Settings>,
    val isOnline: StateFlow<Boolean>,
    private val health: suspend () -> Boolean,
    private val controller: TransferController,
    private val isGenerated: (TransferEntity) -> Boolean,
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** When this screen first saw each row as COMPLETED (rows already complete on arrival are never shown). */
    private val completedSeenAt = HashMap<String, Long>()
    private val lastStates = HashMap<String, TransferState>()

    private val ticker = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    val ui: StateFlow<TransfersUi> = combine(
        repo.observeTransfers(),
        tracker.progress,
        restored,
        settings,
        ticker,
    ) { rows, live, restoredIds, s, now ->
        val items = TransferItem.buildAll(rows, live, restoredIds, now).associateBy { it.id }
        val visible = rows.filter { visible(it, now) }
        fun sorted(list: List<TransferEntity>) = list.sortedWith(compareBy({ it.createdAt }, { it.id })).map { items.getValue(it.id) }
        val active = sorted(visible.filter { it.state in ACTIVE || it.state == TransferState.COMPLETED })
        val queued = sorted(visible.filter { it.state == TransferState.QUEUED })
        val paused = sorted(visible.filter { it.state == TransferState.PAUSED })
        val failed = sorted(visible.filter { it.state == TransferState.FAILED })
        val speed = items.values.filter { it.condition == Condition.TRANSFERRING }.sumOf { it.liveSpeed ?: 0.0 }
        TransfersUi(
            loaded = true,
            active = active,
            waiting = queued + paused,
            attention = failed,
            activeCount = rows.count { it.state in ACTIVE },
            waitingCount = rows.count { it.state == TransferState.QUEUED || it.state == TransferState.PAUSED },
            speed = speed.takeIf { it > 0 },
            limit = s.maxConcurrent,
            serverUrl = s.serverUrl,
            generated = rows.filter { it.type == TransferType.UPLOAD && isGenerated(it) }.mapTo(HashSet()) { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransfersUi())

    private val _serverReachable = MutableStateFlow(true)

    /** False after a failed GET /health (the "Can't reach the server" banner). */
    val serverReachable: StateFlow<Boolean> = _serverReachable.asStateFlow()

    /** Non-terminal rows show; COMPLETED rows linger 2.5 s after this screen saw them finish. */
    private fun visible(row: TransferEntity, now: Long): Boolean {
        val previous = lastStates.put(row.id, row.state)
        return when (row.state) {
            TransferState.CANCELLED -> false
            TransferState.COMPLETED -> {
                if (previous != null && previous != TransferState.COMPLETED) completedSeenAt.putIfAbsent(row.id, now)
                val seen = completedSeenAt[row.id] ?: return false
                now - seen < COMPLETED_LINGER_MS
            }
            else -> true
        }
    }

    fun checkHealth() {
        viewModelScope.launch {
            _serverReachable.value = try {
                health()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "health check failed: ${e.message}")
                false
            }
        }
    }

    fun perform(id: String, action: TransferAction) {
        appScope.launch {
            runCatching { controller.perform(id, action) }.onFailure { Log.w(TAG, "$action on $id failed", it) }
        }
    }

    companion object {
        const val COMPLETED_LINGER_MS = 2_500L
        const val TICK_MS = 250L
        const val HEALTH_INTERVAL_MS = 30_000L
        private const val TAG = "StableShare"
        private val ACTIVE = setOf(TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.RETRYING)
    }
}
