package com.maanit.stableshare.engine

import android.net.Uri
import android.util.Log
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.TransferApi
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Every user intent goes through here (the UI never writes state). Each change is a repository
 * transition; anything that creates runnable work calls [TransferScheduler.ensureRunning], as a
 * user-initiated start (Android 14+ may then host the run in a user-initiated job).
 */
class TransferController(
    private val repo: TransferRepository,
    private val api: TransferApi,
    private val files: FileStore,
    private val settings: suspend () -> Settings,
    private val scheduler: TransferScheduler,
    private val engine: TransferEngine,
) {

    /** Generates [sizeMb] MiB of random bytes in app storage and queues their upload. */
    suspend fun uploadGeneratedFile(sizeMb: Int, onProgress: (Long) -> Unit = {}): TransferEntity {
        val file = files.generateTestFile(sizeMb, onProgress = onProgress)
        return uploadUri(Uri.fromFile(file))
    }

    /** Queues an upload of [uri]; content:// sources keep a persisted read grant. */
    suspend fun uploadUri(uri: Uri): TransferEntity {
        if (uri.scheme == "content") files.takePersistableReadPermission(uri)
        val info = files.querySource(uri)
        val transfer = repo.createUpload(
            fileName = info.name,
            fileSize = info.size,
            mimeType = info.mimeType,
            sourceUri = uri.toString(),
            chunkSize = settings().uploadChunkSizeBytes,
        )
        scheduler.ensureRunning(userInitiated = true)
        return transfer
    }

    /** Fetches the manifest (chunk size from settings) and queues the download into its own .part file. */
    suspend fun download(fileId: String): TransferEntity {
        val manifest = api.getManifest(fileId, settings().uploadChunkSizeBytes)
        val id = UUID.randomUUID().toString()
        val part = files.partFileFor(id, manifest.name)
        val transfer = repo.createDownload(manifest, Uri.fromFile(part).toString(), id = id)
        scheduler.ensureRunning(userInitiated = true)
        return transfer
    }

    suspend fun perform(id: String, action: TransferAction): Boolean = when (action) {
        TransferAction.PAUSE -> pause(id)
        TransferAction.RESUME -> resume(id)
        TransferAction.RETRY -> retry(id)
        TransferAction.CANCEL -> cancel(id)
        TransferAction.REMOVE -> remove(id)
    }

    /** The coordinator sees the row leave TRANSFERRING/RETRYING and cancels the job; progress is kept. */
    suspend fun pause(id: String): Boolean = repo.transition(id, TransferState.PAUSED)

    /**
     * The notification's "Pause transfers": pauses every QUEUED, TRANSFERRING and RETRYING row
     * (VERIFYING has no edge to PAUSED and finishes on its own). Returns how many were paused.
     */
    suspend fun pauseAll(): Int =
        repo.getInStates(TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.RETRYING).count { pause(it.id) }

    suspend fun resume(id: String): Boolean =
        repo.transition(id, TransferState.QUEUED, expectedFrom = TransferState.PAUSED).also { if (it) scheduler.ensureRunning(userInitiated = true) }

    /** Manual retry: FAILED → QUEUED; the repository resets attempt counters and keeps DONE chunks. */
    suspend fun retry(id: String): Boolean =
        repo.transition(id, TransferState.QUEUED, expectedFrom = TransferState.FAILED).also { if (it) scheduler.ensureRunning(userInitiated = true) }

    /**
     * CANCELLED wins the CAS first, so nothing can revive the row. Then the job (if any) is
     * stopped and the server session or local file is removed, best effort, even if the caller
     * is cancelled meanwhile. Cleanup never writes state or progress.
     */
    suspend fun cancel(id: String): Boolean {
        val row = repo.getTransfer(id) ?: return false
        if (!repo.transition(id, TransferState.CANCELLED)) return false
        withContext(NonCancellable) {
            engine.stopJob(id)
            cleanup(row)
        }
        return true
    }

    suspend fun remove(id: String): Boolean = repo.deleteTransfer(id)

    private suspend fun cleanup(row: TransferEntity) {
        val message = when (row.type) {
            TransferType.UPLOAD -> try {
                api.deleteUpload(row.remoteId ?: row.id)
                Uri.parse(row.localUri).takeIf { it.scheme == "content" }?.let(files::releasePersistableReadPermission)
                "Cleanup: server session deleted"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Cleanup: could not delete the server session (${e.message}); it expires in 24 h"
            }
            TransferType.DOWNLOAD -> {
                // The .part file, or a file already renamed but never marked COMPLETED.
                val file = Uri.parse(row.localUri).path?.let(::File)
                if (file == null || files.deletePart(file)) "Cleanup: local file removed" else "Cleanup: could not delete ${file.name}"
            }
        }
        Log.i(TAG, "${row.id}: $message")
        runCatching { repo.logEvent(row.id, EventType.INFO, message) }
    }

    private companion object {
        const val TAG = "StableShare"
    }
}
