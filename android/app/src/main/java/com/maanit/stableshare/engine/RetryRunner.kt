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
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** Everything a pipeline needs besides the protocol and the file store. */
class PipelineEnv(
    val repo: TransferRepository,
    val classifier: ErrorClassifier,
    val retryPolicy: RetryPolicy,
    val settings: suspend () -> Settings,
    val tracker: TransferProgressTracker,
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
 * success, a consumed attempt (≤ [RetryPolicy.maxAttemptsPerChunk] per step), waiting for the
 * network (RETRYING, job ends, no attempt consumed), or FAILED. One instance per pipeline run.
 */
internal class RetryRunner(private val id: String, private val env: PipelineEnv) {
    private val repo get() = env.repo
    private val stepFailures = HashMap<Step.Named, Int>()

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
            Outcome.WaitForNetwork -> {
                val from = activeState() ?: throw PipelineSignal.Stop("not active")
                repo.transition(id, TransferState.RETRYING, ErrorCode.NETWORK_UNAVAILABLE, message, nextRetryAt = null, expectedFrom = from)
                env.tracker.setPhase(id, TransferPhase.WaitingForNetwork)
                throw PipelineSignal.Stop("waiting for network")
            }
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
        repo.logEvent(
            id, EventType.RETRY_SCHEDULED,
            "${label(step)} attempt $failures failed ($code); retrying in $delayMs ms",
            chunkIndex = (step as? Step.Chunk)?.index,
        )
        env.tracker.setInFlight(id, 0)
        env.tracker.setPhase(id, TransferPhase.Retrying(at))
        env.sleep(delayMs)
        resumeTransferring()
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
    }
}
