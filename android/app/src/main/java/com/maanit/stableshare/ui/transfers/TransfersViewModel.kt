package com.maanit.stableshare.ui.transfers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.ServerProfile
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.DemoController
import com.maanit.stableshare.engine.NetworkState
import com.maanit.stableshare.engine.TransferController
import com.maanit.stableshare.engine.TransferProgressTracker
import com.maanit.stableshare.ui.model.Condition
import com.maanit.stableshare.ui.model.TransferItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    val serverProfile: ServerProfile = ServerProfile.HOSTED,
    /** True until settings load, so the first-run server sheet never flashes. */
    val serverChosen: Boolean = true,
    /** The Wi-Fi only setting (the banner shows when it is on over a metered network). */
    val wifiOnly: Boolean = false,
    /** Upload ids whose source is a generated test file (tagged "BIN"). */
    val generated: Set<String> = emptySet(),
    /** Ids a demo started (the "Demo" chip). */
    val demo: Set<String> = emptySet(),
    /** The Big upload demo showing its one-time tip, if any. */
    val tipFor: String? = null,
    /** DONE chunks per transfer id (the Active card details). */
    val donePieces: Map<String, Int> = emptyMap(),
    /** Times each transfer was resumed from PAUSED (the Active card details). */
    val resumes: Map<String, Int> = emptyMap(),
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
    val networkState: StateFlow<NetworkState>,
    /** GET /health on a server (Waking first for HOSTED); completes after one final result. */
    private val checkHealth: (ServerProfile, String) -> Flow<ServerHealth>,
    private val setLimit: suspend (Int) -> Unit,
    private val controller: TransferController,
    private val isGenerated: (TransferEntity) -> Boolean,
    private val appScope: CoroutineScope,
    demoIds: Flow<Set<String>> = flowOf(emptySet()),
    tipDismissed: Flow<Boolean> = flowOf(true),
    private val dismissDemoTip: suspend () -> Unit = {},
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
        combine(repo.observeTransfers(), repo.observeInstantUploadIds(), repo.observeDoneChunkCounts(), repo.observeResumeCounts(), ::Quad),
        tracker.progress,
        restored,
        combine(settings, networkState, demoIds, tipDismissed, ::Quad),
        ticker,
    ) { (rows, instantIds, donePieces, resumes), live, restoredIds, (s, network, demo, tipGone), now ->
        val wifiGated = TransferItem.wifiGated(s.wifiOnly, network)
        val items = TransferItem.buildAll(rows, live, restoredIds, now, wifiGated, instantIds).associateBy { it.id }
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
            serverProfile = s.serverProfile,
            serverChosen = s.serverChosen,
            wifiOnly = s.wifiOnly,
            generated = rows.filter { it.type == TransferType.UPLOAD && isGenerated(it) }.mapTo(HashSet()) { it.id },
            demo = demo,
            tipFor = if (tipGone) null else (active + queued + paused).firstOrNull { showsTip(it, demo) }?.id,
            donePieces = donePieces,
            resumes = resumes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransfersUi())

    private val _serverHealth = MutableStateFlow<ServerHealth?>(null)

    /** The selected server's last health result; null until the first check answers (the status pill). */
    val serverHealth: StateFlow<ServerHealth?> = _serverHealth.asStateFlow()

    /** False after a failed health check (the "Can't reach the server" banner). */
    val serverReachable: StateFlow<Boolean> = _serverHealth
        .map { it !is ServerHealth.Unreachable && it != ServerHealth.Invalid }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var healthJob: Job? = null
    private var checkedUrl: String? = null
    private var checkedAt = 0L

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

    /**
     * Checks the selected server: at once when it changed, otherwise at most once per
     * [HEALTH_INTERVAL_MS] and never while a check (a hosted wake-up can take a minute) is running.
     */
    fun refreshHealth() {
        val s = ui.value.takeIf { it.loaded } ?: return
        val now = clock()
        if (s.serverUrl == checkedUrl && (healthJob?.isActive == true || now - checkedAt < HEALTH_INTERVAL_MS)) return
        healthJob?.cancel()
        if (s.serverUrl != checkedUrl) _serverHealth.value = null
        checkedUrl = s.serverUrl
        checkedAt = now
        healthJob = viewModelScope.launch {
            checkHealth(s.serverProfile, s.serverUrl).collect {
                if (it is ServerHealth.Unreachable) Log.i(TAG, "health check failed: ${it.reason}")
                _serverHealth.value = it
            }
        }
    }

    fun dismissTip() {
        appScope.launch { dismissDemoTip() }
    }

    fun setMaxConcurrent(n: Int) {
        viewModelScope.launch { setLimit(n) }
    }

    fun perform(id: String, action: TransferAction) {
        appScope.launch {
            runCatching { controller.perform(id, action) }.onFailure { Log.w(TAG, "$action on $id failed", it) }
        }
    }

    /** The Big upload demo, from 10% until it finishes. */
    private fun showsTip(item: TransferItem, demo: Set<String>): Boolean =
        item.id in demo && item.type == TransferType.UPLOAD && DemoController.isBigUpload(item.name) &&
            item.state != TransferState.COMPLETED && item.percent >= TIP_AT_PERCENT

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        const val TIP_AT_PERCENT = 10
        const val COMPLETED_LINGER_MS = 2_500L
        const val TICK_MS = 250L
        const val HEALTH_INTERVAL_MS = 30_000L
        private const val TAG = "StableShare"
        private val ACTIVE = setOf(TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.RETRYING)
    }
}
