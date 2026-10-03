package com.maanit.stableshare.engine

import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.StateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory set of transfers that restart reconciliation moved back to QUEUED in this process
 * (UI-SPEC §5.4.2). Never persisted, so it empties when the process dies; an id leaves the set
 * once its transfer reaches a terminal state or is deleted.
 */
class RestoredTransfers {
    private val ids = MutableStateFlow<Set<String>>(emptySet())
    val restored: StateFlow<Set<String>> = ids.asStateFlow()

    fun add(reconciled: Collection<String>) {
        if (reconciled.isNotEmpty()) ids.update { it + reconciled }
    }

    /** Drops ids whose row is gone or terminal; call with every emission of the transfer table. */
    fun prune(rows: List<TransferEntity>) {
        val live = rows.filterNot { StateMachine.isTerminal(it.state) }.mapTo(HashSet()) { it.id }
        ids.update { current -> if (current.all { it in live }) current else current.filterTo(HashSet()) { it in live } }
    }
}
