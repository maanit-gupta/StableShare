package com.maanit.stableshare.data.repo

import androidx.room.withTransaction
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.db.ChunkEntity
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.db.TransferEventEntity
import com.maanit.stableshare.data.net.Manifest
import com.maanit.stableshare.domain.ChunkPlanner
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The only writer of transfer state (rule 1). Every state change is a compare-and-set validated
 * by [StateMachine] and logged as a STATE_CHANGE event in the same transaction. Chunk progress
 * is written only while the transfer is TRANSFERRING (rule 5), enforced in SQL.
 */
class TransferRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val transfers = db.transferDao()
    private val chunks = db.chunkDao()
    private val events = db.eventDao()

    // ---- creation ----

    suspend fun createUpload(
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        sourceUri: String,
        chunkSize: Int,
        sha256: String? = null,
        sourceLastModified: Long? = null,
        id: String = UUID.randomUUID().toString(),
    ): TransferEntity {
        val plan = ChunkPlanner.plan(fileSize, chunkSize)
        val now = clock()
        val transfer = TransferEntity(
            id = id,
            type = TransferType.UPLOAD,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            localUri = sourceUri,
            remoteId = id,
            chunkSize = chunkSize,
            totalChunks = plan.size,
            sha256 = sha256,
            sourceLastModified = sourceLastModified,
            etag = null,
            state = TransferState.QUEUED,
            bytesDone = 0,
            errorCode = null,
            errorMessage = null,
            attemptCount = 0,
            nextRetryAt = null,
            sessionCreated = false,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        val rows = plan.map { ChunkEntity(id, it.index, it.offset, it.length, null, ChunkStatus.PENDING, 0, null) }
        insertNew(transfer, rows, "Upload created: $fileName ($fileSize bytes, ${plan.size} chunks)")
        return transfer
    }

    /** Chunk rows come from the manifest (with hashes) and must agree with [ChunkPlanner]. */
    suspend fun createDownload(
        manifest: Manifest,
        partFileUri: String,
        mimeType: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): TransferEntity {
        val plan = ChunkPlanner.plan(manifest.size, manifest.chunkSize)
        require(plan.size == manifest.chunks.size) {
            "manifest has ${manifest.chunks.size} chunks, expected ${plan.size}"
        }
        plan.zip(manifest.chunks).forEach { (p, m) ->
            require(p.index == m.index && p.offset == m.offset && p.length == m.length) {
                "manifest chunk ${m.index} is ${m.offset}+${m.length}, expected ${p.offset}+${p.length}"
            }
        }
        val now = clock()
        val transfer = TransferEntity(
            id = id,
            type = TransferType.DOWNLOAD,
            fileName = manifest.name,
            fileSize = manifest.size,
            mimeType = mimeType,
            localUri = partFileUri,
            remoteId = manifest.fileId,
            chunkSize = manifest.chunkSize,
            totalChunks = plan.size,
            sha256 = manifest.sha256,
            sourceLastModified = null,
            etag = manifest.etag,
            state = TransferState.QUEUED,
            bytesDone = 0,
            errorCode = null,
            errorMessage = null,
            attemptCount = 0,
            nextRetryAt = null,
            sessionCreated = false,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        val rows = manifest.chunks.map {
            ChunkEntity(id, it.index, it.offset, it.length, it.sha256.lowercase(), ChunkStatus.PENDING, 0, null)
        }
        insertNew(transfer, rows, "Download created: ${manifest.name} (${manifest.size} bytes, ${plan.size} chunks)")
        return transfer
    }

    private suspend fun insertNew(transfer: TransferEntity, rows: List<ChunkEntity>, message: String) {
        db.withTransaction {
            transfers.insert(transfer)
            if (rows.isNotEmpty()) chunks.insertAll(rows)
            insertEvent(transfer.id, EventType.STATE_CHANGE, message, from = null, to = TransferState.QUEUED)
        }
    }

    // ---- state ----

    /**
     * Moves [id] to [to] if the current state allows it (and equals [expectedFrom], when given).
     * Returns false, changing nothing, if the transfer is missing, the transition is illegal, or a
     * concurrent writer got there first.
     *
     * Field rules: RETRYING/FAILED record [errorCode]/[errorMessage]; RETRYING records
     * [nextRetryAt] (null = wait for connectivity). TRANSFERRING and COMPLETED clear the error.
     * FAILED → QUEUED is a manual retry: attempt counters reset, DONE chunks are kept.
     */
    suspend fun transition(
        id: String,
        to: TransferState,
        errorCode: ErrorCode? = null,
        errorMessage: String? = null,
        nextRetryAt: Long? = null,
        expectedFrom: TransferState? = null,
    ): Boolean = db.withTransaction { transitionLocked(id, to, errorCode, errorMessage, nextRetryAt, expectedFrom) }

    private suspend fun transitionLocked(
        id: String,
        to: TransferState,
        errorCode: ErrorCode?,
        errorMessage: String?,
        nextRetryAt: Long?,
        expectedFrom: TransferState?,
        reason: String? = null,
    ): Boolean {
        val current = transfers.get(id) ?: return false
        val from = current.state
        if (expectedFrom != null && from != expectedFrom) return false
        if (!StateMachine.canTransition(from, to)) return false

        val now = clock()
        val manualRetry = from == TransferState.FAILED && to == TransferState.QUEUED
        val (code, message) = when {
            to == TransferState.RETRYING || to == TransferState.FAILED -> errorCode to errorMessage
            to == TransferState.TRANSFERRING || to == TransferState.COMPLETED || manualRetry -> null to null
            else -> current.errorCode to current.errorMessage
        }
        val updated = transfers.compareAndSetState(
            id = id,
            from = from,
            to = to,
            errorCode = code,
            errorMessage = message,
            nextRetryAt = if (to == TransferState.RETRYING) nextRetryAt else null,
            attemptCount = if (manualRetry) 0 else current.attemptCount,
            completedAt = if (to == TransferState.COMPLETED) now else current.completedAt,
            now = now,
        )
        if (updated == 0) return false
        if (manualRetry) chunks.resetAttempts(id)

        val text = buildString {
            append("$from → $to")
            if (code != null && (to == TransferState.RETRYING || to == TransferState.FAILED)) append(" [$code]")
            if (message != null && (to == TransferState.RETRYING || to == TransferState.FAILED)) append(": $message")
            if (to == TransferState.RETRYING && nextRetryAt != null) append(" (retry at $nextRetryAt)")
            if (reason != null) append(" — $reason")
        }
        insertEvent(id, EventType.STATE_CHANGE, text, from = from, to = to)
        return true
    }

    /**
     * Atomically claims up to [limit] runnable transfers, oldest first, and moves each to
     * TRANSFERRING. Runnable = QUEUED, or RETRYING whose nextRetryAt has passed. Rows in [exclude]
     * (transfers whose pipeline is still running in this process, e.g. backing off in RETRYING) are
     * skipped. Room serialises write transactions, so two concurrent callers never claim the same row.
     */
    suspend fun claimNextQueued(limit: Int, exclude: Collection<String> = emptyList()): List<TransferEntity> {
        if (limit <= 0) return emptyList()
        return db.withTransaction {
            transfers.claimable(clock(), limit, exclude.toList()).mapNotNull { candidate ->
                val ok = transitionLocked(
                    candidate.id, TransferState.TRANSFERRING, null, null, null,
                    expectedFrom = candidate.state, reason = "claimed",
                )
                if (ok) transfers.get(candidate.id) else null
            }
        }
    }

    /**
     * Run once per process start: a TRANSFERRING or VERIFYING row means the previous process died
     * mid-flight, so it goes back to QUEUED. Terminal, PAUSED, FAILED and RETRYING rows are left
     * alone (RETRYING keeps its persisted backoff and is picked up by [claimNextQueued]).
     * Returns the ids it moved (the UI shows them as "restored after restart").
     */
    suspend fun reconcileAfterProcessStart(): List<String> = db.withTransaction {
        val stale = transfers.getInStates(listOf(TransferState.TRANSFERRING, TransferState.VERIFYING))
        stale.filter { row ->
            val ok = transitionLocked(
                row.id, TransferState.QUEUED, null, null, null,
                expectedFrom = row.state, reason = "reconciled after process start",
            )
            if (ok) {
                insertEvent(row.id, EventType.INFO, "Reconciled after process start: ${row.state} → QUEUED")
            }
            ok
        }.map { it.id }
    }

    /** RETRYING rows whose persisted backoff has elapsed go back to QUEUED. Returns how many moved. */
    suspend fun promoteDueRetries(): Int = db.withTransaction {
        val now = clock()
        transfers.getInStates(listOf(TransferState.RETRYING))
            .filter { it.nextRetryAt != null && it.nextRetryAt <= now }
            .count { promoteLocked(it.id, "backoff elapsed") }
    }

    /**
     * RETRYING rows waiting for a usable network (NETWORK_UNAVAILABLE or METERED_NETWORK, no
     * nextRetryAt) go back to QUEUED. Called when the network becomes usable. Only RETRYING rows
     * move, so PAUSED and CANCELLED ones never do. Returns how many moved.
     */
    suspend fun promoteWaitingForNetwork(): Int = db.withTransaction {
        transfers.getInStates(listOf(TransferState.RETRYING))
            .filter { it.isWaitingForNetwork() }
            .count { promoteLocked(it.id, "network usable") }
    }

    /**
     * Keeps waiting rows' reason in step with the network: [code] is NETWORK_UNAVAILABLE (offline)
     * or METERED_NETWORK (metered, Wi-Fi only on). Changes only errorCode/errorMessage, never the
     * state, and logs an INFO event per row. Returns how many changed.
     */
    suspend fun recodeNetworkWaiters(code: ErrorCode): Int = db.withTransaction {
        require(code in NETWORK_WAIT_CODES) { "$code is not a network-wait code" }
        val message = when (code) {
            ErrorCode.NETWORK_UNAVAILABLE -> "Still waiting: now offline"
            else -> "Still waiting: on mobile data with Wi-Fi only on"
        }
        transfers.getInStates(listOf(TransferState.RETRYING))
            .filter { it.isWaitingForNetwork() && it.errorCode != code }
            .count { row ->
                val ok = transfers.setWaitingReason(row.id, code, message, clock()) > 0
                if (ok) insertEvent(row.id, EventType.INFO, message)
                ok
            }
    }

    private fun TransferEntity.isWaitingForNetwork() = nextRetryAt == null || errorCode in NETWORK_WAIT_CODES

    private suspend fun promoteLocked(id: String, reason: String): Boolean = transitionLocked(
        id, TransferState.QUEUED, null, null, null, expectedFrom = TransferState.RETRYING, reason = reason,
    )

    // ---- chunks ----

    /** Marks a chunk DONE only while TRANSFERRING; recomputes bytesDone in the same transaction. */
    suspend fun markChunkDone(id: String, index: Int, sha256: String?): Boolean = db.withTransaction {
        if (chunks.markDoneIfTransferring(id, index, sha256?.lowercase()) == 0) return@withTransaction false
        transfers.recomputeBytesDone(id, clock())
        insertEvent(id, EventType.CHUNK_DONE, "Chunk $index done", chunkIndex = index)
        true
    }

    suspend fun markChunkFailed(id: String, index: Int, error: String): Boolean = db.withTransaction {
        if (chunks.markFailedIfTransferring(id, index, error) == 0) return@withTransaction false
        transfers.recomputeBytesDone(id, clock())
        insertEvent(id, EventType.CHUNK_FAILED, "Chunk $index failed: $error", chunkIndex = index)
        true
    }

    /**
     * Upload resume: the server is the source of truth (rule 7). Listed chunks become DONE,
     * every other chunk PENDING. Allowed while TRANSFERRING or VERIFYING.
     */
    suspend fun applyServerReceivedChunks(id: String, indices: Collection<Int>): Boolean = db.withTransaction {
        if (!isSyncable(id)) return@withTransaction false
        chunks.resetAllIfSyncable(id)
        indices.distinct().chunked(SQL_BATCH).forEach { chunks.markDoneIfSyncable(id, it) }
        transfers.recomputeBytesDone(id, clock())
        insertEvent(id, EventType.INFO, "Server reports ${indices.size} chunk(s) received")
        true
    }

    /** Puts [indices] (or every chunk, when null) back to PENDING. Allowed while TRANSFERRING or VERIFYING. */
    suspend fun resetChunks(id: String, indices: Collection<Int>? = null): Boolean = db.withTransaction {
        if (!isSyncable(id)) return@withTransaction false
        if (indices == null) {
            chunks.resetAllIfSyncable(id)
        } else {
            indices.distinct().chunked(SQL_BATCH).forEach { chunks.resetIfSyncable(id, it) }
        }
        transfers.recomputeBytesDone(id, clock())
        insertEvent(id, EventType.INFO, "Reset ${indices?.size ?: "all"} chunk(s) to PENDING")
        true
    }

    /**
     * Counts one failed attempt of chunk [index] (also bumping the transfer's attemptCount) and
     * returns the chunk's new attempt count, or null if the transfer is not being worked on.
     */
    suspend fun incrementAttempts(id: String, index: Int): Int? = db.withTransaction {
        val state = transfers.get(id)?.state ?: return@withTransaction null
        if (!StateMachine.isActive(state)) return@withTransaction null
        if (chunks.incrementAttempts(id, index) == 0) return@withTransaction null
        transfers.incrementAttemptCount(id, clock())
        chunks.get(id, index)?.attempts
    }

    private suspend fun isSyncable(id: String): Boolean {
        val state = transfers.get(id)?.state
        return state == TransferState.TRANSFERRING || state == TransferState.VERIFYING
    }

    // ---- metadata ----

    suspend fun setSessionCreated(id: String, created: Boolean = true): Boolean =
        transfers.setSessionCreated(id, created, clock()) > 0

    suspend fun setSha256(id: String, sha256: String): Boolean =
        transfers.setSha256(id, sha256.lowercase(), clock()) > 0

    /** Upload: the source hash and mtime recorded once, before the session is created. */
    suspend fun setSourceInfo(id: String, sha256: String, lastModified: Long?): Boolean =
        transfers.setSourceInfo(id, sha256.lowercase(), lastModified, clock()) > 0

    suspend fun setEtag(id: String, etag: String): Boolean = transfers.setEtag(id, etag, clock()) > 0

    suspend fun setLocalUri(id: String, localUri: String): Boolean =
        transfers.setLocalUri(id, localUri, clock()) > 0

    suspend fun logEvent(
        id: String,
        type: EventType,
        message: String,
        chunkIndex: Int? = null,
    ) {
        insertEvent(id, type, message, chunkIndex = chunkIndex)
    }

    private suspend fun insertEvent(
        id: String,
        type: EventType,
        message: String,
        from: TransferState? = null,
        to: TransferState? = null,
        chunkIndex: Int? = null,
    ) {
        events.insert(TransferEventEntity(0, id, clock(), type, from, to, chunkIndex, message))
    }

    // ---- reads ----

    fun observeTransfers(): Flow<List<TransferEntity>> = transfers.observeAll()
    fun observeTransfer(id: String): Flow<TransferEntity?> = transfers.observe(id)
    fun observeChunks(id: String): Flow<List<ChunkEntity>> = chunks.observe(id)
    fun observeEvents(id: String): Flow<List<TransferEventEntity>> = events.observe(id)
    fun observeHistory(): Flow<List<TransferEntity>> = transfers.observeHistory()

    suspend fun getTransfer(id: String): TransferEntity? = transfers.get(id)
    suspend fun getChunks(id: String): List<ChunkEntity> = chunks.getAll(id)
    suspend fun getEvents(id: String): List<TransferEventEntity> = events.getAll(id)
    suspend fun getInStates(vararg states: TransferState): List<TransferEntity> =
        transfers.getInStates(states.toList())

    /** Deletes a COMPLETED or CANCELLED transfer (chunks and events cascade). */
    suspend fun deleteTransfer(id: String): Boolean = transfers.deleteIfTerminal(id) > 0

    /** History's "Clear all": deletes every COMPLETED and CANCELLED transfer; returns how many. */
    suspend fun clearHistory(): Int = transfers.deleteAllTerminal()

    private companion object {
        /** Stays under SQLite's 999 bound-variable limit on older Android versions. */
        const val SQL_BATCH = 500

        /** The codes of a RETRYING row that waits for a usable network rather than a backoff. */
        val NETWORK_WAIT_CODES = setOf(ErrorCode.NETWORK_UNAVAILABLE, ErrorCode.METERED_NETWORK)
    }
}
