package com.maanit.stableshare.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Why the previous process of this app ended, as far as restart reconciliation cares: was it
 * stopped by the user? Task Manager "Stop" (Android 13+) and Settings → Force stop kill the
 * process without calling onStopJob, so the job host never sees a USER stop; the only trace is
 * the exit record, [ApplicationExitInfo.REASON_USER_REQUESTED].
 *
 * Read once per process (the latest record is the previous process; this one has not exited).
 * Below API 30 there are no exit records, so it reports false and reconciliation requeues as before.
 */
class PreviousProcessExit(private val context: Context) {
    val stoppedByUser: Boolean by lazy { read() }

    private fun read(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return false
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            last?.reason == ApplicationExitInfo.REASON_USER_REQUESTED
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the previous exit reason", e)
            false
        }
    }

    private companion object {
        const val TAG = "PreviousProcessExit"
    }
}
