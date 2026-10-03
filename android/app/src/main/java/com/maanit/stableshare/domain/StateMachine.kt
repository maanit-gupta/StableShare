package com.maanit.stableshare.domain

import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING

/**
 * The transfer state table from CLAUDE.md / DESIGN.md §4. Pure: no I/O, no clocks.
 * TRANSFERRING/VERIFYING → QUEUED exists only for restart reconciliation and system stops.
 */
object StateMachine {

    private val transitions: Map<TransferState, Set<TransferState>> = mapOf(
        QUEUED to setOf(TRANSFERRING, PAUSED, CANCELLED),
        TRANSFERRING to setOf(VERIFYING, RETRYING, PAUSED, FAILED, CANCELLED, QUEUED),
        RETRYING to setOf(TRANSFERRING, QUEUED, PAUSED, FAILED, CANCELLED),
        VERIFYING to setOf(COMPLETED, RETRYING, FAILED, CANCELLED, QUEUED),
        PAUSED to setOf(QUEUED, CANCELLED),
        FAILED to setOf(QUEUED, CANCELLED),
        COMPLETED to emptySet(),
        CANCELLED to emptySet(),
    )

    fun canTransition(from: TransferState, to: TransferState): Boolean =
        to in transitions.getValue(from)

    fun nextStates(from: TransferState): Set<TransferState> = transitions.getValue(from)

    fun isTerminal(state: TransferState): Boolean = transitions.getValue(state).isEmpty()

    /** States in which a transfer is owned by (or waiting on) the engine. */
    fun isActive(state: TransferState): Boolean =
        state == TRANSFERRING || state == RETRYING || state == VERIFYING

    fun allowedActions(state: TransferState): Set<TransferAction> = when (state) {
        QUEUED, TRANSFERRING, RETRYING -> setOf(TransferAction.PAUSE, TransferAction.CANCEL)
        VERIFYING -> setOf(TransferAction.CANCEL)
        PAUSED -> setOf(TransferAction.RESUME, TransferAction.CANCEL)
        FAILED -> setOf(TransferAction.RETRY, TransferAction.CANCEL)
        COMPLETED, CANCELLED -> setOf(TransferAction.REMOVE)
    }

    /** The state a user action moves to, or null for REMOVE (a deletion, not a transition). */
    fun targetOf(action: TransferAction): TransferState? = when (action) {
        TransferAction.PAUSE -> PAUSED
        TransferAction.RESUME, TransferAction.RETRY -> QUEUED
        TransferAction.CANCEL -> CANCELLED
        TransferAction.REMOVE -> null
    }
}
