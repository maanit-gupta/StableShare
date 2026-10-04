package com.maanit.stableshare.fuzz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.db.TransferEventEntity
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.settings.Settings
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
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.engine.EngineHarness
import com.maanit.stableshare.engine.FakeConnectivity
import com.maanit.stableshare.engine.FakeTransferServer
import com.maanit.stableshare.engine.FakeTransferServer.Fault
import com.maanit.stableshare.engine.FakeTransferServer.Op
import com.maanit.stableshare.engine.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Layer B: the real engine, pipelines, controller and repository against [FakeTransferServer]
 * with seeded faults, under virtual time. Room and file I/O run on the test dispatcher, so a seed
 * replays the same interleaving. Each scenario: 1–5 transfers (uploads and downloads, 0 bytes and
 * non-multiples of the chunk size), maxConcurrent 1–3, parallelChunks 1, 2 or 4, then random pauses, resumes, cancels,
 * manual retries, network flips and process deaths; run to quiescence, then (faults off, network
 * back) resume everything paused or failed and run to quiescence again.
 *
 * I8 COMPLETED ⇒ VERIFIED event and delivered bytes == source. I9 CANCELLED ⇒ nothing after the
 * cancel but cleanup, and cleanup ran. I10 the server stores only chunks equal to the source.
 * I11 never more than maxConcurrent pipelines, or TRANSFERRING+VERIFYING rows, at any state change,
 * and never more than maxConcurrent × parallelChunks chunk requests in flight.
 * I12 nothing leaves COMPLETED/CANCELLED; every change is a legal edge. I13 CHUNK_DONE only while
 * TRANSFERRING. Liveness: with faults off and the network back, every non-cancelled transfer completes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineFuzzTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun randomScenariosKeepTheInvariants() {
        FuzzSeeds.run("engine", "*EngineFuzzTest") { seed -> Scenario(seed, tmp.newFolder("seed-$seed")).run() }
        println("EngineFuzzTest: $stats")
    }

    /** What the scenarios exercised, printed once so a green run shows it did real work. */
    private data class Stats(
        var seeds: Int = 0, var transfers: Int = 0, var completed: Int = 0, var cancelled: Int = 0,
        var faults: Int = 0, var deaths: Int = 0, var actionsApplied: Int = 0, var completedAfterDeath: Int = 0,
        var instantCompleted: Int = 0,
    )

    private val stats = Stats()

    /** Thrown by a killed process's clock: every repository write needs the clock, so none lands. */
    private class ProcessDied : CancellationException("process died")

    private class Source(val type: TransferType, val bytes: ByteArray)

    private inner class Scenario(val seed: Long, val dir: File) {
        private val rnd = Random(seed)
        private val faultRnd = Random(seed * 7_919 + 1)
        /** Picks identical re-uploads; separate from [rnd] so adding it did not reshuffle older seeds. */
        private val reuseRnd = Random(seed * 104_729 + 3)
        /** (size, content seed) of every upload so far, for identical re-uploads (instant upload). */
        private val uploadContents = mutableListOf<Pair<Int, Int>>()
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val server = FakeTransferServer()
        private val net = FakeConnectivity()
        private val maxConcurrent = rnd.nextInt(1, 4)
        /** Chunks in flight per transfer; its own generator, so adding it did not reshuffle older seeds' other choices. */
        private val parallelChunks = listOf(1, 2, 4).random(Random(seed * 15_485_863 + 5))
        private val faultRate = listOf(0.0, 0.1, 0.2, 0.35).random(rnd)
        private var faultsOn = true
        private val sources = linkedMapOf<String, Source>()
        private val errors = mutableListOf<Throwable>()
        private val log = mutableListOf<String>()
        private var maxActiveSeen = 0
        private var nextSeed = 0
        private var deaths = 0

        @OptIn(ExperimentalCoroutinesApi::class)
        fun run() = runTest(timeout = 60.seconds) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .setQueryCoroutineContext(dispatcher)
                .allowMainThreadQueries()
                .build()
            server.fault = ::fault
            // Latency per request, so user actions and network flips land mid-transfer. Times are
            // whole ticks, so requests, actions and wake-ups often coincide and interleave both ways.
            val maxTicks = listOf(1L, 5L, 15L).random(rnd)
            server.onRequest = { delay(faultRnd.nextLong(0, maxTicks + 1) * TICK) }
            var settings = Settings(maxConcurrent = maxConcurrent, uploadChunkSizeBytes = EngineHarness.CHUNK, parallelChunks = parallelChunks)
            var process = Process(this, dispatcher, db, settings)
            try {
                log += "maxConcurrent=$maxConcurrent parallelChunks=$parallelChunks faultRate=$faultRate"
                repeat(rnd.nextInt(1, 6)) { create(process) }
                repeat(rnd.nextInt(10, 40)) { step ->
                    delay(rnd.nextLong(0, 16) * TICK)
                    when (rnd.nextInt(100)) {
                        in 0..14 -> userAction(process, "pause") { process.h.controller.pause(it) }
                        in 15..29 -> userAction(process, "resume") { process.h.controller.resume(it) }
                        in 30..39 -> userAction(process, "cancel") { process.h.controller.cancel(it) }
                        in 40..49 -> userAction(process, "retry") { process.h.controller.retry(it) }
                        in 50..64 -> {
                            net.state = NetworkState.entries.random(rnd)
                            log += "#$step network ${net.state}"
                        }
                        in 65..69 -> {
                            val on = rnd.nextBoolean()
                            process.h.setWifiOnly(on)
                            settings = process.h.settings.value
                            log += "#$step wifiOnly=$on"
                        }
                        in 70..79 -> {
                            log += "#$step process death"
                            deaths++
                            settings = process.h.settings.value
                            process.kill()
                            process = Process(this, dispatcher, db, settings)
                        }
                        in 80..84 -> create(process)
                        else -> log += "#$step wait"
                    }
                    process.ensureRunning()
                }
                settle(process, "phase 1")

                log += "phase 2: faults off, network back, resume all"
                faultsOn = false
                net.state = NetworkState.Unmetered
                for (id in sources.keys) {
                    when (process.h.state(id)) {
                        PAUSED -> process.h.controller.resume(id)
                        FAILED -> process.h.controller.retry(id)
                        else -> Unit
                    }
                }
                settle(process, "phase 2")
                checkInvariants(process)
                for (id in sources.keys) {
                    val row = process.h.row(id)
                    if (row.state != CANCELLED) {
                        assertEquals("liveness: $id (${row.errorCode}: ${row.errorMessage})", COMPLETED, row.state)
                    }
                }
                assertTrue("uncaught: $errors", errors.isEmpty())
                val final = sources.keys.map { process.h.state(it) }
                stats.seeds++
                stats.transfers += final.size
                stats.completed += final.count { it == COMPLETED }
                stats.cancelled += final.count { it == CANCELLED }
                stats.instantCompleted += sources.keys.count { id ->
                    process.h.state(id) == COMPLETED && process.h.eventTypes(id).contains(EventType.INSTANT_UPLOAD)
                }
                stats.deaths += deaths
                if (deaths > 0) stats.completedAfterDeath += final.count { it == COMPLETED }
            } catch (t: Throwable) {
                throw AssertionError("${t.message}\nScenario log:\n${log.takeLast(40).joinToString("\n")}", t)
            } finally {
                process.scope.coroutineContext.job.cancelAndJoin()
                db.close()
            }
        }

        /** One app process: harness (engine, pipelines, controller, repository) on the shared world. */
        private inner class Process(test: TestScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher, db: AppDatabase, settings: Settings) {
            @Volatile private var dead = false
            private val clockOf: () -> Long = { test.testScheduler.currentTime }
            val h = EngineHarness(
                context, dir, clock = { if (dead) throw ProcessDied() else clockOf() },
                server = server, net = net, db = db, settings = settings, io = dispatcher,
            )
            val scope = CoroutineScope(
                dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, t -> if (!dead) errors += t },
            )
            private var run: Job? = null

            init {
                scope.launch { h.engine.activeIds.collect { maxActiveSeen = maxOf(maxActiveSeen, it.size) } }
            }

            fun ensureRunning() {
                if (run?.isActive != true) run = scope.launch { h.engine.run() }
            }

            val idle: Boolean get() = run?.isActive != true

            /** No cleanup reaches the database: the clock throws before any write commits. */
            suspend fun kill() {
                dead = true
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

        private suspend fun create(p: Process) {
            val size = when (rnd.nextInt(6)) {
                0 -> 0
                1 -> EngineHarness.CHUNK * rnd.nextInt(1, 6)
                2 -> 1
                else -> rnd.nextInt(1, 12 * EngineHarness.CHUNK + 500)
            }
            val s = nextSeed++
            faultsOn = false // the user-started manifest fetch is not the engine's to retry
            val (t, bytes) = try {
                if (rnd.nextBoolean()) {
                    // Now and then the same content again: instant when the earlier one is COMPLETED.
                    val (upSize, upSeed) = if (uploadContents.isNotEmpty() && reuseRnd.nextInt(3) == 0) {
                        uploadContents.random(reuseRnd)
                    } else {
                        size to s
                    }
                    uploadContents += upSize to upSeed
                    p.h.upload(upSize, seed = upSeed, fileName = "src-$s-copy-of-$upSeed-$upSize.bin")
                } else {
                    p.h.download(size, seed = s, fileId = "file-$s")
                }
            } finally {
                faultsOn = true
            }
            sources[t.id] = Source(t.type, bytes)
            // Ids are random UUIDs; distinct createdAt keeps claim order (createdAt, id) seed-determined.
            delay(1)
            log += "create ${t.type} ${t.id.take(8)} size=${bytes.size}"
            p.ensureRunning()
        }

        private suspend fun userAction(p: Process, name: String, action: suspend (String) -> Boolean) {
            val id = sources.keys.toList().randomOrNull(rnd) ?: return
            val ok = action(id)
            if (ok) stats.actionsApplied++
            log += "$name ${id.take(8)} = $ok"
        }

        private fun fault(call: FakeTransferServer.Call): Fault? {
            if (!faultsOn || call.op in setOf(Op.DELETE, Op.LIST, Op.MANIFEST)) return null
            if (faultRnd.nextDouble() >= faultRate) return null
            stats.faults++
            return when (faultRnd.nextInt(7)) {
                0 -> Fault.Before(SocketTimeoutException("timeout"))
                1 -> Fault.Before(IOException("Connection reset"))
                2 -> Fault.Before(FakeTransferServer.http(503, "UNAVAILABLE"))
                3 -> Fault.Before(FakeTransferServer.http(429, "TOO_MANY_REQUESTS"))
                4 -> Fault.After(SocketTimeoutException("timeout after processing"))
                5 -> Fault.After(IOException("unexpected end of stream"))
                else -> if (call.op == Op.CHUNK || call.op == Op.RANGE) Fault.Corrupt else Fault.Before(IOException("Broken pipe"))
            }
        }

        /** Runnable but held back: the engine claims nothing while the network is unusable. */
        private fun waiting(state: TransferState) =
            (state == QUEUED || state == RETRYING) && !net.usableNetwork.value

        /** Runs until every row is terminal, paused, failed or waiting for the network. */
        private suspend fun settle(p: Process, phase: String) {
            repeat(SETTLE_ROUNDS) {
                p.ensureRunning()
                delay(5_000)
                val rows = sources.keys.map { p.h.row(it) }
                val quiet = rows.all { it.state in setOf(COMPLETED, CANCELLED, PAUSED, FAILED) || waiting(it.state) }
                if (quiet && p.idle) return
            }
            val rows = sources.keys.map { p.h.row(it) }.joinToString { "${it.id.take(8)}=${it.state}/${it.errorCode}/${it.nextRetryAt}" }
            fail("$phase: not quiescent after ${SETTLE_ROUNDS * 5} s: $rows")
        }

        private suspend fun checkInvariants(p: Process) {
            val h = p.h
            val allEvents = mutableListOf<TransferEventEntity>()
            for ((id, source) in sources) {
                val row = h.row(id)
                val events = h.events(id)
                allEvents += events
                checkHistory(id, events)

                if (row.state == COMPLETED) {
                    assertTrue("I8: $id has a VERIFIED event", events.any { it.type == EventType.VERIFIED })
                    val delivered = if (source.type == TransferType.UPLOAD) server.assembled(row.remoteId!!) else h.localFile(id).readBytes()
                    assertArrayEquals("I8: $id delivered bytes", source.bytes, delivered)
                    assertEquals("I8: $id sha256", FileStore.sha256Hex(source.bytes), FileStore.sha256Hex(delivered))
                }
                if (row.state == CANCELLED) {
                    val at = events.indexOfFirst { it.type == EventType.STATE_CHANGE && it.toState == CANCELLED }
                    val after = events.drop(at + 1)
                    assertTrue(
                        "I9: $id events after cancel: ${after.map { it.message }}",
                        after.all { it.type == EventType.INFO && it.message.startsWith("Cleanup:") },
                    )
                    assertTrue("I9: $id cleanup logged", after.any { it.message.startsWith("Cleanup:") })
                    if (source.type == TransferType.UPLOAD) {
                        assertFalse("I9: $id server session deleted", server.sessions.containsKey(row.remoteId))
                    } else {
                        assertFalse("I9: $id local file deleted", h.localFile(id).exists())
                    }
                }
                if (source.type == TransferType.UPLOAD) {
                    server.sessions[row.remoteId]?.chunks?.forEach { (index, stored) ->
                        val from = index * row.chunkSize
                        val expected = source.bytes.copyOfRange(from, minOf(from + row.chunkSize, source.bytes.size))
                        assertArrayEquals("I10: $id chunk $index stored on the server", expected, stored)
                    }
                }
            }

            // I11: replay every state change in commit order.
            assertTrue("I11: $maxActiveSeen pipelines at once > $maxConcurrent", maxActiveSeen <= maxConcurrent)
            val peakChunks = server.peakChunkRequests.get()
            assertTrue("I11: $peakChunks chunk requests at once > $maxConcurrent × $parallelChunks", peakChunks <= maxConcurrent * parallelChunks)
            val states = mutableMapOf<String, TransferState>()
            for (e in allEvents.sortedBy { it.id }) {
                if (e.type != EventType.STATE_CHANGE) continue
                states[e.transferId] = e.toState!!
                val busy = states.values.count { it == TRANSFERRING || it == VERIFYING }
                assertTrue("I11: $busy TRANSFERRING/VERIFYING > $maxConcurrent at event ${e.id} (${e.message})", busy <= maxConcurrent)
            }
        }

        /** I12 and I13 over one transfer's event log. */
        private fun checkHistory(id: String, events: List<TransferEventEntity>) {
            var state: TransferState? = null
            for (e in events) {
                when (e.type) {
                    EventType.STATE_CHANGE -> {
                        assertEquals("$id: change chains from the current state", state, e.fromState)
                        if (state != null) {
                            assertTrue("I12: $id $state → ${e.toState} is legal", e.toState in LEGAL_TRANSITIONS.getValue(state))
                        }
                        state = e.toState
                    }
                    EventType.CHUNK_DONE -> assertEquals("I13: $id chunk ${e.chunkIndex} DONE while $state", TRANSFERRING, state)
                    else -> Unit
                }
            }
        }
    }

    private companion object {
        const val SETTLE_ROUNDS = 120
        const val TICK = 100L
    }
}
