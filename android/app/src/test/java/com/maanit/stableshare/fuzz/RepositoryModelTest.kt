package com.maanit.stableshare.fuzz

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.net.Manifest
import com.maanit.stableshare.data.net.ManifestChunk
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferState.CANCELLED
import com.maanit.stableshare.domain.TransferState.COMPLETED
import com.maanit.stableshare.domain.TransferState.FAILED
import com.maanit.stableshare.domain.TransferState.PAUSED
import com.maanit.stableshare.domain.TransferState.QUEUED
import com.maanit.stableshare.domain.TransferState.RETRYING
import com.maanit.stableshare.domain.TransferState.TRANSFERRING
import com.maanit.stableshare.domain.TransferState.VERIFYING
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Layer A: random operations on [TransferRepository] checked after every step against a small
 * reference model (state, error, backoff and chunk statuses per transfer). The legal-transition
 * table is written out here from CLAUDE.md, independently of StateMachine.
 *
 * I1 legal transitions only; an illegal one returns false and changes nothing. I2 terminal rows
 * never change. I3 bytesDone = sum of DONE chunk lengths. I4 markChunkDone succeeds iff
 * TRANSFERRING. I5 claimNextQueued(k) claims at most k runnable rows, oldest first, into
 * TRANSFERRING. I6 reconcile moves exactly the TRANSFERRING/VERIFYING rows to QUEUED. I7 no chunk
 * (or event) row outlives its transfer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryModelTest {

    @Test
    fun randomOperationsMatchTheModel() = FuzzSeeds.run("repository", "*RepositoryModelTest") { seed ->
        runBlocking { Scenario(seed).run() }
    }

    private class ModelChunk(val length: Int, var status: ChunkStatus = ChunkStatus.PENDING)

    private class ModelTransfer(
        val id: String,
        val createdAt: Long,
        val chunks: List<ModelChunk>,
        var state: TransferState = QUEUED,
        var errorCode: ErrorCode? = null,
        var nextRetryAt: Long? = null,
    )

    private class Scenario(val seed: Long) {
        private val rnd = Random(seed)
        private var now = 1_000L
        private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val repo = TransferRepository(db) { now }
        private val model = linkedMapOf<String, ModelTransfer>()
        private val terminalSnapshots = mutableMapOf<String, TransferEntity>()
        private var nextId = 0
        private val log = mutableListOf<String>()

        suspend fun run() {
            try {
                repeat(OPS) { step ->
                    val op = nextOp()
                    log += "#$step $op"
                    check()
                }
                checkEventLog()
            } catch (t: Throwable) {
                throw AssertionError("${t.message}\nLast ops:\n${log.takeLast(8).joinToString("\n")}", t)
            } finally {
                db.close()
            }
        }

        private fun pick(): ModelTransfer? = model.values.toList().randomOrNull(rnd)

        private suspend fun nextOp(): String {
            if (rnd.nextInt(4) == 0) now += rnd.nextLong(0, 5_000)
            val t = pick()
            return when (if (model.isEmpty()) 0 else rnd.nextInt(100)) {
                in 0..7 -> createUpload()
                in 8..13 -> createDownload()
                in 14..43 -> if (t == null) "noop" else transition(t)
                in 44..53 -> claim()
                in 54..65 -> if (t == null) "noop" else markDone(t)
                in 66..68 -> if (t == null) "noop" else markFailed(t)
                in 69..74 -> if (t == null) "noop" else applyServer(t)
                in 75..79 -> if (t == null) "noop" else reset(t)
                in 80..85 -> reconcile()
                in 86..88 -> promoteDue()
                in 89..91 -> promoteWaiting()
                else -> if (t == null) "noop" else delete(t)
            }
        }

        private fun sizes(): Pair<Long, Int> {
            val chunk = listOf(512, 1_000, 1_024).random(rnd)
            val size = when (rnd.nextInt(4)) {
                0 -> 0L
                1 -> chunk.toLong() * rnd.nextInt(1, 6)
                else -> rnd.nextLong(1, 6_000)
            }
            return size to chunk
        }

        private fun lengths(size: Long, chunk: Int): List<Int> =
            (0 until ((size + chunk - 1) / chunk).toInt()).map { i -> minOf(chunk.toLong(), size - i.toLong() * chunk).toInt() }

        private suspend fun createUpload(): String {
            val (size, chunk) = sizes()
            val id = "t${nextId++}"
            repo.createUpload("$id.bin", size, null, "file:///$id", chunk, id = id)
            model[id] = ModelTransfer(id, now, lengths(size, chunk).map { ModelChunk(it) })
            return "createUpload($id, $size, $chunk)"
        }

        private suspend fun createDownload(): String {
            val (size, chunk) = sizes()
            val id = "t${nextId++}"
            var offset = 0L
            val chunks = lengths(size, chunk).mapIndexed { i, len ->
                ManifestChunk(i, offset, len, "%064x".format(i)).also { offset += len }
            }
            repo.createDownload(Manifest("f-$id", "$id.bin", size, "0".repeat(64), "\"e\"", chunk, chunks), "file:///$id.part", id = id)
            model[id] = ModelTransfer(id, now, chunks.map { ModelChunk(it.length) })
            return "createDownload($id, $size, $chunk)"
        }

        private suspend fun transition(t: ModelTransfer): String {
            val to = TransferState.entries.random(rnd)
            val expectedFrom = if (rnd.nextInt(4) == 0) TransferState.entries.random(rnd) else null
            val code = if (rnd.nextBoolean()) ErrorCode.entries.random(rnd) else null
            val retryAt = when (rnd.nextInt(3)) {
                0 -> null
                1 -> now - rnd.nextLong(0, 1_000)
                else -> now + rnd.nextLong(1, 10_000)
            }
            val before = repo.getTransfer(t.id)
            val ok = repo.transition(t.id, to, code, "m", retryAt, expectedFrom)
            val legal = (expectedFrom == null || expectedFrom == t.state) && to in LEGAL_TRANSITIONS.getValue(t.state)
            assertEquals("I1: transition ${t.state} → $to (expectedFrom $expectedFrom) result", legal, ok)
            if (ok) {
                val manualRetry = t.state == FAILED && to == QUEUED
                t.errorCode = when {
                    to == RETRYING || to == FAILED -> code
                    to == TRANSFERRING || to == COMPLETED || manualRetry -> null
                    else -> t.errorCode
                }
                t.nextRetryAt = if (to == RETRYING) retryAt else null
                t.state = to
            } else {
                assertEquals("I1: a refused transition changes nothing", before, repo.getTransfer(t.id))
            }
            return "transition(${t.id}, $to, from=$expectedFrom, code=$code, retryAt=$retryAt) = $ok"
        }

        private suspend fun claim(): String {
            val k = rnd.nextInt(0, 4)
            val exclude = model.keys.filter { rnd.nextInt(5) == 0 }
            val expected = model.values
                .filter { it.id !in exclude && it.isRunnable() }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
                .take(k)
                .map { it.id }
            val claimed = repo.claimNextQueued(k, exclude)
            assertTrue("I5: at most $k claimed", claimed.size <= k)
            assertEquals("I5: claimed rows", expected, claimed.map { it.id })
            claimed.forEach { assertEquals("I5: claimed row state", TRANSFERRING, it.state) }
            expected.forEach { id -> model.getValue(id).apply { state = TRANSFERRING; errorCode = null; nextRetryAt = null } }
            return "claim($k, exclude=$exclude) = $expected"
        }

        private fun ModelTransfer.isRunnable() =
            state == QUEUED || (state == RETRYING && nextRetryAt != null && nextRetryAt!! <= now)

        private fun randomIndex(t: ModelTransfer) = rnd.nextInt(-1, t.chunks.size + 1)

        private suspend fun markDone(t: ModelTransfer): String {
            val i = randomIndex(t)
            val ok = repo.markChunkDone(t.id, i, null)
            val exists = i in t.chunks.indices
            assertEquals("I4: markChunkDone in ${t.state}", t.state == TRANSFERRING && exists, ok)
            if (ok) t.chunks[i].status = ChunkStatus.DONE
            return "markChunkDone(${t.id}, $i) = $ok"
        }

        private suspend fun markFailed(t: ModelTransfer): String {
            val i = randomIndex(t)
            val ok = repo.markChunkFailed(t.id, i, "boom")
            assertEquals("markChunkFailed in ${t.state}", t.state == TRANSFERRING && i in t.chunks.indices, ok)
            if (ok) t.chunks[i].status = ChunkStatus.FAILED
            return "markChunkFailed(${t.id}, $i) = $ok"
        }

        private fun ModelTransfer.isSyncable() = state == TRANSFERRING || state == VERIFYING

        private suspend fun applyServer(t: ModelTransfer): String {
            val indices = (-1..t.chunks.size).filter { rnd.nextBoolean() }
            val ok = repo.applyServerReceivedChunks(t.id, indices)
            assertEquals("applyServerReceivedChunks in ${t.state}", t.isSyncable(), ok)
            if (ok) {
                t.chunks.forEachIndexed { i, c -> c.status = if (i in indices) ChunkStatus.DONE else ChunkStatus.PENDING }
            }
            return "applyServerReceivedChunks(${t.id}, $indices) = $ok"
        }

        private suspend fun reset(t: ModelTransfer): String {
            val indices = if (rnd.nextInt(3) == 0) null else (0 until t.chunks.size).filter { rnd.nextBoolean() }
            val ok = repo.resetChunks(t.id, indices)
            assertEquals("resetChunks in ${t.state}", t.isSyncable(), ok)
            if (ok) {
                t.chunks.forEachIndexed { i, c -> if (indices == null || i in indices) c.status = ChunkStatus.PENDING }
            }
            return "resetChunks(${t.id}, $indices) = $ok"
        }

        private suspend fun reconcile(): String {
            val before = model.keys.associateWith { repo.getTransfer(it) }
            val expected = model.values.filter { it.isSyncable() }.map { it.id }
            val moved = repo.reconcileAfterProcessStart()
            assertEquals("I6: reconciled ids", expected.toSet(), moved.toSet())
            expected.forEach { model.getValue(it).apply { state = QUEUED; nextRetryAt = null } }
            assertTrue("I6: nothing left TRANSFERRING/VERIFYING", repo.getInStates(TRANSFERRING, VERIFYING).isEmpty())
            model.keys.filter { it !in expected }.forEach {
                assertEquals("I6: $it unchanged by reconcile", before[it], repo.getTransfer(it))
            }
            return "reconcile = $moved"
        }

        private suspend fun promoteDue(): String {
            val expected = model.values.filter { it.state == RETRYING && it.nextRetryAt != null && it.nextRetryAt!! <= now }
            assertEquals("promoteDueRetries count", expected.size, repo.promoteDueRetries())
            expected.forEach { it.state = QUEUED; it.nextRetryAt = null }
            return "promoteDueRetries = ${expected.map { it.id }}"
        }

        private suspend fun promoteWaiting(): String {
            val waitCodes = setOf(ErrorCode.NETWORK_UNAVAILABLE, ErrorCode.METERED_NETWORK)
            val expected = model.values.filter { it.state == RETRYING && (it.nextRetryAt == null || it.errorCode in waitCodes) }
            assertEquals("promoteWaitingForNetwork count", expected.size, repo.promoteWaitingForNetwork())
            expected.forEach { it.state = QUEUED; it.nextRetryAt = null }
            return "promoteWaitingForNetwork = ${expected.map { it.id }}"
        }

        private suspend fun delete(t: ModelTransfer): String {
            val ok = repo.deleteTransfer(t.id)
            val terminal = t.state == COMPLETED || t.state == CANCELLED
            assertEquals("deleteTransfer in ${t.state}", terminal, ok)
            if (ok) {
                model.remove(t.id)
                terminalSnapshots.remove(t.id)
                assertEquals("I7: chunks deleted with the transfer", 0, repo.getChunks(t.id).size)
                assertEquals("I7: events deleted with the transfer", 0, repo.getEvents(t.id).size)
            }
            return "deleteTransfer(${t.id}) = $ok"
        }

        /** Database == model, after every operation. */
        private suspend fun check() {
            val rows = repo.getInStates(*TransferState.entries.toTypedArray()).associateBy { it.id }
            assertEquals("transfer ids", model.keys, rows.keys)
            for (t in model.values) {
                val row = rows.getValue(t.id)
                assertEquals("${t.id} state", t.state, row.state)
                assertEquals("${t.id} errorCode", t.errorCode, row.errorCode)
                assertEquals("${t.id} nextRetryAt", t.nextRetryAt, row.nextRetryAt)
                val chunks = repo.getChunks(t.id)
                assertEquals("${t.id} chunk statuses", t.chunks.map { it.status }, chunks.map { it.status })
                assertEquals("I3: ${t.id} bytesDone", chunks.filter { it.status == ChunkStatus.DONE }.sumOf { it.length.toLong() }, row.bytesDone)
                assertEquals("I3: ${t.id} bytesDone (model)", t.chunks.filter { it.status == ChunkStatus.DONE }.sumOf { it.length.toLong() }, row.bytesDone)

                if (row.state == COMPLETED || row.state == CANCELLED) {
                    val snapshot = terminalSnapshots.getOrPut(t.id) { row }
                    assertEquals("I2: ${t.id} changed after ${snapshot.state}", snapshot, row)
                }
            }
            val sql = db.openHelper.readableDatabase
            sql.query("SELECT COUNT(*) FROM chunks WHERE transferId NOT IN (SELECT id FROM transfers)").use {
                it.moveToFirst(); assertEquals("I7: orphan chunks", 0, it.getInt(0))
            }
            sql.query("SELECT COUNT(*) FROM transfer_events WHERE transferId NOT IN (SELECT id FROM transfers)").use {
                it.moveToFirst(); assertEquals("I7: orphan events", 0, it.getInt(0))
            }
        }

        /** I1/I2 over the whole history: every logged state change is a legal edge, chained. */
        private suspend fun checkEventLog() {
            for (id in model.keys) {
                val changes = repo.getEvents(id).filter { it.type == EventType.STATE_CHANGE }
                assertEquals("$id first event is the creation", null, changes.first().fromState)
                changes.zipWithNext().forEach { (a, b) ->
                    assertEquals("$id: changes chain", a.toState, b.fromState)
                    assertTrue("I1: $id ${b.fromState} → ${b.toState} is legal", b.toState in LEGAL_TRANSITIONS.getValue(b.fromState!!))
                }
                assertEquals("$id last change = current state", model.getValue(id).state, changes.last().toState)
            }
        }
    }

    private companion object {
        const val OPS = 100

    }
}
