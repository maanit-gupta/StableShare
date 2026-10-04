package com.maanit.stableshare.engine

import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.TransferState

/**
 * Starts the coordinator if it is not already going to pick up new work by itself.
 * [userInitiated] is true only for a user action (start an upload or download, Resume, Retry);
 * on Android 14+ such a call may host the run in a user-initiated data transfer job.
 */
fun interface TransferScheduler {
    fun ensureRunning(userInitiated: Boolean)
}

/** A start not caused by a user action: app start, connectivity, settings, wake-ups, stops. */
fun TransferScheduler.ensureRunning() = ensureRunning(userInitiated = false)

/**
 * When the exiting coordinator should be started again: after [delayMs] (the earliest persisted
 * backoff), and only with a network when [requiresNetwork] — an unmetered one when [unmetered]
 * (Wi-Fi only is on).
 */
data class WakeupPlan(val delayMs: Long, val requiresNetwork: Boolean, val unmetered: Boolean = false) {
    companion object {
        /**
         * Network-only wake-ups wait at least this long, so a disagreement between the connectivity
         * monitor and a failing request cannot spin faster than once per floor.
         */
        const val NETWORK_FLOOR_MS = 5_000L

        /** After a system stop: WorkManager re-runs stopped work itself; this is a backstop. */
        const val AFTER_STOP_MS = 15_000L

        /**
         * Pure: what the coordinator must be woken for, given the rows left behind. RETRYING with a
         * nextRetryAt waits for that time; RETRYING without one waits for the network. While the
         * network is not [usable], QUEUED rows and due retries wait for the network too (the
         * coordinator claims nothing then). Any run needs the network, so the constraint is always
         * set when something waits; [wifiOnly] makes it an unmetered one.
         */
        fun compute(rows: List<TransferEntity>, now: Long, usable: Boolean = true, wifiOnly: Boolean = false): WakeupPlan? {
            val retrying = rows.filter { it.state == TransferState.RETRYING }
            val earliest = retrying.mapNotNull { it.nextRetryAt }.filter { usable || it > now }.minOrNull()
            val gated = !usable && rows.any {
                it.state == TransferState.QUEUED ||
                    (it.state == TransferState.RETRYING && it.nextRetryAt != null && it.nextRetryAt <= now)
            }
            val waitsForNetwork = gated || retrying.any { it.nextRetryAt == null }
            if (earliest == null && !waitsForNetwork) return null
            val timed = earliest?.let { (it - now).coerceAtLeast(0) }
            val delay = when {
                waitsForNetwork -> minOf(timed ?: NETWORK_FLOOR_MS, NETWORK_FLOOR_MS)
                else -> requireNotNull(timed)
            }
            return WakeupPlan(delay, requiresNetwork = true, unmetered = wifiOnly)
        }
    }
}

/** Arranges for [TransferScheduler.ensureRunning] to be called later, or cancels that (null). */
fun interface WakeupScheduler {
    fun schedule(plan: WakeupPlan?)
}
