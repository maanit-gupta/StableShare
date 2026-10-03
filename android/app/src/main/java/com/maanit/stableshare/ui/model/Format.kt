package com.maanit.stableshare.ui.model

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Number, size and time formatting (UI-SPEC §9). Pure, so it is unit-tested directly; the locale
 * and time zone are parameters so tests are deterministic.
 */
object Format {
    private val UNITS = listOf("B", "KB", "MB", "GB")
    private const val STEP = 1024.0

    /** "512 B", "2.4 MB", "200 MB": binary units, one decimal below 10, none at 10 or above. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val unit = unitFor(bytes)
        return "${number(bytes, unit, locale)} ${UNITS[unit]}"
    }

    /** "84 of 200 MB": both numbers in the unit chosen by [total]; the unit appears once. */
    fun sizeProgress(done: Long, total: Long, locale: Locale = Locale.getDefault()): Pair<String, String> {
        val unit = unitFor(total)
        return number(done, unit, locale) to "${number(total, unit, locale)} ${UNITS[unit]}"
    }

    /** "4.1 MB/s"; null when the speed is unknown or 0 (the caller hides it). */
    fun speed(bytesPerSecond: Double?, locale: Locale = Locale.getDefault()): String? {
        if (bytesPerSecond == null || bytesPerSecond <= 0.0 || bytesPerSecond.isNaN()) return null
        return size(bytesPerSecond.roundToLong().coerceAtLeast(1), locale) + "/s"
    }

    /** "28 s", "3 min 5 s", "12 min", "1 h 4 min". */
    fun eta(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3_600 -> {
                val m = s / 60
                if (m > 10) "$m min" else "$m min ${s % 60} s"
            }
            else -> "${s / 3_600} h ${(s % 3_600) / 60} min"
        }
    }

    /** Durations use the ETA style; milliseconds are rounded up to whole seconds. */
    fun duration(millis: Long): String = eta(ceil(millis.coerceAtLeast(0) / 1_000.0).toLong())

    /** "209,715,200" with the locale's grouping. */
    fun exactBytes(bytes: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(bytes)

    /** "HH:mm:ss" for today, "MMM d, HH:mm" for another day. */
    fun activityTime(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val pattern = if (at.toLocalDate() == today) "HH:mm:ss" else "MMM d, HH:mm"
        return DateTimeFormatter.ofPattern(pattern, locale).format(at)
    }

    /** "MMM d, HH:mm" (History finish time, Details timestamps). */
    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, HH:mm", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private fun unitFor(bytes: Long): Int {
        var unit = 0
        var value = bytes.toDouble()
        while (unit < UNITS.lastIndex && rounded(value) >= STEP) {
            value /= STEP
            unit++
        }
        return unit
    }

    /** Rounds the way [number] displays, so 1023.96 KB counts as 1024 KB and moves up a unit. */
    private fun rounded(value: Double): Double =
        if (value < 10) Math.round(value * 10) / 10.0 else Math.round(value).toDouble()

    private fun number(bytes: Long, unit: Int, locale: Locale): String {
        if (unit == 0) return bytes.toString()
        val value = bytes / Math.pow(STEP, unit.toDouble())
        val r = rounded(value)
        return if (r < 10) String.format(locale, "%.1f", r) else String.format(locale, "%d", r.toLong())
    }
}
