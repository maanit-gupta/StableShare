package com.maanit.stableshare.engine

import android.net.Uri
import com.maanit.stableshare.data.db.ChunkEntity
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.files.DiskFullException
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.ChunkHashMismatchException
import com.maanit.stableshare.data.net.TransferApi
import com.maanit.stableshare.domain.ChunkStatus
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Download algorithm of DESIGN.md §3.2 / §10: a full-length .part file, ranged GETs with
 * If-Range, every chunk hashed against the manifest before it is written, write → fsync → DONE
 * (rule 2), then a full re-hash before the atomic rename and COMPLETED (rule 3).
 */
class DownloadPipeline(
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
        private val t = initial
        private val fileId = requireNotNull(initial.remoteId) { "download without a fileId" }
        private val etag = requireNotNull(initial.etag) { "download without an ETag" }
        private val expectedSha = requireNotNull(initial.sha256) { "download without a sha256" }
        private var verifyFailures = 0

        suspend fun execute() {
            tracker.start(id, t.bytesDone, t.fileSize)
            val existing = File(requireNotNull(Uri.parse(t.localUri).path))
            if (!existing.name.endsWith(PART_SUFFIX) && existing.isFile) {
                // A previous run renamed the verified part file but died before COMPLETED.
                verifyFinalised(existing)
                return
            }
            while (true) {
                try {
                    attempt(existing)
                    return
                } catch (_: PipelineSignal.Restart) {
                    continue
                }
            }
        }

        private suspend fun attempt(part: File) {
            tracker.setPhase(id, TransferPhase.Transferring)
            ensurePartFile(part)
            reverifyTail(part)
            commitProgress()

            for (chunk in repo.getChunks(id).filter { it.status != ChunkStatus.DONE }) {
                currentCoroutineContext().ensureActive()
                fetchChunk(part, chunk)
            }

            runner.enterVerifying()
            val actual = runner.run(Step.Named("verify file"), retryInPlace = false) {
                if (!files.partFileValid(part, t.fileSize)) null else files.sha256(part) { tracker.setPhase(id, TransferPhase.Verifying(it)) }
            }
            if (actual == null) {
                runner.backToTransferring(null, "Part file missing or truncated before verification; rebuilding it")
                throw PipelineSignal.Restart("part file gone")
            }
            if (actual.equals(expectedSha, ignoreCase = true)) {
                finalise(part, actual)
                return
            }
            recoverFromMismatch(part, actual)
        }

        /** Step 1: the .part file must exist at full length; otherwise every chunk starts over. */
        private suspend fun ensurePartFile(part: File) {
            if (files.partFileValid(part, t.fileSize)) return
            val existing = if (part.isFile) part.length() else 0L
            if (files.availableBytes() < t.fileSize - existing) {
                runner.fail(ErrorCode.DISK_FULL, "Need ${t.fileSize} bytes for ${t.fileName}, ${files.availableBytes()} free")
            }
            if (repo.getChunks(id).any { it.status == ChunkStatus.DONE } || part.exists()) {
                repo.resetChunks(id)
                repo.logEventWhile(id, StateMachine.ACTIVE, EventType.INFO, "Part file missing or wrong size; restarting from chunk 0")
            }
            val created = runner.run(Step.Named("create part file")) { files.createPartFile(id, t.fileName, t.fileSize) }
            if (created.absolutePath != part.absolutePath) {
                runner.fail(ErrorCode.UNKNOWN, "Part file path mismatch: ${created.path} vs ${part.path}")
            }
        }

        /**
         * Step 2: re-hash the last [TAIL_REVERIFY] DONE chunks on disk. They are the ones a crash or
         * a lost fsync could have torn; anything older is caught by the full-file check.
         */
        private suspend fun reverifyTail(part: File) {
            val tail = repo.getChunks(id).filter { it.status == ChunkStatus.DONE }.takeLast(TAIL_REVERIFY)
            val bad = tail.filterNot { files.verifyChunkOnDisk(part, it.offset, it.length, requireNotNull(it.sha256)) }
            if (bad.isNotEmpty()) {
                repo.resetChunks(id, bad.map { it.index })
                repo.logEventWhile(id, StateMachine.ACTIVE, EventType.INFO, "On-disk check failed for chunk(s) ${bad.map { it.index }}; fetching them again")
            }
        }

        /** Step 3: fetch → hash check BEFORE writing → write + fsync → THEN mark DONE. */
        private suspend fun fetchChunk(part: File, chunk: ChunkEntity) {
            val expected = requireNotNull(chunk.sha256)
            runner.run(Step.Chunk(chunk.index)) {
                val body = api.downloadRange(fileId, chunk.offset, chunk.length, etag) { tracker.setInFlight(id, it, chunk.index) }
                if (!body.sha256.equals(expected, ignoreCase = true)) {
                    throw ChunkHashMismatchException(chunk.index, expected, body.sha256)
                }
                files.writeChunkAt(part, chunk.offset, body.bytes)
            }
            if (!repo.markChunkDone(id, chunk.index, null)) throw PipelineSignal.Stop("not TRANSFERRING")
            commitProgress()
        }

        /**
         * Step 5, mismatch: re-hash every chunk on disk and fetch only the bad ones again. A second
         * consecutive mismatch (or one no chunk explains) is FAILED FILE_HASH_MISMATCH.
         */
        private suspend fun recoverFromMismatch(part: File, actual: String) {
            verifyFailures++
            val message = "File hash $actual differs from manifest $expectedSha"
            if (verifyFailures >= MAX_VERIFY_FAILURES) runner.fail(ErrorCode.FILE_HASH_MISMATCH, "$message ($verifyFailures times)")
            val bad = repo.getChunks(id).filterNot {
                files.verifyChunkOnDisk(part, it.offset, it.length, requireNotNull(it.sha256))
            }.map { it.index }
            if (bad.isEmpty()) runner.fail(ErrorCode.FILE_HASH_MISMATCH, "$message, yet every chunk matches the manifest")
            repo.resetChunks(id, bad)
            runner.backToTransferring(ErrorCode.FILE_HASH_MISMATCH, "$message; fetching chunk(s) $bad again")
            throw PipelineSignal.Restart("re-fetch bad chunks")
        }

        /** Verified: atomic rename (never overwriting), record the final URI, then COMPLETED. */
        private suspend fun finalise(part: File, sha: String) {
            val final = try {
                files.finalizePart(part, t.fileName)
            } catch (e: DiskFullException) {
                runner.fail(ErrorCode.DISK_FULL, e.message ?: "disk full")
            }
            repo.setLocalUri(id, Uri.fromFile(final).toString())
            complete(sha, "Local SHA-256 $sha matches the manifest; saved as ${final.name}")
        }

        private suspend fun verifyFinalised(file: File) {
            runner.enterVerifying()
            val actual = runner.run(Step.Named("verify file")) {
                files.sha256(file) { tracker.setPhase(id, TransferPhase.Verifying(it)) }
            }
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                runner.fail(ErrorCode.FILE_HASH_MISMATCH, "Saved file ${file.name} hashes to $actual, expected $expectedSha")
            }
            complete(actual, "Local SHA-256 $actual of ${file.name} matches the manifest")
        }

        private suspend fun complete(sha: String, message: String) {
            repo.logEventWhile(id, StateMachine.ACTIVE, EventType.VERIFIED, message)
            if (!repo.transition(id, TransferState.COMPLETED, expectedFrom = TransferState.VERIFYING)) {
                throw PipelineSignal.Stop("lost CAS VERIFYING → COMPLETED ($sha)")
            }
        }

        private suspend fun commitProgress() {
            repo.getTransfer(id)?.let { tracker.setCommitted(id, it.bytesDone) }
        }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val TAIL_REVERIFY = 2
        const val MAX_VERIFY_FAILURES = 2
    }
}
