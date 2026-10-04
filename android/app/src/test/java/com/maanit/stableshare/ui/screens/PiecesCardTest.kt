package com.maanit.stableshare.ui.screens

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.maanit.stableshare.data.db.ChunkEntity
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.detail.DetailUi
import com.maanit.stableshare.ui.detail.PiecesCard
import com.maanit.stableshare.ui.model.TransferItem
import com.maanit.stableshare.ui.theme.StableShareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.maanit.stableshare.ui.theme.Mint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/** UI-SPEC §5.8.6: the chunk map summary, which is also the map's accessibility description. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PiecesCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun ui(statuses: List<ChunkStatus>, size: Long = statuses.size * 1_000L): DetailUi {
        val row = TransferEntity(
            id = "t", type = TransferType.DOWNLOAD, fileName = "f.bin", fileSize = size, mimeType = null, localUri = "file:///f",
            remoteId = "f", chunkSize = 1_000, totalChunks = statuses.size, sha256 = null, sourceLastModified = null, etag = null,
            state = TransferState.TRANSFERRING, bytesDone = 0, errorCode = null, errorMessage = null, attemptCount = 0,
            nextRetryAt = null, sessionCreated = true, createdAt = 0, updatedAt = 0, completedAt = null,
        )
        val chunks = statuses.mapIndexed { i, s -> ChunkEntity("t", i, i * 1_000L, 1_000, null, s, 0, null) }
        return DetailUi(
            item = TransferItem.build(row, null, null, false, 0),
            chunks = chunks,
            doneChunks = statuses.count { it == ChunkStatus.DONE },
            failedChunks = statuses.count { it == ChunkStatus.FAILED },
            activity = emptyList(),
            retryAttempt = 1,
            maxConcurrent = 2,
            verifiedSha = null,
            generated = false,
            now = 0,
        )
    }

    @Test
    fun summaryCountsDoneAndFailedPieces() {
        val statuses = List(4) { ChunkStatus.DONE } + ChunkStatus.FAILED + List(5) { ChunkStatus.PENDING }
        compose.setContent { StableShareTheme(reducedMotion = true) { PiecesCard(ui(statuses)) } }
        compose.onNodeWithText("4 of 10 pieces done, 1 failed").assertExists()
        compose.onNodeWithContentDescription("4 of 10 pieces done, 1 failed").assertExists()
    }

    @Test
    fun noFailuresMeansNoSuffix() {
        compose.setContent { StableShareTheme(reducedMotion = true) { PiecesCard(ui(List(3) { ChunkStatus.DONE })) } }
        compose.onNodeWithText("3 of 3 pieces done").assertExists()
    }

    @Test
    fun zeroByteFilesHaveNoPieces() {
        compose.setContent { StableShareTheme(reducedMotion = true) { PiecesCard(ui(emptyList(), size = 0)) } }
        compose.onNodeWithText("This file has no pieces to send.").assertExists()
    }

    @Test
    fun everyChunkInFlightIsDrawnAsAMovingCell() {
        val base = ui(listOf(ChunkStatus.DONE, ChunkStatus.PENDING, ChunkStatus.PENDING, ChunkStatus.PENDING))
        val moving = base.copy(item = base.item.copy(inFlightChunks = setOf(1, 2)))
        var yellow = Color.Unspecified
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                yellow = Mint.colors.accentYellow
                PiecesCard(moving)
            }
        }
        val summary = "1 of 4 pieces done"
        compose.onNodeWithText(summary).assertExists()
        // Software-draw the window (captureToImage waits for a hardware redraw Robolectric never makes).
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val map = compose.onNodeWithContentDescription(summary).fetchSemanticsNode().boundsInWindow
        // Four chunks fit one row of 10 dp cells with 2 dp gaps; sample each cell's centre.
        val density = compose.density.density
        fun centre(i: Int) = Color(bitmap.getPixel((map.left + (i * 12 + 5) * density).toInt(), (map.top + 5 * density).toInt()))
        assertEquals("chunk 1 moving", yellow, centre(1))
        assertEquals("chunk 2 moving", yellow, centre(2))
        assertNotEquals("chunk 0 is done", yellow, centre(0))
        assertNotEquals("chunk 3 is waiting", yellow, centre(3))
        compose.onNodeWithText("Moving").assertExists()
    }
}
