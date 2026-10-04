package com.maanit.stableshare.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide single-flight lease for the coordinator loop (DESIGN.md §9). The concurrency limit
 * is enforced per loop, so two hosts (the WorkManager worker, later a UIDT job) must never run a
 * loop each. A host that cannot take the lease returns at once, but first raises [rerun]: the
 * holder checks it after releasing and runs the loop again. Without the hand-off, a host started
 * while the holder is exiting (it has done its last emptiness check but not yet released) would
 * leave a new QUEUED row with nobody to run it.
 *
 * A holder that ends by cancellation (a system stop) drops a pending rerun; its requeue schedules
 * the after-stop wake-up, which starts a new run.
 */
class RunLease {
    private val held = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)

    /** True while some host is running the loop. */
    fun isHeld(): Boolean = held.get()

    /**
     * Runs [block] under the lease, again for as long as another host asked for a rerun meanwhile.
     * Returns false without running it when another host holds the lease (that host reruns).
     */
    suspend fun runOrHandOff(block: suspend () -> Unit): Boolean {
        rerun.set(true)
        var ran = false
        // Raise-then-try on the losing side and release-then-check on the holder's side: whichever
        // order they interleave in, one of them sees the other and the request is served.
        while (rerun.get() && held.compareAndSet(false, true)) {
            ran = true
            try {
                rerun.set(false)
                block()
            } finally {
                held.set(false)
            }
        }
        return ran
    }
}
