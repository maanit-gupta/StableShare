package com.maanit.stableshare.ui.model

import com.maanit.stableshare.domain.TransferState

/**
 * The displayed percentage (UI-SPEC §5.8.2): floor(100 × bytes / size), capped at 99 until the
 * transfer is COMPLETED, which alone shows 100. Zero-byte files show 0 until COMPLETED.
 */
fun displayPercent(bytes: Long, size: Long, state: TransferState): Int = when {
    state == TransferState.COMPLETED -> 100
    size <= 0 -> 0
    else -> ((bytes.coerceIn(0, size) * 100) / size).toInt().coerceAtMost(99)
}
