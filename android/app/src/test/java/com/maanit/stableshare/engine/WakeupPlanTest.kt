package com.maanit.stableshare.engine

import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeupPlanTest {

    private fun row(state: TransferState, nextRetryAt: Long? = null) = TransferEntity(
        id = "id-$state-$nextRetryAt", type = TransferType.UPLOAD, fileName = "f", fileSize = 1, mimeType = null,
        localUri = "file:///f", remoteId = null, chunkSize = 1024, totalChunks = 1, sha256 = null,
        sourceLastModified = null, etag = null, state = state, bytesDone = 0, errorCode = null, errorMessage = null,
        attemptCount = 0, nextRetryAt = nextRetryAt, sessionCreated = false, createdAt = 0, updatedAt = 0, completedAt = null,
    )

    @Test
    fun nothingWaitingMeansNoWakeup() {
        assertNull(WakeupPlan.compute(emptyList(), 1_000))
        assertNull(WakeupPlan.compute(listOf(row(TransferState.PAUSED), row(TransferState.FAILED)), 1_000))
    }

    @Test
    fun earliestBackoffSetsTheDelay() {
        val plan = WakeupPlan.compute(listOf(row(TransferState.RETRYING, 9_000), row(TransferState.RETRYING, 4_000)), 1_000)
        assertEquals(WakeupPlan(3_000, requiresNetwork = true), plan)
    }

    @Test
    fun overdueBackoffWakesImmediately() {
        assertEquals(WakeupPlan(0, true), WakeupPlan.compute(listOf(row(TransferState.RETRYING, 500)), 1_000))
    }

    @Test
    fun networkWaitersUseTheFloorUnlessABackoffIsSooner() {
        val offline = row(TransferState.RETRYING, null)
        assertEquals(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, true), WakeupPlan.compute(listOf(offline), 0))
        assertEquals(WakeupPlan(WakeupPlan.NETWORK_FLOOR_MS, true), WakeupPlan.compute(listOf(offline, row(TransferState.RETRYING, 60_000)), 0))
        assertEquals(WakeupPlan(2_000, true), WakeupPlan.compute(listOf(offline, row(TransferState.RETRYING, 2_000)), 0))
    }
}
