package com.maanit.stableshare.engine

import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-level triggers for the coordinator, started from Application.onCreate: leftover work at
 * launch, a usable network coming back, a maxConcurrent or Wi-Fi only change. It keeps waiting
 * rows' codes in step with the network (offline vs metered) and the restored-after-restart set
 * free of finished or deleted transfers.
 */
class EngineBootstrap(
    private val repo: TransferRepository,
    private val settings: Flow<Settings>,
    private val connectivity: ConnectivityMonitor,
    private val scheduler: TransferScheduler,
    private val scope: CoroutineScope,
    private val restored: RestoredTransfers = RestoredTransfers(),
) {
    fun start() {
        scope.launch {
            val leftover = repo.getInStates(
                TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.RETRYING, TransferState.VERIFYING,
            )
            if (leftover.isNotEmpty()) scheduler.ensureRunning()
        }
        // Baseline taken now, not when the coroutine first runs, so an edge right after start counts.
        var wasUsable = connectivity.usableNetwork.value
        scope.launch {
            connectivity.usableNetwork.collect { usable ->
                if (usable && !wasUsable) {
                    // QUEUED rows held back by the gate start too, so this runs even if nothing moved.
                    repo.promoteWaitingForNetwork()
                    scheduler.ensureRunning()
                }
                wasUsable = usable
            }
        }
        scope.launch {
            combine(connectivity.networkState, connectivity.usableNetwork, NetworkPolicy::blockReason)
                .distinctUntilChanged()
                .filterNotNull()
                .collect { reason -> repo.recodeNetworkWaiters(reason) }
        }
        scope.launch { repo.observeTransfers().collect(restored::prune) }
        scope.launch {
            settings.map { it.maxConcurrent }.distinctUntilChanged().drop(1).collect { scheduler.ensureRunning() }
        }
        // A run's exit reschedules the wake-up with the matching network constraint.
        scope.launch {
            settings.map { it.wifiOnly }.distinctUntilChanged().drop(1).collect { scheduler.ensureRunning() }
        }
    }
}
