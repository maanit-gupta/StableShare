package com.maanit.stableshare.engine

import android.net.Uri
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.files.SourceChangedException
import com.maanit.stableshare.data.net.CreateSessionRequest
import com.maanit.stableshare.data.net.HttpStatusException
import com.maanit.stableshare.data.net.TransferApi
import com.maanit.stableshare.data.net.isAmbiguous
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs one claimed (TRANSFERRING) transfer until it completes, fails, waits or is stopped. */
interface TransferPipeline {
    suspend fun run(transfer: TransferEntity)
}

/**
 * Upload algorithm of DESIGN.md §3.1 / §8: hash the source, create the session (idempotent),
 * take the server's chunk list as the truth (rule 7), send the missing chunks in order, then
 * `complete` and compare the server's SHA-256 before COMPLETED (rule 3).
 */
class UploadPipeline(
    private val api: TransferApi,
    private val files: FileStore,
    private val env: PipelineEnv,
) : TransferPipeline {

    override suspend fun run(transfer: TransferEntity) {
        try {
            Run(transfer).execute()
        } catch (_: PipelineSignal.Stop) {
            // State already written (or another writer won); the job simply ends.
        }
    }

    private inner class Run(initial: TransferEntity) {
        private val id = initial.id
        private val repo = env.repo
        private val tracker = env.tracker
        private val runner = RetryRunner(id, env)
        private val uri = Uri.parse(initial.localUri)
        private var t = initial
        private var sessionRecreated = false
        private var missingResyncs = 0

        suspend fun execute() {
            tracker.start(id, t.bytesDone, t.fileSize)
            prepareSource()
            while (true) {
                try {
                    if (attempt()) return
                } catch (e: PipelineSignal.Restart) {
                    continue
                } catch (e: PipelineSignal.SessionLost) {
                    if (sessionRecreated) runner.fail(ErrorCode.SESSION_NOT_FOUND, "Upload session lost twice: ${e.message}")
                    sessionRecreated = true
                    repo.logEvent(id, EventType.INFO, "Server lost the upload session (${e.message}); recreating it once")
                    ensureTransferring(ErrorCode.SESSION_NOT_FOUND, "Upload session lost; recreating it")
                    repo.setSessionCreated(id, false)
                } catch (e: PipelineSignal.MissingChunks) {
                    if (++missingResyncs > MAX_MISSING_RESYNCS) {
                        runner.fail(ErrorCode.SESSION_CONFLICT, "Server still reports missing chunks ${e.missing} after $MAX_MISSING_RESYNCS re-syncs")
                    }
                    repo.resetChunks(id, e.missing)
                    runner.backToTransferring(null, "Server reported missing chunk(s) ${e.missing}; re-sending them")
                }
            }
        }

        /** Step 1: hash the source once (phase Preparing) and re-check size/mtime on resume. */
        private suspend fun prepareSource() {
            val expected = t.sha256
            runner.run(Step.Named("prepare source")) {
                val before = files.querySource(uri)
                checkUnchanged(before.size, before.lastModified)
                if (expected == null) {
                    tracker.setPhase(id, TransferPhase.Preparing(0))
                    val sha = files.sha256(uri) { tracker.setPhase(id, TransferPhase.Preparing(it)) }
                    val after = files.querySource(uri)
                    if (after.size != before.size || after.lastModified != before.lastModified) {
                        throw SourceChangedException("Source changed while it was being hashed")
                    }
                    repo.setSourceInfo(id, sha, before.lastModified)
                    t = repo.getTransfer(id) ?: throw PipelineSignal.Stop("row deleted")
                }
            }
            tracker.setPhase(id, TransferPhase.Transferring)
        }

        private fun checkUnchanged(size: Long, lastModified: Long?) {
            if (size != t.fileSize) throw SourceChangedException("Source size changed: expected ${t.fileSize}, now $size")
            val recorded = t.sourceLastModified
            if (recorded != null && lastModified != null && recorded != lastModified) {
                throw SourceChangedException("Source modified at $lastModified, expected $recorded")
            }
        }

        /** One pass: session → server sync → missing chunks → verify. True once COMPLETED. */
        private suspend fun attempt(): Boolean {
            val sha = requireNotNull(t.sha256)
            tracker.setPhase(id, TransferPhase.Transferring)
            runner.run(Step.Named("create session")) {
                session { api.createSession(id, CreateSessionRequest(t.fileName, t.fileSize, t.chunkSize, sha)) }
            }
            repo.setSessionCreated(id)

            val status = runner.run(Step.Named("get status")) { session { api.getUploadStatus(id) } }
            if (status.isCompleted) {
                // complete already succeeded (e.g. its response was lost before a restart).
                runner.enterVerifying()
                finish(status.sha256 ?: runner.fail(ErrorCode.UNKNOWN, "Server says COMPLETED without a sha256"))
                return true
            }
            if (!repo.applyServerReceivedChunks(id, status.receivedChunks)) throw PipelineSignal.Stop("not active")
            commitProgress()

            for (chunk in repo.getChunks(id).filter { it.status != ChunkStatus.DONE }) {
                currentCoroutineContext().ensureActive()
                sendChunk(chunk.index, chunk.offset, chunk.length)
            }

            runner.enterVerifying()
            val serverSha = runner.run(
                Step.Named("complete"),
                retryInPlace = false,
                recover = { outcome -> if (outcome.isAmbiguous) completedViaStatus() else null },
            ) { session { api.completeUpload(id).sha256 } }
            finish(serverSha)
            return true
        }

        private suspend fun sendChunk(index: Int, offset: Long, length: Int) {
            var bodySent = false
            var sentSha: String? = null
            val sha = runner.run(
                Step.Chunk(index),
                recover = { outcome -> if (outcome.isAmbiguous && bodySent) confirmedViaStatus(index, sentSha) else null },
            ) {
                bodySent = false
                val data = files.readChunk(uri, offset, length, t.fileSize, t.sourceLastModified)
                sentSha = data.sha256
                session {
                    api.uploadChunk(id, index, data.bytes, data.sha256) { sent ->
                        tracker.setInFlight(id, sent, index)
                        if (sent >= length) bodySent = true
                    }
                }
                data.sha256
            }
            if (!repo.markChunkDone(id, index, sha)) throw PipelineSignal.Stop("not TRANSFERRING")
            commitProgress()
        }

        /**
         * Lost-response check (DESIGN.md §8): the whole body went out but no 2xx came back. If GET
         * status lists the chunk, the server stored it: done, without resending.
         */
        private suspend fun confirmedViaStatus(index: Int, sha: String?): String? {
            if (sha == null) return null
            val status = quietly { api.getUploadStatus(id) } ?: return null
            if (index !in status.receivedChunks) return null
            repo.logEvent(
                id, EventType.CHUNK_CONFIRMED_AFTER_LOST_RESPONSE,
                "Chunk $index: response lost, but GET status lists it; not resending",
                chunkIndex = index,
            )
            return sha
        }

        /** `complete` failed ambiguously: if the session is COMPLETED, take its verified hash. */
        private suspend fun completedViaStatus(): String? {
            val status = quietly { api.getUploadStatus(id) } ?: return null
            if (!status.isCompleted || status.sha256 == null) return null
            repo.logEvent(id, EventType.INFO, "complete: response lost, but GET status says COMPLETED")
            return status.sha256
        }

        /** VERIFYING: the server's verified hash must equal ours (rule 3). */
        private suspend fun finish(serverSha: String) {
            val expected = requireNotNull(t.sha256)
            if (!serverSha.equals(expected, ignoreCase = true)) {
                runner.fail(ErrorCode.FILE_HASH_MISMATCH, "Server hash $serverSha differs from local $expected")
            }
            repo.logEvent(id, EventType.VERIFIED, "Server verified SHA-256 $serverSha")
            if (!repo.transition(id, TransferState.COMPLETED, expectedFrom = TransferState.VERIFYING)) {
                throw PipelineSignal.Stop("lost CAS VERIFYING → COMPLETED")
            }
        }

        /** After a session loss the row may be VERIFYING (404 on complete); get back to TRANSFERRING. */
        private suspend fun ensureTransferring(code: ErrorCode, message: String) {
            when (repo.getTransfer(id)?.state) {
                TransferState.TRANSFERRING -> Unit
                TransferState.VERIFYING -> runner.backToTransferring(code, message)
                else -> throw PipelineSignal.Stop("not active")
            }
        }

        private suspend fun commitProgress() {
            repo.getTransfer(id)?.let { tracker.setCommitted(id, it.bytesDone) }
        }

        /** Maps the two server answers the pipeline handles itself onto signals. */
        private suspend fun <T> session(block: suspend () -> T): T = try {
            block()
        } catch (e: HttpStatusException) {
            when {
                e.status == 404 && e.code == "SESSION_NOT_FOUND" -> throw PipelineSignal.SessionLost(e.message ?: "404")
                e.status == 409 && e.code == "MISSING_CHUNKS" -> throw PipelineSignal.MissingChunks(e.missing.orEmpty())
                else -> throw e
            }
        }

        private suspend fun <T> quietly(block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        /** DESIGN.md §7: MISSING_CHUNKS re-syncs at most twice, then it is fatal. */
        const val MAX_MISSING_RESYNCS = 2
    }
}
