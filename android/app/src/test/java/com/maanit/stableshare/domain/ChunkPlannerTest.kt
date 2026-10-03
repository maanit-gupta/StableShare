package com.maanit.stableshare.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

    private val mib = 1024 * 1024

    @Test
    fun zeroBytesGivesNoChunks() {
        assertEquals(emptyList<ChunkSpec>(), ChunkPlanner.plan(0, 2 * mib))
        assertEquals(0, ChunkPlanner.totalChunks(0, 2 * mib))
    }

    @Test
    fun oneByte() {
        assertEquals(listOf(ChunkSpec(0, 0, 1)), ChunkPlanner.plan(1, 2 * mib))
    }

    @Test
    fun exactlyOneChunk() {
        assertEquals(listOf(ChunkSpec(0, 0, 2 * mib)), ChunkPlanner.plan(2L * mib, 2 * mib))
    }

    @Test
    fun exactMultiple() {
        val plan = ChunkPlanner.plan(6L * mib, 2 * mib)
        assertEquals(
            listOf(ChunkSpec(0, 0, 2 * mib), ChunkSpec(1, 2L * mib, 2 * mib), ChunkSpec(2, 4L * mib, 2 * mib)),
            plan,
        )
    }

    @Test
    fun multiplePlusOneByte() {
        val plan = ChunkPlanner.plan(6L * mib + 1, 2 * mib)
        assertEquals(4, plan.size)
        assertEquals(ChunkSpec(3, 6L * mib, 1), plan.last())
    }

    @Test
    fun matchesServerSampleOdd() {
        // server seed "sample-odd": 3 MiB + 123 B at 1 MiB chunks
        val plan = ChunkPlanner.plan(3145851, 1 * mib)
        assertEquals(4, plan.size)
        assertEquals(ChunkSpec(3, 3145728, 123), plan.last())
    }

    @Test
    fun oneGibAtTwoMib() {
        val size = 1024L * mib
        val plan = ChunkPlanner.plan(size, 2 * mib)
        assertEquals(512, plan.size)
        assertEquals(ChunkSpec(511, size - 2 * mib, 2 * mib), plan.last())
        assertEquals(size, plan.sumOf { it.length.toLong() })
        plan.zipWithNext().forEach { (a, b) -> assertEquals(a.offset + a.length, b.offset) }
        assertTrue(plan.withIndex().all { (i, c) -> c.index == i })
    }

    @Test
    fun offsetsBeyondIntRangeUseLongArithmetic() {
        // 5 GiB at 1 MiB would overflow Int offsets; the planner must still be exact.
        val size = 5L * 1024 * mib + 7
        val plan = ChunkPlanner.plan(size, mib)
        assertEquals(5 * 1024 + 1, plan.size)
        assertEquals(5L * 1024 * mib, plan.last().offset)
        assertEquals(7, plan.last().length)
    }

    @Test
    fun invalidSizesRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChunkPlanner.plan(10, 0) }
        assertThrows(IllegalArgumentException::class.java) { ChunkPlanner.plan(10, -1) }
        assertThrows(IllegalArgumentException::class.java) { ChunkPlanner.plan(-1, mib) }
    }
}
