package com.maanit.stableshare.ui.model

import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.LiveProgress
import com.maanit.stableshare.engine.TransferPhase
import kotlin.math.ceil

/** The rows of the state presentation table (UI-SPEC §6): a state, refined by phase or error. */
enum class Condition {
    QUEUED,
    PREPARING,
    TRANSFERRING,
    VERIFYING,
    RETRYING,
    WAITING_NETWORK,
    PAUSED,
    FAILED,
    COMPLETED,
    CANCELLED,
}

fun conditionOf(state: TransferState, errorCode: ErrorCode?, phase: TransferPhase?): Condition = when (state) {
    TransferState.QUEUED -> Condition.QUEUED
    TransferState.TRANSFERRING -> if (phase is TransferPhase.Preparing) Condition.PREPARING else Condition.TRANSFERRING
    TransferState.VERIFYING -> Condition.VERIFYING
    TransferState.RETRYING ->
        if (errorCode == ErrorCode.NETWORK_UNAVAILABLE) Condition.WAITING_NETWORK else Condition.RETRYING
    TransferState.PAUSED -> Condition.PAUSED
    TransferState.FAILED -> Condition.FAILED
    TransferState.COMPLETED -> Condition.COMPLETED
    TransferState.CANCELLED -> Condition.CANCELLED
}

/** Everything a row or the detail hero shows about one transfer at one moment. */
data class TransferItem(
    val row: TransferEntity,
    val condition: Condition,
    /** Committed bytes plus the chunk in flight. */
    val bytes: Long,
    val percent: Int,
    val bytesPerSecond: Double?,
    val etaSeconds: Long?,
    /** Preparing: checksum progress, 0–99. */
    val preparingPercent: Int,
    /** RETRYING: whole seconds until the next try, counting down. */
    val retryInSeconds: Int,
    /** QUEUED: 1-based place in claim order. */
    val queuePosition: Int?,
    val restored: Boolean,
    val inFlightChunk: Int?,
) {
    val id: String get() = row.id
    val name: String get() = row.fileName
    val type: TransferType get() = row.type
    val state: TransferState get() = row.state
    val size: Long get() = row.fileSize

    /** Speed shown only while actually transferring with a known, positive rate. */
    val liveSpeed: Double? get() = bytesPerSecond?.takeIf { condition == Condition.TRANSFERRING && it > 0 }

    /** Row buttons: the primary action (Pause, Resume or Retry), then Cancel — from the state machine. */
    val rowActions: List<TransferAction> get() = actionsFor(state)

    companion object {
        /** Orders [StateMachine.allowedActions]: primary first, Cancel last; REMOVE is not a row action. */
        fun actionsFor(state: TransferState): List<TransferAction> {
            val allowed = StateMachine.allowedActions(state)
            val primary = listOf(TransferAction.PAUSE, TransferAction.RESUME, TransferAction.RETRY).filter { it in allowed }
            return primary + listOfNotNull(TransferAction.CANCEL.takeIf { it in allowed })
        }

        fun build(
            row: TransferEntity,
            live: LiveProgress?,
            queuePosition: Int?,
            restored: Boolean,
            now: Long,
        ): TransferItem {
            val phase = live?.phase
            val condition = conditionOf(row.state, row.errorCode, phase)
            val bytes = live?.bytes ?: row.bytesDone
            val preparing = (phase as? TransferPhase.Preparing)?.let { displayPercent(it.hashedBytes, row.fileSize, row.state) } ?: 0
            val retryAt = (phase as? TransferPhase.Retrying)?.atMs ?: row.nextRetryAt
            val retryIn = retryAt?.let { ceil((it - now).coerceAtLeast(0) / 1_000.0).toInt() } ?: 0
            return TransferItem(
                row = row,
                condition = condition,
                bytes = bytes,
                percent = displayPercent(bytes, row.fileSize, row.state),
                bytesPerSecond = live?.bytesPerSecond,
                etaSeconds = live?.etaSeconds,
                preparingPercent = preparing,
                retryInSeconds = retryIn,
                queuePosition = queuePosition,
                restored = restored,
                inFlightChunk = live?.inFlightChunk,
            )
        }

        /** Queue positions in claim order (createdAt, then id — the same order as TransferDao.claimable). */
        fun queuePositions(rows: List<TransferEntity>): Map<String, Int> =
            rows.filter { it.state == TransferState.QUEUED }
                .sortedWith(compareBy<TransferEntity>({ it.createdAt }, { it.id }))
                .mapIndexed { i, r -> r.id to i + 1 }
                .toMap()

        fun buildAll(rows: List<TransferEntity>, live: Map<String, LiveProgress>, restored: Set<String>, now: Long): List<TransferItem> {
            val positions = queuePositions(rows)
            return rows.map { build(it, live[it.id], positions[it.id], it.id in restored, now) }
        }
    }
}
