package com.maanit.stableshare.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class FormatTest {
    private val us = Locale.US
    private val kb = 1024L
    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun sizesUseBinaryUnitsWithOneDecimalBelowTen() {
        assertEquals("0 B", Format.size(0, us))
        assertEquals("512 B", Format.size(512, us))
        assertEquals("1023 B", Format.size(1023, us))
        assertEquals("1.0 KB", Format.size(kb, us))
        assertEquals("2.4 MB", Format.size((2.4 * mb).toLong(), us))
        assertEquals("9.9 MB", Format.size((9.94 * mb).toLong(), us))
        assertEquals("10 MB", Format.size((9.96 * mb).toLong(), us))
        assertEquals("200 MB", Format.size(200 * mb, us))
        assertEquals("1.0 GB", Format.size(gb, us))
        assertEquals("1.0 GB", Format.size(1023 * mb + 1000 * kb, us)) // rounds to 1024 MB → next unit
    }

    @Test
    fun progressPairUsesTheTotalsUnitOnce() {
        assertEquals("84" to "200 MB", Format.sizeProgress(84 * mb, 200 * mb, us))
        assertEquals("2.4" to "200 MB", Format.sizeProgress((2.4 * mb).toLong(), 200 * mb, us))
        assertEquals("0.5" to "1.0 GB", Format.sizeProgress(gb / 2, gb, us))
        assertEquals("0" to "500 B", Format.sizeProgress(0, 500, us))
    }

    @Test
    fun speedAppendsPerSecondAndHidesUnknownOrZero() {
        assertEquals("4.1 MB/s", Format.speed(4.1 * mb, us))
        assertEquals("12 MB/s", Format.speed(12.3 * mb, us))
        assertNull(Format.speed(null, us))
        assertNull(Format.speed(0.0, us))
        assertNull(Format.speed(Double.NaN, us))
    }

    @Test
    fun etaBoundaries() {
        assertEquals("0 s", Format.eta(0))
        assertEquals("28 s", Format.eta(28))
        assertEquals("59 s", Format.eta(59))
        assertEquals("1 min 0 s", Format.eta(60))
        assertEquals("3 min 5 s", Format.eta(185))
        assertEquals("10 min 59 s", Format.eta(659))
        assertEquals("11 min", Format.eta(660))
        assertEquals("59 min", Format.eta(3_599))
        assertEquals("1 h 0 min", Format.eta(3_600))
        assertEquals("1 h 4 min", Format.eta(3_840))
        assertEquals("0 s", Format.eta(-5))
    }

    @Test
    fun durationsUseTheEtaStyleRoundedUp() {
        assertEquals("0 s", Format.duration(0))
        assertEquals("1 s", Format.duration(1))
        assertEquals("49 s", Format.duration(48_200))
        assertEquals("2 min 3 s", Format.duration(123_000))
        assertEquals("1 h 30 min", Format.duration(5_400_000))
    }

    @Test
    fun exactBytesAreGrouped() {
        assertEquals("209,715,200", Format.exactBytes(209_715_200, us))
        assertEquals("0", Format.exactBytes(0, us))
    }

    @Test
    fun activityTimeIsClockTimeTodayAndDateOtherwise() {
        val zone = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 10, 3, 18, 0).toInstant(zone).toEpochMilli()
        val earlierToday = LocalDateTime.of(2026, 10, 3, 9, 5, 7).toInstant(zone).toEpochMilli()
        val yesterday = LocalDateTime.of(2026, 10, 2, 23, 59, 30).toInstant(zone).toEpochMilli()
        assertEquals("09:05:07", Format.activityTime(earlierToday, now, zone, us))
        assertEquals("Oct 2, 23:59", Format.activityTime(yesterday, now, zone, us))
        assertEquals("Oct 3, 09:05", Format.dateTime(earlierToday, zone, us))
    }
}
