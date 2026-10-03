package com.maanit.stableshare.data.net

import com.maanit.stableshare.domain.ErrorCode

/**
 * Why transfers may not use the network right now, or null when they may: NETWORK_UNAVAILABLE
 * when offline, METERED_NETWORK when Wi-Fi only is on and the network is metered. Used to tell
 * WAITING from RETRYABLE (DESIGN.md §7).
 */
fun interface NetworkBlocker {
    fun blockReason(): ErrorCode?
}
