package com.maanit.stableshare.engine

import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredTransfersTest {

    private fun row(id: String, state: TransferState) = TransferEntity(
        id = id, type = TransferType.UPLOAD, fileName = "f", fileSize = 1, mimeType = null, localUri = "file:///f",
        remoteId = id, chunkSize = 1, totalChunks = 1, sha256 = null, sourceLastModified = null, etag = null,
        state = state, bytesDone = 0, errorCode = null, errorMessage = null, attemptCount = 0, nextRetryAt = null,
        sessionCreated = false, createdAt = 0, updatedAt = 0, completedAt = null,
    )

    @Test
    fun idsStayUntilTerminalOrDeleted() {
        val restored = RestoredTransfers()
        restored.add(listOf("a", "b", "c"))
        restored.prune(listOf(row("a", TransferState.TRANSFERRING), row("b", TransferState.PAUSED), row("c", TransferState.FAILED)))
        assertEquals(setOf("a", "b", "c"), restored.restored.value)

        restored.prune(listOf(row("a", TransferState.COMPLETED), row("b", TransferState.CANCELLED), row("c", TransferState.QUEUED)))
        assertEquals(setOf("c"), restored.restored.value)

        restored.prune(emptyList())
        assertEquals(emptySet<String>(), restored.restored.value)
    }

    @Test
    fun addingNothingKeepsTheSet() {
        val restored = RestoredTransfers()
        restored.add(listOf("x"))
        restored.add(emptyList())
        assertEquals(setOf("x"), restored.restored.value)
    }
}
