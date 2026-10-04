package com.maanit.stableshare.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PreviousProcessExitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun recordExit(reason: Int) {
        val am = context.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(context.packageName, 1234, reason, 0)
    }

    @Test
    fun taskManagerStopOrForceStopCountsAsStoppedByUser() {
        recordExit(ApplicationExitInfo.REASON_USER_REQUESTED)
        assertTrue(PreviousProcessExit(context).stoppedByUser)
    }

    @Test
    fun otherDeathsDoNot() {
        listOf(
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_OTHER,
        ).forEach { reason ->
            recordExit(reason)
        }
        // Only non-user exits are recorded, so whichever one comes back as the latest is not a user stop.
        assertFalse(PreviousProcessExit(context).stoppedByUser)
    }

    @Test
    fun noRecordMeansNotStopped() {
        assertFalse(PreviousProcessExit(context).stoppedByUser)
    }

    @Test
    @Config(sdk = [29])
    fun beforeApi30ThereAreNoExitRecords() {
        assertFalse(PreviousProcessExit(context).stoppedByUser)
    }

    @Test
    fun theAnswerIsReadOnceAndKept() {
        val exit = PreviousProcessExit(context)
        assertFalse(exit.stoppedByUser)
        recordExit(ApplicationExitInfo.REASON_USER_REQUESTED)
        assertFalse("a later record belongs to no earlier process of this one", exit.stoppedByUser)
    }
}
