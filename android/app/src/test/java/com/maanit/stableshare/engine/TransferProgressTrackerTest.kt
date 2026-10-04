package com.maanit.stableshare.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressTrackerTest {

    private var now = 0L
    private val tracker = TransferProgressTracker(clock = { now })

    @Test
    fun uiBytesAreCommittedPlusInFlight() {
        tracker.start("t", committedBytes = 1_000, totalBytes = 10_000)
        tracker.setInFlight("t", 300)
        assertEquals(1_300, tracker.progress.value.getValue("t").bytes)
        tracker.setCommitted("t", 2_000)
        val p = tracker.progress.value.getValue("t")
        assertEquals(0, p.inFlightBytes)
        assertEquals(2_000, p.bytes)
    }

    @Test
    fun speedIsAnEmaThatConvergesToTheSteadyRate() {
        tracker.start("t", 0, 100_000_000)
        // 1 MB/s for 15 s, sampled every 500 ms.
        for (i in 1..30) {
            now = i * 500L
            tracker.setInFlight("t", i * 500_000L)
        }
        val p = tracker.progress.value.getValue("t")
        assertEquals(1_000_000.0, p.bytesPerSecond, 1_000.0)
        assertEquals((100_000_000 - 15_000_000) / 1_000_000L, p.etaSeconds)
    }

    @Test
    fun emaReactsGraduallyToARateChange() {
        tracker.start("t", 0, 1_000_000_000)
        var bytes = 0L
        for (i in 1..40) { now += 500; bytes += 500_000; tracker.setInFlight("t", bytes) } // 1 MB/s
        for (i in 1..6) { now += 500; bytes += 1_500_000; tracker.setInFlight("t", bytes) } // 3 MB/s for 3 s
        val speed = tracker.progress.value.getValue("t").bytesPerSecond
        // After one time constant (≈ 3 s) the EMA has moved ~63 % of the way from 1 to 3 MB/s.
        assertTrue("speed $speed", speed in 2_100_000.0..2_400_000.0)
    }

    @Test
    fun samplesCloserThanTheMinimumIntervalDoNotUpdateSpeed() {
        tracker.start("t", 0, 1_000_000)
        now = 100
        tracker.setInFlight("t", 100_000)
        assertEquals(0.0, tracker.progress.value.getValue("t").bytesPerSecond, 0.0)
        assertNull(tracker.progress.value.getValue("t").etaSeconds)
    }

    @Test
    fun aRetryThatDropsInFlightBytesNeverYieldsNegativeSpeed() {
        tracker.start("t", 0, 1_000_000)
        now = 1_000; tracker.setInFlight("t", 400_000)
        now = 1_500; tracker.setInFlight("t", 0)
        now = 2_000; tracker.setInFlight("t", 100_000)
        assertTrue(tracker.progress.value.getValue("t").bytesPerSecond >= 0)
    }

    @Test
    fun phasesAndClear() {
        tracker.start("t", 0, 10)
        tracker.setPhase("t", TransferPhase.Retrying(5_000))
        assertEquals(TransferPhase.Retrying(5_000), tracker.progress.value.getValue("t").phase)
        tracker.clear("t")
        assertTrue(tracker.progress.value.isEmpty())
        tracker.setInFlight("t", 5) // late callback after the job ended: ignored
        assertTrue(tracker.progress.value.isEmpty())
    }

    @Test
    fun inFlightChunkIsTrackedUntilCommittedOrThePhaseChanges() {
        tracker.start("t", 0, 10_000)
        assertNull(tracker.progress.value.getValue("t").inFlightChunk)
        tracker.setInFlight("t", 100, chunkIndex = 4)
        assertEquals(4, tracker.progress.value.getValue("t").inFlightChunk)
        tracker.setCommitted("t", 1_000)
        assertNull(tracker.progress.value.getValue("t").inFlightChunk)

        tracker.setInFlight("t", 50, chunkIndex = 5)
        tracker.setPhase("t", TransferPhase.Transferring)
        assertEquals(5, tracker.progress.value.getValue("t").inFlightChunk)
        tracker.setPhase("t", TransferPhase.Retrying(9_000))
        assertNull(tracker.progress.value.getValue("t").inFlightChunk)
    }

    @Test
    fun parallelChunksSumTheirBytesAndCommitOneAtATime() {
        tracker.start("t", 0, 10_000)
        tracker.setChunkInFlight("t", 3, 200)
        tracker.setChunkInFlight("t", 1, 300)
        tracker.setChunkInFlight("t", 3, 400)
        var p = tracker.progress.value.getValue("t")
        assertEquals(700, p.inFlightBytes)
        assertEquals(mapOf(1 to 300L, 3 to 400L), p.inFlightChunks)
        assertEquals("the lowest index stands for the map", 1, p.inFlightChunk)

        tracker.chunkCommitted("t", 1, committedBytes = 1_000)
        p = tracker.progress.value.getValue("t")
        assertEquals(1_000, p.committedBytes)
        assertEquals(400, p.inFlightBytes)
        assertEquals(1_400, p.bytes)
        assertEquals(3, p.inFlightChunk)

        now = 1_000
        tracker.setChunkInFlight("t", 5, 600)
        p = tracker.progress.value.getValue("t")
        assertEquals("speed is over the total of all chunks", 2_000.0, p.bytesPerSecond, 0.001)

        tracker.dropChunkInFlight("t", 3)
        assertEquals(600, tracker.progress.value.getValue("t").inFlightBytes)
        tracker.setPhase("t", TransferPhase.WaitingForNetwork)
        p = tracker.progress.value.getValue("t")
        assertTrue(p.inFlightChunks.isEmpty())
        assertNull(p.inFlightChunk)
    }
}
