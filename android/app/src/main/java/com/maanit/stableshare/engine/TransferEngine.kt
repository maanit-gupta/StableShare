package com.maanit.stableshare.engine

import android.util.Log
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** What a stopped host's running transfers become (see TransferEngine.handleStop). */
enum class StopKind { REQUEUE, PAUSE, WAIT_FOR_NETWORK }

/**
 * Aggregate numbers for the foreground notification: running pipelines, their combined progress,
 * transfers queued or running ([pending]) and their combined live speed.
 */
data class CoordinatorStatus(
    val active: Int,
    val bytes: Long,
    val totalBytes: Long,
    val pending: Int = active,
    val bytesPerSecond: Double = 0.0,
) {
    val percent: Int get() = if (totalBytes <= 0) 0 else ((bytes * 100) / totalBytes).toInt()
}

/**
 * The coordinator (DESIGN.md §6): runs at most `maxConcurrent` pipelines at once, reacting to
 * database, settings and connectivity changes (no polling), and claims nothing while the network
 * is unusable (offline, or metered with Wi-Fi only on). It never writes COMPLETED; only a
 * pipeline's verification step does (rule 3). [run] is the loop every host calls; [lease] keeps
 * it to one loop per process.
 */
class TransferEngine(
    private val repo: TransferRepository,
    private val settings: Flow<Settings>,
    private val connectivity: ConnectivityMonitor,
    private val upload: TransferPipeline,
    private val download: TransferPipeline,
    private val tracker: TransferProgressTracker,
    private val wakeups: WakeupScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val restored: RestoredTransfers = RestoredTransfers(),
    private val lease: RunLease = RunLease(),
) {
    private val accepting = AtomicBoolean(false)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val active = MutableStateFlow<Set<String>>(emptySet())

    /** Transfer ids whose pipeline is running in this process. */
    val activeIds: StateFlow<Set<String>> = active.asStateFlow()

    /** Transfers that restart reconciliation requeued in this process (UI-SPEC §5.4.2). */
    val restoredIds: StateFlow<Set<String>> = restored.restored

    /**
     * True while a coordinator run is going and has not begun exiting: it will notice new rows by
     * itself, so [TransferScheduler.ensureRunning] need not enqueue another run. The flag is
     * cleared *before* the final emptiness check (flag-then-check), so a row written before an
     * ensureRunning() that saw `true` is always seen by that check.
     */
    fun isAcceptingWork(): Boolean = accepting.get()

    /** Cancels the pipeline of [id], if this process runs one, and waits until it has stopped. */
    suspend fun stopJob(id: String) {
        jobs[id]?.cancelAndJoin()
    }

    /** Foreground-notification model: active pipelines and their combined live progress. */
    fun status(): Flow<CoordinatorStatus> =
        combine(active, tracker.progress, repo.observeTransfers()) { ids, live, rows ->
            val mine = rows.filter { it.id in ids }
            CoordinatorStatus(
                active = ids.size,
                bytes = mine.sumOf { live[it.id]?.bytes ?: it.bytesDone },
                totalBytes = mine.sumOf { it.fileSize },
                pending = rows.count { it.state == TransferState.QUEUED || StateMachine.isActive(it.state) },
                bytesPerSecond = ids.sumOf { live[it]?.bytesPerSecond ?: 0.0 },
            )
        }.distinctUntilChanged()

    /**
     * One coordinator run for a host. Returns when nothing is running or QUEUED (after scheduling a
     * wake-up for rows that wait on a backoff or the network). If cancelled — the system stopped
     * the host — the running transfers are handled as [stopKind] says (default: back to QUEUED, an
     * interruption, not a failure). Both lambdas are read only once the stop has happened.
     *
     * Returns false at once, without running, when another host holds the [lease]; that host runs
     * the loop again before it lets go, so new rows are still picked up.
     */
    suspend fun run(
        stopReason: () -> String = { "cancelled" },
        stopKind: () -> StopKind = { StopKind.REQUEUE },
    ): Boolean = lease.runOrHandOff { runLoop(stopReason, stopKind) }

    private suspend fun runLoop(stopReason: () -> String, stopKind: () -> StopKind) {
        accepting.set(true)
        try {
            coroutineScope { coordinate() }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { handleStop(stopReason(), stopKind()) }
            throw e
        } finally {
            accepting.set(false)
            active.value = emptySet()
        }
    }

    private suspend fun CoroutineScope.coordinate() {
        restored.add(repo.reconcileAfterProcessStart())
        repo.promoteDueRetries()
        if (connectivity.usableNetwork.value) repo.promoteWaitingForNetwork()

        val wake = Channel<Unit>(Channel.CONFLATED)
        val pipelines = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val watchers = launch {
            launch { repo.observeTransfers().collect { wake.trySend(Unit) } }
            launch { settings.map { it.maxConcurrent }.distinctUntilChanged().collect { wake.trySend(Unit) } }
            launch {
                var wasUsable = connectivity.usableNetwork.value
                connectivity.usableNetwork.collect { usable ->
                    if (usable && !wasUsable) repo.promoteWaitingForNetwork()
                    wasUsable = usable
                    wake.trySend(Unit)
                }
            }
        }

        while (true) {
            val deadline = evaluate(pipelines, wake) ?: break
            if (deadline == Long.MAX_VALUE) {
                wake.receive()
            } else {
                withTimeoutOrNull((deadline - clock()).coerceAtLeast(1)) { wake.receive() }
            }
        }
        watchers.cancel()
        pipelines.coroutineContext.job.cancel()
    }

    /**
     * One pass of the loop. Returns null to exit, otherwise the time of the next timer wake-up
     * ([Long.MAX_VALUE] = only events).
     */
    private suspend fun evaluate(pipelines: CoroutineScope, wake: Channel<Unit>): Long? {
        jobs.entries.removeAll { it.value.isCompleted }

        // A row that left {TRANSFERRING, RETRYING, VERIFYING} (paused, cancelled, failed,
        // completed, deleted) has its job cancelled at once.
        val activeRows = repo.getInStates(TransferState.TRANSFERRING, TransferState.RETRYING, TransferState.VERIFYING)
            .associateBy { it.id }
        jobs.forEach { (id, job) -> if (id !in activeRows) job.cancel() }

        // Fill free slots; maxConcurrent is read fresh, so lowering it lets running jobs finish.
        // Nothing is claimed while the network is unusable (offline, or metered with Wi-Fi only).
        val s = settings.first()
        val usable = connectivity.usableNetwork.value
        val free = s.maxConcurrent - jobs.count { !it.value.isCompleted }
        if (free > 0 && usable) {
            // A snapshot: finishing jobs remove themselves from another thread, and a live key view
            // can shrink between Kotlin's size check and iterator().next() (NoSuchElementException).
            repo.claimNextQueued(free, exclude = HashSet(jobs.keys)).forEach { launchPipeline(pipelines, it, wake) }
        }
        publishActive()

        if (jobs.values.none { !it.isCompleted }) {
            accepting.set(false)
            val now = clock()
            val pending = repo.getInStates(TransferState.QUEUED, TransferState.RETRYING)
            val runnable = usable && pending.any {
                it.state == TransferState.QUEUED || (it.nextRetryAt != null && it.nextRetryAt <= now)
            }
            if (!runnable) {
                wakeups.schedule(WakeupPlan.compute(pending, now, usable, s.wifiOnly))
                return null
            }
            accepting.set(true)
            return now // re-evaluate immediately
        }

        // Unowned RETRYING rows with a future backoff: wake when the earliest is due.
        return activeRows.values
            .filter { it.state == TransferState.RETRYING && it.id !in jobs.keys }
            .mapNotNull { it.nextRetryAt }
            .minOrNull() ?: Long.MAX_VALUE
    }

    private fun launchPipeline(scope: CoroutineScope, row: TransferEntity, wake: Channel<Unit>) {
        val pipeline = if (row.type == TransferType.UPLOAD) upload else download
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                pipeline.run(row)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Pipelines classify their own errors; this only catches bugs, and still ends bounded.
                Log.e(TAG, "pipeline ${row.id} crashed", t)
                failActive(row.id, "Internal error: ${t::class.simpleName}: ${t.message}")
            } finally {
                tracker.clear(row.id)
            }
            // Ended on its own (not cancelled): a system stop must not requeue it.
            jobs.remove(row.id, coroutineContext.job)
        }
        jobs[row.id] = job
        job.invokeOnCompletion { wake.trySend(Unit) }
        job.start()
    }

    private suspend fun failActive(id: String, message: String) {
        val state = repo.getTransfer(id)?.state ?: return
        if (state == TransferState.TRANSFERRING || state == TransferState.VERIFYING) {
            repo.transition(id, TransferState.FAILED, ErrorCode.UNKNOWN, message, expectedFrom = state)
        }
    }

    private fun publishActive() {
        active.value = jobs.filterValues { !it.isCompleted }.keys.toSet()
    }

    /**
     * System stop (DESIGN.md §9). Every transfer this run was working on is handled per [kind],
     * with no attempt consumed; rows the user paused or cancelled meanwhile are untouched
     * (expectedFrom), and terminal rows are never written.
     * - REQUEUE: back to QUEUED with no error code; a wake-up is scheduled as a backstop.
     * - PAUSE (the user stopped the job): TRANSFERRING/RETRYING → PAUSED; VERIFYING has no edge to
     *   PAUSED, so it goes back to QUEUED. No wake-up: nothing restarts until the user acts or the
     *   app starts again.
     * - WAIT_FOR_NETWORK (the job lost its network constraint): TRANSFERRING/VERIFYING → RETRYING
     *   NETWORK_UNAVAILABLE without nextRetryAt, the usual waiting path; RETRYING rows already wait
     *   (on a backoff or the network) and are left as they are. A network wake-up is scheduled.
     */
    private suspend fun handleStop(reason: String, kind: StopKind) {
        // Jobs that ended on their own already removed themselves; what is left was interrupted.
        val owned = jobs.keys.toList()
        jobs.values.forEach { it.cancel() }
        jobs.values.toList().joinAll()
        jobs.clear()
        for (id in owned) {
            runCatching {
                val state = repo.getTransfer(id)?.state ?: return@runCatching
                if (!StateMachine.isActive(state)) return@runCatching
                when {
                    kind == StopKind.PAUSE && state != TransferState.VERIFYING ->
                        repo.transition(id, TransferState.PAUSED, expectedFrom = state)
                    kind == StopKind.WAIT_FOR_NETWORK -> if (state != TransferState.RETRYING) {
                        repo.transition(
                            id, TransferState.RETRYING, ErrorCode.NETWORK_UNAVAILABLE,
                            "The system stopped the transfer job: no network ($reason)", nextRetryAt = null, expectedFrom = state,
                        )
                    }
                    repo.transition(id, TransferState.QUEUED, expectedFrom = state) ->
                        repo.logEventWhile(id, setOf(TransferState.QUEUED), EventType.INFO, "Interrupted by a system stop ($reason); requeued")
                }
            }.onFailure { Log.w(TAG, "handling $id after stop failed", it) }
        }
        if (kind != StopKind.PAUSE) {
            runCatching {
                val unmetered = settings.first().wifiOnly
                wakeups.schedule(WakeupPlan(WakeupPlan.AFTER_STOP_MS, requiresNetwork = true, unmetered = unmetered))
            }
        }
        Log.i(TAG, "coordinator stopped ($reason, $kind); handled ${owned.size} transfer(s)")
    }

    private companion object {
        const val TAG = "StableShare"
    }
}
