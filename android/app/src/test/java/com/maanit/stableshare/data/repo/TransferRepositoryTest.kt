package com.maanit.stableshare.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.net.Manifest
import com.maanit.stableshare.data.net.ManifestChunk
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import com.maanit.stableshare.domain.TransferType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TransferRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .build()
        repo = TransferRepository(db) { now }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun upload(size: Long = 5_000, chunk: Int = 2_000, at: Long = now): String {
        now = at
        return repo.createUpload("f.bin", size, "application/octet-stream", "file:///src/f.bin", chunk).id
    }

    /** Drives a transfer to [target] through legal transitions. */
    private suspend fun moveTo(id: String, vararg path: TransferState) {
        path.forEach { assertTrue("→ $it", repo.transition(id, it)) }
    }

    private suspend fun state(id: String) = repo.getTransfer(id)!!.state

    // ---- creation ----

    @Test
    fun createUploadInsertsQueuedTransferChunksAndEvent() = runBlocking {
        val id = upload(size = 5_000, chunk = 2_000)
        val t = repo.getTransfer(id)!!
        assertEquals(QUEUED, t.state)
        assertEquals(TransferType.UPLOAD, t.type)
        assertEquals(id, t.remoteId)
        assertEquals(3, t.totalChunks)
        assertEquals(0L, t.bytesDone)
        assertEquals(listOf(2_000, 2_000, 1_000), repo.getChunks(id).map { it.length })
        assertTrue(repo.getChunks(id).all { it.status == ChunkStatus.PENDING })
        val events = repo.getEvents(id)
        assertEquals(1, events.size)
        assertEquals(EventType.STATE_CHANGE, events[0].type)
        assertEquals(QUEUED, events[0].toState)
    }

    @Test
    fun createZeroByteUploadHasNoChunks() = runBlocking {
        val id = upload(size = 0)
        assertEquals(0, repo.getTransfer(id)!!.totalChunks)
        assertTrue(repo.getChunks(id).isEmpty())
    }

    @Test
    fun createDownloadCopiesManifestHashesAndEtag() = runBlocking {
        val manifest = manifest(size = 2_500, chunk = 1_000)
        val t = repo.createDownload(manifest, "file:///dl/x.part")
        assertEquals(TransferType.DOWNLOAD, t.type)
        assertEquals("sample", t.remoteId)
        assertEquals("\"etag\"", t.etag)
        assertEquals(manifest.sha256, t.sha256)
        assertEquals(manifest.chunks.map { it.sha256 }, repo.getChunks(t.id).map { it.sha256 })
    }

    @Test
    fun createDownloadRejectsInconsistentManifest() {
        val bad = manifest(2_500, 1_000).let { it.copy(chunks = it.chunks.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.createDownload(bad, "file:///dl/x.part") }
        }
        val shifted = manifest(2_500, 1_000).let { m ->
            m.copy(chunks = m.chunks.map { if (it.index == 1) it.copy(offset = 999) else it })
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.createDownload(shifted, "file:///dl/x.part") }
        }
    }

    // ---- transitions ----

    @Test
    fun illegalTransitionRejectedAndStateUnchanged() = runBlocking {
        val id = upload()
        val eventsBefore = repo.getEvents(id).size
        assertFalse(repo.transition(id, COMPLETED))
        assertFalse(repo.transition(id, VERIFYING))
        assertFalse(repo.transition(id, RETRYING))
        assertEquals(QUEUED, state(id))
        assertEquals(eventsBefore, repo.getEvents(id).size)
    }

    @Test
    fun everyIllegalTransitionFromEveryReachableStateIsRejected() = runBlocking {
        val paths = mapOf(
            QUEUED to listOf(),
            TRANSFERRING to listOf(TRANSFERRING),
            RETRYING to listOf(TRANSFERRING, RETRYING),
            VERIFYING to listOf(TRANSFERRING, VERIFYING),
            PAUSED to listOf(PAUSED),
            FAILED to listOf(TRANSFERRING, FAILED),
            COMPLETED to listOf(TRANSFERRING, VERIFYING, COMPLETED),
            CANCELLED to listOf(CANCELLED),
        )
        for ((start, path) in paths) {
            for (target in TransferState.entries) {
                val id = upload()
                moveTo(id, *path.toTypedArray())
                assertEquals(start, state(id))
                val ok = repo.transition(id, target)
                assertEquals("$start → $target", StateMachine.canTransition(start, target), ok)
                assertEquals(if (ok) target else start, state(id))
            }
        }
    }

    @Test
    fun transitionToUnknownIdReturnsFalse() = runBlocking {
        assertFalse(repo.transition("nope", TRANSFERRING))
    }

    @Test
    fun expectedFromActsAsCompareAndSet() = runBlocking {
        val id = upload()
        moveTo(id, TRANSFERRING, RETRYING)
        // User pauses during the backoff…
        assertTrue(repo.transition(id, PAUSED))
        // …so a late "RETRYING → QUEUED" from a stopped worker must not un-pause it.
        assertFalse(repo.transition(id, QUEUED, expectedFrom = RETRYING))
        assertEquals(PAUSED, state(id))
        assertTrue(repo.transition(id, QUEUED, expectedFrom = PAUSED))
    }

    @Test
    fun cancelledNeverMovesToPausedOrQueued() = runBlocking {
        val id = upload()
        moveTo(id, TRANSFERRING, CANCELLED)
        for (target in TransferState.entries) assertFalse(repo.transition(id, target))
        assertEquals(0, repo.reconcileAfterProcessStart().size)
        assertTrue(repo.claimNextQueued(10).isEmpty())
        assertEquals(CANCELLED, state(id))
    }

    @Test
    fun retryingRecordsErrorAndNextRetryAtAndTransferringClearsThem() = runBlocking {
        val id = upload()
        moveTo(id, TRANSFERRING)
        assertTrue(repo.transition(id, RETRYING, ErrorCode.TIMEOUT, "read timed out", nextRetryAt = 9_999))
        repo.getTransfer(id)!!.let {
            assertEquals(ErrorCode.TIMEOUT, it.errorCode)
            assertEquals("read timed out", it.errorMessage)
            assertEquals(9_999L, it.nextRetryAt)
        }
        assertTrue(repo.transition(id, TRANSFERRING))
        repo.getTransfer(id)!!.let {
            assertNull(it.errorCode)
            assertNull(it.nextRetryAt)
        }
        val change = repo.getEvents(id).last { it.type == EventType.STATE_CHANGE && it.toState == RETRYING }
        assertTrue(change.message, change.message.contains("TIMEOUT"))
    }

    @Test
    fun completedSetsCompletedAt() = runBlocking {
        val id = upload()
        moveTo(id, TRANSFERRING, VERIFYING)
        now = 7_777
        assertTrue(repo.transition(id, COMPLETED))
        assertEquals(7_777L, repo.getTransfer(id)!!.completedAt)
    }

    @Test
    fun manualRetryResetsAttemptsButKeepsDoneChunks() = runBlocking {
        val id = upload(size = 5_000, chunk = 2_000)
        moveTo(id, TRANSFERRING)
        assertTrue(repo.markChunkDone(id, 0, "a".repeat(64)))
        assertEquals(1, repo.incrementAttempts(id, 1))
        assertEquals(2, repo.incrementAttempts(id, 1))
        assertTrue(repo.transition(id, FAILED, ErrorCode.RETRIES_EXHAUSTED, "gave up"))
        assertEquals(2, repo.getTransfer(id)!!.attemptCount)

        assertTrue(repo.transition(id, QUEUED))
        val t = repo.getTransfer(id)!!
        assertEquals(0, t.attemptCount)
        assertNull(t.errorCode)
        assertEquals(2_000L, t.bytesDone)
        assertTrue(repo.getChunks(id).all { it.attempts == 0 })
        assertEquals(ChunkStatus.DONE, repo.getChunks(id)[0].status)
    }

    // ---- chunk progress ----

    @Test
    fun markChunkDoneIgnoredUnlessTransferring() = runBlocking {
        val id = upload()
        assertFalse("QUEUED", repo.markChunkDone(id, 0, null))
        moveTo(id, PAUSED)
        assertFalse("PAUSED", repo.markChunkDone(id, 0, null))
        moveTo(id, QUEUED, TRANSFERRING, RETRYING)
        assertFalse("RETRYING", repo.markChunkDone(id, 0, null))
        moveTo(id, TRANSFERRING)
        assertTrue("TRANSFERRING", repo.markChunkDone(id, 0, null))
        moveTo(id, CANCELLED)
        assertFalse("CANCELLED", repo.markChunkDone(id, 1, null))
        assertFalse("CANCELLED", repo.markChunkFailed(id, 1, "x"))

        val statuses = repo.getChunks(id).map { it.status }
        assertEquals(listOf(ChunkStatus.DONE, ChunkStatus.PENDING, ChunkStatus.PENDING), statuses)
        assertEquals(2_000L, repo.getTransfer(id)!!.bytesDone)
        assertEquals(1, repo.getEvents(id).count { it.type == EventType.CHUNK_DONE })
    }

    @Test
    fun bytesDoneTracksDoneChunksAcrossEveryChunkWrite() = runBlocking {
        val id = upload(size = 5_000, chunk = 2_000) // 2000, 2000, 1000
        moveTo(id, TRANSFERRING)
        suspend fun check() {
            val expected = repo.getChunks(id).filter { it.status == ChunkStatus.DONE }.sumOf { it.length.toLong() }
            assertEquals(expected, repo.getTransfer(id)!!.bytesDone)
        }
        repo.markChunkDone(id, 2, "b".repeat(64)); check()
        assertEquals(1_000L, repo.getTransfer(id)!!.bytesDone)
        repo.markChunkDone(id, 0, null); check()
        repo.markChunkDone(id, 0, null); check() // idempotent
        assertEquals(3_000L, repo.getTransfer(id)!!.bytesDone)
        repo.markChunkFailed(id, 0, "corrupt"); check()
        assertEquals(1_000L, repo.getTransfer(id)!!.bytesDone)
        repo.applyServerReceivedChunks(id, listOf(0, 1)); check()
        assertEquals(4_000L, repo.getTransfer(id)!!.bytesDone)
        repo.resetChunks(id, listOf(1)); check()
        assertEquals(2_000L, repo.getTransfer(id)!!.bytesDone)
        repo.resetChunks(id); check()
        assertEquals(0L, repo.getTransfer(id)!!.bytesDone)
    }

    @Test
    fun applyServerReceivedChunksMakesServerTheSourceOfTruth() = runBlocking {
        val id = upload(size = 10_000, chunk = 2_000) // 5 chunks
        moveTo(id, TRANSFERRING)
        repo.markChunkDone(id, 4, null)
        repo.markChunkFailed(id, 3, "timeout")
        assertTrue(repo.applyServerReceivedChunks(id, listOf(0, 2)))
        assertEquals(
            listOf(ChunkStatus.DONE, ChunkStatus.PENDING, ChunkStatus.DONE, ChunkStatus.PENDING, ChunkStatus.PENDING),
            repo.getChunks(id).map { it.status },
        )
        // Also allowed in VERIFYING (MISSING_CHUNKS re-sync), refused when PAUSED.
        moveTo(id, VERIFYING)
        assertTrue(repo.applyServerReceivedChunks(id, listOf(0, 1, 2)))
        moveTo(id, RETRYING, PAUSED)
        assertFalse(repo.applyServerReceivedChunks(id, listOf(0, 1, 2, 3, 4)))
        assertFalse(repo.resetChunks(id))
        assertEquals(6_000L, repo.getTransfer(id)!!.bytesDone)
    }

    @Test
    fun applyServerReceivedChunksHandlesMoreThanSqliteVariableLimit() = runBlocking {
        val id = upload(size = 1_500L * 1_024, chunk = 1_024) // 1500 chunks
        moveTo(id, TRANSFERRING)
        assertTrue(repo.applyServerReceivedChunks(id, (0 until 1_200).toList()))
        assertEquals(1_200L * 1_024, repo.getTransfer(id)!!.bytesDone)
    }

    @Test
    fun incrementAttemptsCountsPerChunkOnlyWhileActive() = runBlocking {
        val id = upload()
        assertNull(repo.incrementAttempts(id, 0))
        moveTo(id, TRANSFERRING)
        assertEquals(1, repo.incrementAttempts(id, 0))
        moveTo(id, RETRYING)
        assertEquals(2, repo.incrementAttempts(id, 0))
        assertEquals(1, repo.incrementAttempts(id, 1))
        assertEquals(3, repo.getTransfer(id)!!.attemptCount)
        assertNull(repo.incrementAttempts(id, 99))
    }

    // ---- claiming ----

    @Test
    fun claimNextQueuedRespectsLimitAndCreatedAtOrder() = runBlocking {
        val c = upload(at = 300)
        val a = upload(at = 100)
        val b = upload(at = 200)
        val claimed = repo.claimNextQueued(2)
        assertEquals(listOf(a, b), claimed.map { it.id })
        assertTrue(claimed.all { it.state == TRANSFERRING })
        assertEquals(QUEUED, state(c))
        assertEquals(listOf(c), repo.claimNextQueued(5).map { it.id })
        assertTrue(repo.claimNextQueued(5).isEmpty())
        assertTrue(repo.claimNextQueued(0).isEmpty())
    }

    @Test
    fun claimSkipsPausedAndTakesOnlyDueRetries() = runBlocking {
        val paused = upload(at = 10)
        moveTo(paused, PAUSED)
        val due = upload(at = 20)
        moveTo(due, TRANSFERRING)
        repo.transition(due, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = 5_000)
        val later = upload(at = 30)
        moveTo(later, TRANSFERRING)
        repo.transition(later, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = 50_000)
        val waitingForNetwork = upload(at = 40)
        moveTo(waitingForNetwork, TRANSFERRING)
        repo.transition(waitingForNetwork, RETRYING, ErrorCode.NETWORK_UNAVAILABLE, "offline", nextRetryAt = null)

        now = 4_999
        assertTrue(repo.claimNextQueued(10).isEmpty())
        now = 5_000
        assertEquals(listOf(due), repo.claimNextQueued(10).map { it.id })
        assertEquals(PAUSED, state(paused))
        assertEquals(RETRYING, state(later))
        assertEquals(RETRYING, state(waitingForNetwork))
    }

    @Test
    fun claimSkipsExcludedIds() = runBlocking {
        val owned = upload(at = 10)
        moveTo(owned, TRANSFERRING)
        repo.transition(owned, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = 0)
        val queued = upload(at = 20)
        assertEquals(listOf(queued), repo.claimNextQueued(5, exclude = listOf(owned)).map { it.id })
        assertEquals(RETRYING, state(owned))
        assertEquals(listOf(owned), repo.claimNextQueued(5).map { it.id })
    }

    @Test
    fun promoteDueRetriesAndNetworkWaitersToQueued() = runBlocking {
        val due = upload(at = 10)
        moveTo(due, TRANSFERRING)
        repo.transition(due, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = 2_000)
        val later = upload(at = 20)
        moveTo(later, TRANSFERRING)
        repo.transition(later, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = 9_000)
        val offline = upload(at = 30)
        moveTo(offline, TRANSFERRING)
        repo.transition(offline, RETRYING, ErrorCode.NETWORK_UNAVAILABLE, "offline", nextRetryAt = null)
        val paused = upload(at = 40)
        moveTo(paused, PAUSED)

        now = 2_000
        assertEquals(1, repo.promoteDueRetries())
        assertEquals(QUEUED, state(due))
        assertEquals(RETRYING, state(later))
        assertEquals(RETRYING, state(offline))

        assertEquals(1, repo.promoteWaitingForNetwork())
        assertEquals(QUEUED, state(offline))
        assertEquals(RETRYING, state(later))
        assertEquals(PAUSED, state(paused))
        assertEquals(0, repo.promoteWaitingForNetwork())
    }

    @Test
    fun meteredWaitersArePromotedButPausedAndCancelledNeverAre() = runBlocking {
        val metered = upload(at = 10)
        moveTo(metered, TRANSFERRING)
        repo.transition(metered, RETRYING, ErrorCode.METERED_NETWORK, "wifi only", nextRetryAt = null)
        val pausedWhileWaiting = upload(at = 20)
        moveTo(pausedWhileWaiting, TRANSFERRING)
        repo.transition(pausedWhileWaiting, RETRYING, ErrorCode.METERED_NETWORK, "wifi only", nextRetryAt = null)
        moveTo(pausedWhileWaiting, PAUSED)
        val cancelledWhileWaiting = upload(at = 30)
        moveTo(cancelledWhileWaiting, TRANSFERRING)
        repo.transition(cancelledWhileWaiting, RETRYING, ErrorCode.NETWORK_UNAVAILABLE, "offline", nextRetryAt = null)
        moveTo(cancelledWhileWaiting, CANCELLED)
        val backingOff = upload(at = 40)
        moveTo(backingOff, TRANSFERRING)
        repo.transition(backingOff, RETRYING, ErrorCode.TIMEOUT, "slow", nextRetryAt = 60_000)

        assertEquals(1, repo.promoteWaitingForNetwork())
        assertEquals(QUEUED, state(metered))
        assertEquals("keeps its code until it runs", ErrorCode.METERED_NETWORK, repo.getTransfer(metered)!!.errorCode)
        assertEquals(PAUSED, state(pausedWhileWaiting))
        assertEquals(CANCELLED, state(cancelledWhileWaiting))
        assertEquals(RETRYING, state(backingOff))
    }

    @Test
    fun recodeNetworkWaitersChangesOnlyTheReason() = runBlocking {
        val waiting = upload(at = 10)
        moveTo(waiting, TRANSFERRING)
        repo.transition(waiting, RETRYING, ErrorCode.METERED_NETWORK, "wifi only", nextRetryAt = null)
        val backingOff = upload(at = 20)
        moveTo(backingOff, TRANSFERRING)
        repo.transition(backingOff, RETRYING, ErrorCode.SERVER_ERROR, "503", nextRetryAt = 60_000)
        val paused = upload(at = 30)
        moveTo(paused, TRANSFERRING)
        repo.transition(paused, RETRYING, ErrorCode.METERED_NETWORK, "wifi only", nextRetryAt = null)
        moveTo(paused, PAUSED)
        val stateEvents = repo.getEvents(waiting).count { it.type == EventType.STATE_CHANGE }

        assertEquals(1, repo.recodeNetworkWaiters(ErrorCode.NETWORK_UNAVAILABLE))
        val row = repo.getTransfer(waiting)!!
        assertEquals(RETRYING, row.state)
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, row.errorCode)
        assertNull(row.nextRetryAt)
        assertEquals("no STATE_CHANGE", stateEvents, repo.getEvents(waiting).count { it.type == EventType.STATE_CHANGE })
        assertEquals("Still waiting: now offline", repo.getEvents(waiting).last().message)
        assertEquals(ErrorCode.SERVER_ERROR, repo.getTransfer(backingOff)!!.errorCode)
        assertEquals(PAUSED, state(paused))
        assertEquals(ErrorCode.METERED_NETWORK, repo.getTransfer(paused)!!.errorCode)

        assertEquals("already that code", 0, repo.recodeNetworkWaiters(ErrorCode.NETWORK_UNAVAILABLE))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recodeNetworkWaiters(ErrorCode.TIMEOUT) } }
        assertEquals(1, repo.recodeNetworkWaiters(ErrorCode.METERED_NETWORK))
        assertEquals(ErrorCode.METERED_NETWORK, repo.getTransfer(waiting)!!.errorCode)
    }

    @Test
    fun meteredNetworkCodeRoundTripsThroughRoomByName() = runBlocking {
        // ErrorCode is stored by name (TEXT), so a new enum value needs no migration.
        val id = upload()
        moveTo(id, TRANSFERRING)
        repo.transition(id, RETRYING, ErrorCode.METERED_NETWORK, "wifi only", nextRetryAt = null)
        assertEquals(ErrorCode.METERED_NETWORK, repo.getTransfer(id)!!.errorCode)
        val stored = withContext(Dispatchers.IO) {
            db.query("SELECT errorCode FROM transfers WHERE id = ?", arrayOf(id)).use {
                it.moveToFirst()
                it.getString(0)
            }
        }
        assertEquals("METERED_NETWORK", stored)
    }

    @Test
    fun setSourceInfoRecordsHashAndMtime() = runBlocking {
        val id = upload()
        assertTrue(repo.setSourceInfo(id, "AB".repeat(32), 1234L))
        val t = repo.getTransfer(id)!!
        assertEquals("ab".repeat(32), t.sha256)
        assertEquals(1234L, t.sourceLastModified)
    }

    @Test
    fun concurrentClaimsNeverOverlap() = runBlocking {
        val ids = (1..12).map { upload(at = it.toLong()) }
        val results = (1..6).map {
            async(Dispatchers.IO) { repo.claimNextQueued(3) }
        }.awaitAll()
        val claimed = results.flatten().map { it.id }
        assertEquals("no transfer claimed twice", claimed.size, claimed.toSet().size)
        assertEquals(ids.toSet(), claimed.toSet())
        assertTrue(results.all { it.size <= 3 })
        assertTrue(ids.all { state(it) == TRANSFERRING })
    }

    // ---- reconciliation ----

    @Test
    fun reconciliationRequeuesOnlyInFlightRows() = runBlocking {
        val transferring = upload().also { moveTo(it, TRANSFERRING) }
        val verifying = upload().also { moveTo(it, TRANSFERRING, VERIFYING) }
        val retrying = upload().also {
            moveTo(it, TRANSFERRING)
            repo.transition(it, RETRYING, ErrorCode.TIMEOUT, "t", nextRetryAt = 99)
        }
        val queued = upload()
        val paused = upload().also { moveTo(it, PAUSED) }
        val failed = upload().also { moveTo(it, TRANSFERRING, FAILED) }
        val completed = upload().also { moveTo(it, TRANSFERRING, VERIFYING, COMPLETED) }
        val cancelled = upload().also { moveTo(it, CANCELLED) }

        assertEquals(2, repo.reconcileAfterProcessStart().size)
        assertEquals(QUEUED, state(transferring))
        assertEquals(QUEUED, state(verifying))
        assertEquals(RETRYING, state(retrying))
        assertEquals(QUEUED, state(queued))
        assertEquals(PAUSED, state(paused))
        assertEquals(FAILED, state(failed))
        assertEquals(COMPLETED, state(completed))
        assertEquals(CANCELLED, state(cancelled))
        assertTrue(repo.getEvents(transferring).any { it.type == EventType.INFO && it.message.contains("Reconciled") })
        assertEquals(0, repo.reconcileAfterProcessStart().size)
    }

    @Test
    fun reconciliationKeepsDoneChunks() = runBlocking {
        val id = upload()
        moveTo(id, TRANSFERRING)
        repo.markChunkDone(id, 0, null)
        repo.reconcileAfterProcessStart()
        assertEquals(ChunkStatus.DONE, repo.getChunks(id)[0].status)
        assertEquals(2_000L, repo.getTransfer(id)!!.bytesDone)
    }

    // ---- deletion ----

    @Test
    fun deleteOnlyTerminalAndCascades() = runBlocking {
        val active = upload()
        moveTo(active, TRANSFERRING)
        assertFalse(repo.deleteTransfer(active))
        val failed = upload().also { moveTo(it, TRANSFERRING, FAILED) }
        assertFalse(repo.deleteTransfer(failed))

        val done = upload()
        moveTo(done, TRANSFERRING)
        repo.markChunkDone(done, 0, null)
        moveTo(done, CANCELLED)
        assertTrue(repo.getChunks(done).isNotEmpty())
        assertTrue(repo.deleteTransfer(done))
        assertNull(repo.getTransfer(done))
        assertTrue(repo.getChunks(done).isEmpty())
        assertTrue(repo.getEvents(done).isEmpty())
        assertNotNull(repo.getTransfer(active))
    }

    @Test
    fun clearHistoryDeletesOnlyTerminalRows() = runBlocking {
        val queued = upload()
        val paused = upload().also { moveTo(it, PAUSED) }
        val failed = upload().also { moveTo(it, TRANSFERRING, FAILED) }
        val cancelled = upload().also { moveTo(it, CANCELLED) }
        val cancelledToo = upload().also { moveTo(it, TRANSFERRING, CANCELLED) }

        assertEquals(2, repo.clearHistory())
        assertNull(repo.getTransfer(cancelled))
        assertNull(repo.getTransfer(cancelledToo))
        listOf(queued, paused, failed).forEach { assertNotNull(repo.getTransfer(it)) }
        assertEquals(0, repo.clearHistory())
    }

    // ---- observation ----

    @Test
    fun observeTransferEmitsStateChanges() = runBlocking {
        val id = upload()
        repo.observeTransfer(id).test {
            assertEquals(QUEUED, awaitItem()!!.state)
            repo.transition(id, TRANSFERRING)
            assertEquals(TRANSFERRING, awaitItem()!!.state)
            repo.markChunkDone(id, 0, null)
            assertEquals(2_000L, awaitItem()!!.bytesDone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTransfersAndHistory() = runBlocking {
        repo.observeTransfers().test {
            assertEquals(0, awaitItem().size)
            val id = upload()
            assertEquals(listOf(id), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        val done = upload()
        moveTo(done, CANCELLED)
        repo.observeHistory().test {
            assertEquals(listOf(done), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun manifest(size: Long, chunk: Int): Manifest {
        val chunks = com.maanit.stableshare.domain.ChunkPlanner.plan(size, chunk).map {
            ManifestChunk(it.index, it.offset, it.length, it.index.toString().padStart(64, 'c'))
        }
        return Manifest("sample", "sample.bin", size, "d".repeat(64), "\"etag\"", chunk, chunks)
    }
}
