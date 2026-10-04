package com.maanit.stableshare.engine

import android.util.Log
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.net.Outcome
import com.maanit.stableshare.data.net.isAmbiguous
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.RetryPolicy
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Everything a pipeline needs besides the protocol and the file store. */
class PipelineEnv(
    val repo: TransferRepository,
    val classifier: ErrorClassifier,
    val retryPolicy: RetryPolicy,
    val settings: suspend () -> Settings,
    val tracker: TransferProgressTracker,
    /** Ends a backoff early once the network has become unusable (Wi-Fi only, offline). */
    val network: NetworkGuard,
    val clock: () -> Long = System::currentTimeMillis,
    /** Android 17+: false while ACCESS_LOCAL_NETWORK is denied (connects to the server time out). */
    val localNetworkGranted: () -> Boolean = { true },
    /** Backoff wait; a seam for tests that need a transfer to sit in RETRYING. */
    val sleep: suspend (Long) -> Unit = { delay(it) },
)

/**
 * Control-flow signals inside a pipeline. They are never classified as errors: [RetryRunner]
 * rethrows them untouched. No stack traces (they are not failures).
 */
internal sealed class PipelineSignal(message: String) : Exception(message, null, false, false) {
    /** The job ends. The row's state has already been written, or another writer won the CAS. */
    class Stop(reason: String) : PipelineSignal(reason)

    /** Start the pipeline's main loop again (the state is TRANSFERRING again). */
    class Restart(reason: String) : PipelineSignal(reason)

    /** Upload: the server answered 404 SESSION_NOT_FOUND. */
    class SessionLost(message: String) : PipelineSignal(message)

    /** Upload: `complete` answered 409 MISSING_CHUNKS. */
    class MissingChunks(val missing: List<Int>) : PipelineSignal("server is missing chunks $missing")
}

/** What a retried step is, for attempt accounting. */
sealed interface Step {
    /** A chunk: failures count against the persisted `chunks.attempts` (DESIGN.md §7). */
    data class Chunk(val index: Int) : Step

    /** A non-chunk step (session, status, complete, local verification): counted in memory per job. */
    data class Named(val name: String) : Step
}

/**
 * Runs pipeline steps with the error policy of DESIGN.md §7. Every failure ends in exactly one of:
 * success, a consumed attempt (≤ [RetryPolicy.maxAttemptsPerChunk] per step), waiting for a
 * usable network (RETRYING, job ends, no attempt consumed), or FAILED. One instance per pipeline run.
 */
internal class RetryRunner(private val id: String, private val env: PipelineEnv) {
    private val repo get() = env.repo
    private val stepFailures = HashMap<Step.Named, Int>()

    /** Parallel chunks: the first worker to reach a terminal outcome writes the state; the rest find it written. */
    private val terminal = Mutex()

