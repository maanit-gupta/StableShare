package com.maanit.stableshare.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RetryPolicyTest {

    @Test
    fun ceilingDoublesThenCaps() {
        val policy = RetryPolicy()
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map(policy::ceilingFor),
        )
        assertEquals(30_000L, policy.ceilingFor(100))
    }

    @Test
    fun delaysStayWithinBounds() {
        val policy = RetryPolicy(random = Random(42))
        for (attempt in 1..10) {
            repeat(500) {
                val d = policy.delayFor(attempt)
                assertTrue("attempt $attempt delay $d", d in 0..policy.ceilingFor(attempt))
            }
        }
    }

    @Test
    fun delaysGrowOnAverage() {
        val policy = RetryPolicy(random = Random(7))
        val means = (1..6).map { attempt -> (1..2_000).map { policy.delayFor(attempt) }.average() }
        means.zipWithNext().take(4).forEach { (a, b) -> assertTrue("$a < $b", a < b) }
        // Full jitter: the mean sits near half the ceiling.
        assertEquals(15_000.0, means.last(), 1_000.0)
    }

    @Test
    fun seededRandomIsDeterministic() {
        val a = RetryPolicy(random = Random(1234))
        val b = RetryPolicy(random = Random(1234))
        val c = RetryPolicy(random = Random(4321))
        val seqA = (1..20).map { a.delayFor(it % 6 + 1) }
        val seqB = (1..20).map { b.delayFor(it % 6 + 1) }
        val seqC = (1..20).map { c.delayFor(it % 6 + 1) }
        assertEquals(seqA, seqB)
        assertNotEquals(seqA, seqC)
    }

    @Test
    fun injectedParametersAreUsed() {
        val policy = RetryPolicy(baseDelayMs = 100, maxDelayMs = 250, maxAttemptsPerChunk = 2, random = Random(0))
        assertEquals(listOf(100L, 200L, 250L), (1..3).map(policy::ceilingFor))
        assertTrue(policy.canRetry(1))
        assertFalse(policy.canRetry(2))
    }

    @Test
    fun maxAttemptsBoundary() {
        val policy = RetryPolicy()
        assertTrue((0..4).all(policy::canRetry))
        assertFalse(policy.canRetry(5))
    }

    @Test
    fun retryAfterIsALowerBoundButCapped() {
        val policy = RetryPolicy(random = Random(3))
        repeat(100) { assertTrue(policy.delayHonouringRetryAfter(1, 5_000) >= 5_000) }
        assertEquals(30_000L, policy.delayHonouringRetryAfter(1, 120_000))
        assertTrue(policy.delayHonouringRetryAfter(1, null) <= 1_000)
    }

    @Test
    fun attemptStartsAtOne() {
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy().delayFor(0) }
    }
}
