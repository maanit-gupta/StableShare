package com.maanit.stableshare.engine

import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import com.maanit.stableshare.engine.FakeTransferServer.Fault
import com.maanit.stableshare.engine.FakeTransferServer.Op
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Download pipeline driven through the real coordinator against the fake server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadPipelineTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val harnesses = mutableListOf<EngineHarness>()

    @Before
    fun setUp() {
        dir = tmp.newFolder()
    }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    private fun TestScope.harness() =
        EngineHarness(ApplicationProvider.getApplicationContext(), dir, clock = { testScheduler.currentTime })
            .also { harnesses += it }

    private val chunk = EngineHarness.CHUNK

    private fun EngineHarness.rangeCounts(total: Int) = (0 until total).map { server.count(Op.RANGE, it) }

    @Test
    fun happyPathVerifiesAndRenamesAtomically() = runTest {
        val h = harness()
        val (t, bytes) = h.download(5 * chunk + 7)
        val part = h.localFile(t.id)
        assertTrue(part.name.endsWith(".part"))
        h.engine.run()

        val row = h.row(t.id)
        assertEquals(COMPLETED, row.state)
        val saved = h.localFile(t.id)
        assertFalse(saved.name.endsWith(".part"))
        assertFalse("part file renamed away", part.exists())
        assertArrayEquals(bytes, saved.readBytes())
        assertEquals(bytes.size.toLong(), row.bytesDone)
        assertEquals(List(6) { 1 }, h.rangeCounts(6))
        val events = h.events(t.id)
        val verified = events.indexOfFirst { it.type == EventType.VERIFIED }
        val completed = events.indexOfFirst { it.toState == COMPLETED }
        assertTrue(verified in 0 until completed)
        assertEquals(VERIFYING, events[completed].fromState)
    }

    @Test
    fun chunkCorruptedInTransitIsRefetchedAlone() = runTest {
        val h = harness()
        h.server.fault = { c -> if (c.op == Op.RANGE && c.index == 1 && c.attempt == 1) Fault.Corrupt else null }
        val (t, bytes) = h.download(4 * chunk)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(listOf(1, 2, 1, 1), h.rangeCounts(4))
        assertArrayEquals(bytes, h.localFile(t.id).readBytes())
        assertEquals(1, h.events(t.id).count { it.type == EventType.CHUNK_FAILED && it.chunkIndex == 1 })
        assertEquals(1, h.repo.getChunks(t.id)[1].attempts)
    }

    @Test
    fun fullFileMismatchRefetchesOnlyTheBadChunks() = runTest {
        val h = harness()
        lateinit var id: String
        // While the last chunk is being fetched, chunk 0 (long DONE, outside the tail check) rots on disk.
        h.server.onRequest = { c -> if (c.op == Op.RANGE && c.index == 4 && c.attempt == 1) h.corruptOnDisk(id, 0) }
        val (t, bytes) = h.download(5 * chunk)
        id = t.id
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(listOf(2, 1, 1, 1, 1), h.rangeCounts(5))
        assertArrayEquals(bytes, h.localFile(t.id).readBytes())
        val retry = h.events(t.id).single { it.fromState == VERIFYING && it.toState == RETRYING }
        assertTrue(retry.message, retry.message.contains("FILE_HASH_MISMATCH"))
    }

    @Test
    fun tornTailChunkIsRefetchedOnResume() = runTest {
        val h = harness()
        val (t, bytes) = h.download(5 * chunk)
        val held = h.holdAt(Op.RANGE, 3)
        val run = launch { h.engine.run() }
        held.await()
        assertTrue(h.controller.pause(t.id))
        run.join()
        assertEquals(PAUSED, h.state(t.id))

        h.corruptOnDisk(t.id, 2) // the newest DONE chunk: what a lost fsync would leave behind
        h.server.onRequest = {}
        assertTrue(h.controller.resume(t.id))
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(listOf(1, 1, 2, 2, 1), h.rangeCounts(5))
        assertArrayEquals(bytes, h.localFile(t.id).readBytes())
        assertTrue(h.events(t.id).any { it.message.contains("On-disk check failed for chunk(s) [2]") })
        assertFalse("caught before full verification", h.events(t.id).any { it.toState == RETRYING })
    }

    @Test
    fun partFileDeletedWhilePausedRestartsFromChunkZero() = runTest {
        val h = harness()
        val (t, bytes) = h.download(4 * chunk)
        val held = h.holdAt(Op.RANGE, 2)
        val run = launch { h.engine.run() }
        held.await()
        h.controller.pause(t.id)
        run.join()

        assertTrue(h.localFile(t.id).delete())
        h.server.onRequest = {}
        h.controller.resume(t.id)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(listOf(2, 2, 2, 1), h.rangeCounts(4))
        assertArrayEquals(bytes, h.localFile(t.id).readBytes())
    }

    @Test
    fun remoteFileChangedFailsAndKeepsThePartFile() = runTest {
        val h = harness()
        h.server.onRequest = { c -> if (c.op == Op.RANGE && c.index == 2 && c.attempt == 1) h.server.mutate(c.id) }
        val (t, _) = h.download(4 * chunk)
        h.engine.run()

        val row = h.row(t.id)
        assertEquals(FAILED, row.state)
        assertEquals(ErrorCode.REMOTE_FILE_CHANGED, row.errorCode)
        assertTrue("part kept until cancel/remove", h.localFile(t.id).exists())
        assertEquals(2L * chunk, row.bytesDone)
        assertEquals(0, h.server.count(Op.RANGE, 3))
    }

    @Test
    fun zeroByteDownloadCompletesAfterVerification() = runTest {
        val h = harness()
        val (t, _) = h.download(0)
        h.engine.run()

        assertEquals(COMPLETED, h.state(t.id))
        assertEquals(0L, h.localFile(t.id).length())
        assertEquals(0, h.server.count(Op.RANGE))
        assertTrue(h.events(t.id).any { it.type == EventType.VERIFIED })
    }

    @Test
    fun diskFullFails() = runTest {
        val h = harness()
        h.files.full = true
        val (t, _) = h.download(3 * chunk)
        h.engine.run()

        assertEquals(FAILED, h.state(t.id))
        assertEquals(ErrorCode.DISK_FULL, h.row(t.id).errorCode)
        assertEquals(1, h.server.count(Op.RANGE, 0))
        assertTrue(h.repo.getChunks(t.id).none { it.status == ChunkStatus.DONE })
    }
}
