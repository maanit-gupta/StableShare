package com.maanit.stableshare

import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.ui.LocalNetworkPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Parallel chunks benchmark (docs/benchmarks.md), driven by `android/scripts/benchmark-parallel.sh`, which
 * sets the server's faults and passes instrumentation arguments:
 * `direction` (upload | download), `n` (1, 2, 4), `runs`, `sizeMb` (upload) and `fileId` (download).
 * Each run is timed from enqueue to COMPLETED (so it includes the upload's checksum pass and the
 * download's final re-hash) and reported as an instrumentation status line `run_<i>_ms`. Skipped
 * unless `direction` is given, so a plain connectedAndroidTest run does not start it.
 */
@RunWith(AndroidJUnit4::class)
class ParallelChunksBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun benchmark() {
        val direction = args.getString("direction")
        assumeTrue("benchmark runs only from android/scripts/benchmark-parallel.sh", direction != null)
        val n = args.getString("n")!!.toInt()
        val runs = args.getString("runs", "3").toInt()
        if (LocalNetworkPermission.isRequired()) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, LocalNetworkPermission.PERMISSION)
        }
        val app = instrumentation.targetContext.applicationContext as StableShareApp
        val c = app.container
        runBlocking {
            c.settingsRepository.setOnboardingCompleted(true)
            c.settingsRepository.setParallelChunks(n)
            c.settingsRepository.setWifiOnly(false)
            c.settingsRepository.setAutoRetryEnabled(true)
            // Start from an idle engine: a transfer left over from an interrupted run would share the link.
            c.transferRepository.getInStates(*ACTIVE_OR_WAITING).forEach { c.transferController.cancel(it.id) }
        }
        // Keep the app in the foreground, as a user watching a transfer would.
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(runs) { i ->
                val ms = runBlocking { if (direction == "upload") upload(c, args.getString("sizeMb")!!.toInt()) else download(c, args.getString("fileId")!!) }
                instrumentation.sendStatus(0, Bundle().apply { putString("run_${i}_ms", ms.toString()) })
            }
        }
    }

    private suspend fun upload(c: com.maanit.stableshare.di.AppContainer, sizeMb: Int): Long {
        // Fresh random bytes every run, so the server never answers with an instant upload.
        val file = c.fileStore.generateTestFile(sizeMb)
        try {
            val start = System.currentTimeMillis()
            val row = c.transferController.uploadUri(Uri.fromFile(file))
            val ms = awaitCompleted(c, row.id) - start
            c.transferRepository.deleteTransfer(row.id)
            return ms
        } finally {
            file.delete()
        }
    }

    private suspend fun download(c: com.maanit.stableshare.di.AppContainer, fileId: String): Long {
        val start = System.currentTimeMillis()
        val row = c.transferController.download(fileId)
        val ms = awaitCompleted(c, row.id) - start
        c.transferRepository.getTransfer(row.id)?.localUri?.let { Uri.parse(it).path?.let(::File)?.delete() }
        c.transferRepository.deleteTransfer(row.id)
        return ms
    }

    /** Polls every 100 ms; any terminal state other than COMPLETED fails the benchmark. */
    private suspend fun awaitCompleted(c: com.maanit.stableshare.di.AppContainer, id: String): Long {
        while (true) {
            val row = c.transferRepository.getTransfer(id)!!
            when (row.state) {
                TransferState.COMPLETED -> return System.currentTimeMillis()
                TransferState.FAILED, TransferState.CANCELLED -> assertEquals("${row.errorCode}: ${row.errorMessage}", TransferState.COMPLETED, row.state)
                else -> delay(100)
            }
        }
    }

    private companion object {
        /** States that can use the link; paused and failed transfers are left alone. */
        val ACTIVE_OR_WAITING = arrayOf(TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.RETRYING, TransferState.VERIFYING)
    }
}
