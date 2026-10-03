package com.maanit.stableshare.engine

import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-level triggers for the coordinator, started from Application.onCreate:
 * leftover work at launch, the network coming back, and a maxConcurrent change.
 */
class EngineBootstrap(
    private val repo: TransferRepository,
    private val settings: Flow<Settings>,
    private val connectivity: ConnectivityMonitor,
    private val scheduler: TransferScheduler,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            val leftover = repo.getInStates(
                TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.RETRYING, TransferState.VERIFYING,
            )
            if (leftover.isNotEmpty()) scheduler.ensureRunning()
        }
        // Baseline taken now, not when the coroutine first runs, so an edge right after start counts.
        var wasOnline = connectivity.isOnline.value
        scope.launch {
            connectivity.isOnline.collect { online ->
                if (online && !wasOnline && repo.promoteWaitingForNetwork() > 0) scheduler.ensureRunning()
                wasOnline = online
            }
        }
        scope.launch {
            settings.map { it.maxConcurrent }.distinctUntilChanged().drop(1).collect { scheduler.ensureRunning() }
        }
    }
}
