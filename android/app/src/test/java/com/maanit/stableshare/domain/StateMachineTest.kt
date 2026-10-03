package com.maanit.stableshare.domain

import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineTest {

    // Written out independently of StateMachine, straight from the CLAUDE.md table.
    private val expected = setOf(
        QUEUED to TRANSFERRING, QUEUED to PAUSED, QUEUED to CANCELLED,
        TRANSFERRING to VERIFYING, TRANSFERRING to RETRYING, TRANSFERRING to PAUSED,
        TRANSFERRING to FAILED, TRANSFERRING to CANCELLED, TRANSFERRING to QUEUED,
        RETRYING to TRANSFERRING, RETRYING to QUEUED, RETRYING to PAUSED,
        RETRYING to FAILED, RETRYING to CANCELLED,
        VERIFYING to COMPLETED, VERIFYING to RETRYING, VERIFYING to FAILED,
        VERIFYING to CANCELLED, VERIFYING to QUEUED,
        PAUSED to QUEUED, PAUSED to CANCELLED,
        FAILED to QUEUED, FAILED to CANCELLED,
    )

    @Test
    fun everyStatePairMatchesTheTable() {
        var checked = 0
        for (from in TransferState.entries) {
            for (to in TransferState.entries) {
                assertEquals("$from -> $to", (from to to) in expected, StateMachine.canTransition(from, to))
                checked++
            }
        }
        assertEquals(64, checked)
    }

    @Test
    fun noSelfTransitions() {
        TransferState.entries.forEach { assertFalse(it.name, StateMachine.canTransition(it, it)) }
    }

    @Test
    fun terminalStatesHaveNoExits() {
        val terminal = TransferState.entries.filter(StateMachine::isTerminal)
        assertEquals(listOf(COMPLETED, CANCELLED), terminal)
        for (state in terminal) {
            TransferState.entries.forEach { assertFalse("$state -> $it", StateMachine.canTransition(state, it)) }
        }
    }

    @Test
    fun cancelledIsNeverRevived() {
        assertFalse(StateMachine.canTransition(CANCELLED, QUEUED))
        assertFalse(StateMachine.canTransition(CANCELLED, PAUSED))
        assertFalse(StateMachine.canTransition(CANCELLED, TRANSFERRING))
    }

    @Test
    fun completedOnlyFromVerifying() {
        val sources = TransferState.entries.filter { StateMachine.canTransition(it, COMPLETED) }
        assertEquals(listOf(VERIFYING), sources)
    }

    @Test
    fun allowedActionsPerState() {
        val expectedActions = mapOf(
            QUEUED to setOf(TransferAction.PAUSE, TransferAction.CANCEL),
            TRANSFERRING to setOf(TransferAction.PAUSE, TransferAction.CANCEL),
            RETRYING to setOf(TransferAction.PAUSE, TransferAction.CANCEL),
            VERIFYING to setOf(TransferAction.CANCEL),
            PAUSED to setOf(TransferAction.RESUME, TransferAction.CANCEL),
            FAILED to setOf(TransferAction.RETRY, TransferAction.CANCEL),
            COMPLETED to setOf(TransferAction.REMOVE),
            CANCELLED to setOf(TransferAction.REMOVE),
        )
        for (state in TransferState.entries) {
            assertEquals(state.name, expectedActions.getValue(state), StateMachine.allowedActions(state))
        }
    }

    @Test
    fun everyOfferedActionIsALegalTransition() {
        for (state in TransferState.entries) {
            for (action in StateMachine.allowedActions(state)) {
                val target = StateMachine.targetOf(action) ?: continue
                assertTrue("$state --$action--> $target", StateMachine.canTransition(state, target))
            }
        }
    }
}
