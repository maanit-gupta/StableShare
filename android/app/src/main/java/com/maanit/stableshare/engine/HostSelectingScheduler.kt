package com.maanit.stableshare.engine

/**
 * Picks the host for a coordinator run (DESIGN.md §9). A user action on Android 14+ (API 34),
 * while the app is visible and no loop holds the lease, schedules a user-initiated data transfer
 * job ([uidt]); everything else, and any UIDT scheduling failure, goes to [fallback] (the
 * WorkManager coordinator). While a run is going and accepting work, nothing starts at all.
 *
 * [uidt] returns null once the job is scheduled, otherwise why it was not (logged, never written
 * to a transfer's events).
 */
class HostSelectingScheduler(
    private val sdkInt: Int,
    private val appVisible: () -> Boolean,
    private val leaseHeld: () -> Boolean,
    private val engineAcceptingWork: () -> Boolean,
    private val uidt: () -> String?,
    private val fallback: TransferScheduler,
    private val log: (String) -> Unit,
) : TransferScheduler {

    override fun ensureRunning(userInitiated: Boolean) {
        if (engineAcceptingWork()) return
        if (userInitiated && sdkInt >= UIDT_MIN_SDK && appVisible() && !leaseHeld()) {
            val failure = runCatching(uidt).getOrElse { "scheduling threw ${it::class.simpleName}: ${it.message}" }
                ?: return
            log("user-initiated job not scheduled ($failure); using WorkManager")
        }
        fallback.ensureRunning(userInitiated)
    }

    companion object {
        /** Android 14: JobInfo.Builder.setUserInitiated. */
        const val UIDT_MIN_SDK = 34
    }
}
