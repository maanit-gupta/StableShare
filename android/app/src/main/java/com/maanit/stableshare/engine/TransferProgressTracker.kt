package com.maanit.stableshare.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.exp

/** What a running pipeline is doing right now (in memory only; never persisted). */
sealed interface TransferPhase {
    /** Hashing the upload source before the session is created. */
    data class Preparing(val hashedBytes: Long) : TransferPhase
    data object Transferring : TransferPhase
    /** Upload: waiting for `complete`; download: re-hashing the part file. */
    data class Verifying(val hashedBytes: Long) : TransferPhase
    data object WaitingForNetwork : TransferPhase
    /** Backing off; the next attempt starts at [atMs] (epoch ms). */
    data class Retrying(val atMs: Long) : TransferPhase
}

data class LiveProgress(
    val phase: TransferPhase,
    /** bytesDone as last committed to the database. */
    val committedBytes: Long,
    /** Bytes of the current chunk sent or received so far (not yet DONE). */
    val inFlightBytes: Long,
    /** Index of the chunk currently moving, or null between chunks, while backing off or hashing. */
    val inFlightChunk: Int? = null,
    val totalBytes: Long,
    val bytesPerSecond: Double,
    val etaSeconds: Long?,
) {
    /** What the UI shows: committed chunks plus the chunk in flight. */
    val bytes: Long get() = (committedBytes + inFlightBytes).coerceAtMost(totalBytes)
}

/**
 * Per-transfer live progress fed by the pipelines (OkHttp progress callbacks arrive on OkHttp
 * threads, so every update is synchronised). Speed is an exponential moving average with a time
 * constant of [timeConstantMs] (≈ 3 s); ETA = remaining bytes / speed. Entries are removed when
 * the job ends, so stale numbers never outlive a pipeline.
 */
class TransferProgressTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeConstantMs: Double = 3_000.0,
    private val minSampleMs: Long = 250,
) {
    private val state = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val progress: StateFlow<Map<String, LiveProgress>> = state.asStateFlow()

    private class Speed(var sampleAt: Long, var sampleBytes: Long, var ema: Double?)

    private val speeds = HashMap<String, Speed>()
    private val lock = Any()

    fun start(id: String, committedBytes: Long, totalBytes: Long, phase: TransferPhase = TransferPhase.Transferring) =
        synchronized(lock) {
            speeds[id] = Speed(clock(), committedBytes, null)
            state.update { it + (id to LiveProgress(phase, committedBytes, 0, null, totalBytes, 0.0, null)) }
        }

    /** Leaving the Transferring phase means no chunk is moving any more. */
    fun setPhase(id: String, phase: TransferPhase) = modify(id) {
        it.copy(phase = phase, inFlightChunk = if (phase == TransferPhase.Transferring) it.inFlightChunk else null)
    }

    /** A chunk was committed: [committedBytes] is the new bytesDone and nothing is in flight. */
    fun setCommitted(id: String, committedBytes: Long) = modify(id) {
        it.copy(committedBytes = committedBytes, inFlightBytes = 0, inFlightChunk = null)
    }

    /** [bytes] of chunk [chunkIndex] have moved so far; a null index means nothing is moving. */
    fun setInFlight(id: String, bytes: Long, chunkIndex: Int? = null) = modify(id) {
        it.copy(inFlightBytes = bytes, inFlightChunk = chunkIndex)
    }

    fun clear(id: String) = synchronized(lock) {
        speeds.remove(id)
        state.update { it - id }
    }

    private inline fun modify(id: String, crossinline change: (LiveProgress) -> LiveProgress) = synchronized(lock) {
        val current = state.value[id] ?: return@synchronized
        val next = withSpeed(id, change(current))
        state.update { it + (id to next) }
    }

    private fun withSpeed(id: String, p: LiveProgress): LiveProgress {
        val s = speeds[id] ?: return p
        val now = clock()
        val total = p.committedBytes + p.inFlightBytes
        if (total < s.sampleBytes) {
            // A retry discarded in-flight bytes: restart the baseline instead of reporting negative speed.
            s.sampleAt = now
            s.sampleBytes = total
            return p.copy(bytesPerSecond = s.ema ?: 0.0, etaSeconds = eta(p, s.ema))
        }
        val dt = now - s.sampleAt
        if (dt >= minSampleMs) {
            val instant = (total - s.sampleBytes) * 1_000.0 / dt
            val alpha = 1 - exp(-dt / timeConstantMs)
            s.ema = s.ema?.let { it + alpha * (instant - it) } ?: instant
            s.sampleAt = now
            s.sampleBytes = total
        }
        return p.copy(bytesPerSecond = s.ema ?: 0.0, etaSeconds = eta(p, s.ema))
    }

    private fun eta(p: LiveProgress, ema: Double?): Long? {
        if (ema == null || ema <= 0.0) return null
        val remaining = (p.totalBytes - p.committedBytes - p.inFlightBytes).coerceAtLeast(0)
        return Math.ceil(remaining / ema).toLong()
    }
}
