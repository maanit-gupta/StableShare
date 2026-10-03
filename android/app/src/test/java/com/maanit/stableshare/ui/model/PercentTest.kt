package com.maanit.stableshare.ui.model

import com.maanit.stableshare.domain.TransferState
import org.junit.Assert.assertEquals
import org.junit.Test

class PercentTest {
    @Test
    fun floorsAndCapsAt99UntilCompleted() {
        assertEquals(0, displayPercent(0, 1_000, TransferState.TRANSFERRING))
        assertEquals(42, displayPercent(429, 1_000, TransferState.TRANSFERRING))
        assertEquals(99, displayPercent(999, 1_000, TransferState.TRANSFERRING))
        assertEquals(99, displayPercent(1_000, 1_000, TransferState.VERIFYING))
        assertEquals(99, displayPercent(1_000, 1_000, TransferState.CANCELLED))
        assertEquals(100, displayPercent(1_000, 1_000, TransferState.COMPLETED))
    }

    @Test
    fun zeroByteFilesShowZeroUntilCompleted() {
        TransferState.entries.filter { it != TransferState.COMPLETED }.forEach {
            assertEquals(it.name, 0, displayPercent(0, 0, it))
        }
        assertEquals(100, displayPercent(0, 0, TransferState.COMPLETED))
    }

    @Test
    fun outOfRangeBytesAreClamped() {
        assertEquals(99, displayPercent(5_000, 1_000, TransferState.TRANSFERRING))
        assertEquals(0, displayPercent(-1, 1_000, TransferState.QUEUED))
    }
}