    /**
     * Runs [block] until it succeeds. On an error, [recover] may turn the failure into a result
     * (the lost-response check, DESIGN.md §8). Otherwise a retryable error backs off and retries
     * in place, or — when [retryInPlace] is false — throws [PipelineSignal.Restart] after the
     * backoff so the pipeline re-syncs from the top (used for VERIFYING steps).
     */
    suspend fun <T> run(
        step: Step,
        retryInPlace: Boolean = true,
        recover: (suspend (Outcome) -> T?)? = null,
        block: suspend () -> T,
    ): T {
        while (true) {
            val error = try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PipelineSignal) {
                throw e
            } catch (e: Throwable) {
                e
            }
            val outcome = classify(error)
            if (recover != null) recover(outcome)?.let { return it }
            handleFailure(step, outcome, describe(error, outcome))
            if (!retryInPlace) throw PipelineSignal.Restart("retrying $step from the top")
        }
    }

    /**
     * Parallel chunks (N > 1, DESIGN.md §7): runs chunk [index] until it succeeds, with its own
     * persisted attempt counter and the same [RetryPolicy], but the backoff happens in place while
     * the transfer stays TRANSFERRING, so sibling chunks can still be marked DONE (rule 5). Only
     * the terminal outcomes write state, one worker at a time: Fatal or an exhausted chunk →
     * FAILED, no usable network → RETRYING. Each throws [PipelineSignal.Stop], which cancels the
     * siblings; a worker arriving second finds the row no longer active and only stops.
     * [onBackoff] runs when a backoff starts (the chunk is no longer moving) with the failures so far.
     */
    suspend fun <T> runChunkInPlace(
        index: Int,
        recover: (suspend (Outcome) -> T?)? = null,
        onBackoff: (failures: Int) -> Unit = {},
        block: suspend () -> T,
    ): T {
        val step = Step.Chunk(index)
        while (true) {
            val error = try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PipelineSignal) {
                throw e
            } catch (e: Throwable) {
                e
            }
            val outcome = classify(error)
            if (recover != null) recover(outcome)?.let { return it }
            val message = describe(error, outcome)
            when (outcome) {
                is Outcome.Fatal -> {
                    repo.markChunkFailed(id, index, "${outcome.code}: $message")
                    terminal.withLock { fail(outcome.code, message) }
                }
                is Outcome.WaitForNetwork -> terminal.withLock { waitForNetwork(outcome.code, message) }
                is Outcome.Retryable -> {
                    if (!repo.markChunkFailed(id, index, "${outcome.code}: $message")) throw PipelineSignal.Stop("not TRANSFERRING")
                    if (!env.settings().autoRetryEnabled) terminal.withLock { fail(outcome.code, "$message (automatic retry is off)") }
                    val failures = repo.incrementAttempts(id, index) ?: throw PipelineSignal.Stop("not active")
                    if (!env.retryPolicy.canRetry(failures)) {
                        terminal.withLock {
                            fail(ErrorCode.RETRIES_EXHAUSTED, "${label(step)} failed $failures times; last error ${outcome.code}: $message")
                        }
                    }
                    val delayMs = env.retryPolicy.delayHonouringRetryAfter(failures, outcome.retryAfterMs)
                    if (!repo.logEventWhile(
                            id, IN_PLACE_STATES, EventType.RETRY_SCHEDULED,
                            "${label(step)} attempt $failures failed (${outcome.code}); retrying it in $delayMs ms",
                            chunkIndex = index,
                        )
                    ) {
                        throw PipelineSignal.Stop("not TRANSFERRING")
                    }
                    onBackoff(failures)
                    val blocked = env.network.sleep(delayMs, env.sleep)
                    if (blocked != null) {
                        terminal.withLock { waitForNetwork(blocked, "Network became unusable ($blocked) during a chunk backoff") }
                    }
                }
            }
        }
    }

    /** FAILED with [code], from whichever active state the row is in. Always throws [PipelineSignal.Stop]. */
    suspend fun fail(code: ErrorCode, message: String): Nothing {
        val from = activeState()
        if (from != null) repo.transition(id, TransferState.FAILED, code, message, expectedFrom = from)
        Log.w(TAG, "$id FAILED $code: $message")
        throw PipelineSignal.Stop("failed: $code")
    }

    /**
     * VERIFYING → RETRYING (due now) → TRANSFERRING: verification found specific chunks to send or
     * fetch again. Uses only legal transitions; a lost CAS (pause/cancel) stops the job.
     */
    suspend fun backToTransferring(code: ErrorCode?, message: String) {
        val now = env.clock()
        if (!repo.transition(id, TransferState.RETRYING, code, message, nextRetryAt = now, expectedFrom = TransferState.VERIFYING)) {
            throw PipelineSignal.Stop("lost CAS VERIFYING → RETRYING")
        }
        resumeTransferring()
    }

    /** TRANSFERRING → VERIFYING, or stop if the row moved (paused/cancelled). */
    suspend fun enterVerifying() {
        if (!repo.transition(id, TransferState.VERIFYING, expectedFrom = TransferState.TRANSFERRING)) {
            throw PipelineSignal.Stop("lost CAS TRANSFERRING → VERIFYING")
        }
        env.tracker.setPhase(id, TransferPhase.Verifying(0))
    }

    private suspend fun handleFailure(step: Step, outcome: Outcome, message: String) {
        when (outcome) {
            is Outcome.Fatal -> {
                if (step is Step.Chunk) repo.markChunkFailed(id, step.index, "${outcome.code}: $message")
                fail(outcome.code, message)
            }
            is Outcome.WaitForNetwork -> waitForNetwork(outcome.code, message)
            is Outcome.Retryable -> {
                if (step is Step.Chunk) repo.markChunkFailed(id, step.index, "${outcome.code}: $message")
                if (!env.settings().autoRetryEnabled) fail(outcome.code, "$message (automatic retry is off)")
                val failures = when (step) {
                    is Step.Chunk -> repo.incrementAttempts(id, step.index) ?: throw PipelineSignal.Stop("not active")
                    is Step.Named -> (stepFailures[step] ?: 0) + 1
                }
                if (step is Step.Named) stepFailures[step] = failures
                if (!env.retryPolicy.canRetry(failures)) {
                    fail(ErrorCode.RETRIES_EXHAUSTED, "${label(step)} failed $failures times; last error ${outcome.code}: $message")
                }
                val delayMs = env.retryPolicy.delayHonouringRetryAfter(failures, outcome.retryAfterMs)
                backoff(step, failures, outcome.code, message, delayMs)
            }
        }
    }

    private suspend fun backoff(step: Step, failures: Int, code: ErrorCode, message: String, delayMs: Long) {
        val from = activeState() ?: throw PipelineSignal.Stop("not active")
        val at = env.clock() + delayMs
        if (!repo.transition(id, TransferState.RETRYING, code, message, nextRetryAt = at, expectedFrom = from)) {
            throw PipelineSignal.Stop("lost CAS → RETRYING")
        }
        repo.logEventWhile(
            id, StateMachine.ACTIVE, EventType.RETRY_SCHEDULED,
            "${label(step)} attempt $failures failed ($code); retrying in $delayMs ms",
            chunkIndex = (step as? Step.Chunk)?.index,
        )
        env.tracker.setInFlight(id, 0)
        env.tracker.setPhase(id, TransferPhase.Retrying(at))
        val blocked = env.network.sleep(delayMs, env.sleep)
        resumeTransferring()
        // RETRYING → RETRYING is not a transition, so the network wait goes through TRANSFERRING,
        // exactly as when a backoff ends and the next request finds no network.
        if (blocked != null) waitForNetwork(blocked, "Network became unusable ($blocked) during the backoff")
    }

    /**
     * RETRYING with [code] (NETWORK_UNAVAILABLE or METERED_NETWORK), no nextRetryAt and no attempt
     * consumed; the job ends so the slot is free. A usable network promotes the row again.
     */
    private suspend fun waitForNetwork(code: ErrorCode, message: String): Nothing {
        val from = activeState() ?: throw PipelineSignal.Stop("not active")
        repo.transition(id, TransferState.RETRYING, code, message, nextRetryAt = null, expectedFrom = from)
        env.tracker.setPhase(id, TransferPhase.WaitingForNetwork)
        throw PipelineSignal.Stop("waiting for network ($code)")
    }

    private suspend fun resumeTransferring() {
        if (!repo.transition(id, TransferState.TRANSFERRING, expectedFrom = TransferState.RETRYING)) {
            throw PipelineSignal.Stop("lost CAS RETRYING → TRANSFERRING")
        }
        env.tracker.setPhase(id, TransferPhase.Transferring)
    }

    private suspend fun activeState(): TransferState? =
        repo.getTransfer(id)?.state?.takeIf { it == TransferState.TRANSFERRING || it == TransferState.VERIFYING }

    /**
     * The classifier's verdict, except that a transport failure while ACCESS_LOCAL_NETWORK is
     * denied is fatal: every connect would silently time out, so retrying cannot help.
     */
    private fun classify(error: Throwable): Outcome {
        val outcome = env.classifier.classify(error)
        if (outcome.isAmbiguous && !env.localNetworkGranted()) return Outcome.Fatal(ErrorCode.UNKNOWN)
        return outcome
    }

    private fun describe(error: Throwable, outcome: Outcome): String =
        if (outcome is Outcome.Fatal && outcome.code == ErrorCode.UNKNOWN && !env.localNetworkGranted()) {
            "Local network permission denied; grant it and retry (${env.classifier.describe(error)})"
        } else {
            env.classifier.describe(error)
        }

    private fun label(step: Step) = when (step) {
        is Step.Chunk -> "Chunk ${step.index}"
        is Step.Named -> step.name.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        const val TAG = "StableShare"
        val IN_PLACE_STATES = setOf(TransferState.TRANSFERRING)
    }
}
