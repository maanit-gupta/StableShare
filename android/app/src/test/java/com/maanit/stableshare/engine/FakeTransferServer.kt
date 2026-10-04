package com.maanit.stableshare.engine

import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.ChunkUploadResponse
import com.maanit.stableshare.data.net.CompleteResponse
import com.maanit.stableshare.data.net.CreateSessionRequest
import com.maanit.stableshare.data.net.CreateSessionResponse
import com.maanit.stableshare.data.net.HttpStatusException
import com.maanit.stableshare.data.net.Manifest
import com.maanit.stableshare.data.net.ManifestChunk
import com.maanit.stableshare.data.net.RangeBody
import com.maanit.stableshare.data.net.RemoteFile
import com.maanit.stableshare.data.net.RemoteFileChangedException
import com.maanit.stableshare.data.net.TransferApi
import com.maanit.stableshare.data.net.UploadStatus
import com.maanit.stableshare.domain.ChunkPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory implementation of the server protocol (DESIGN.md §3) with the same semantics as
 * server/src: idempotent create, dedupe of identical chunks, 422 on a bad chunk hash, 409
 * MISSING_CHUNKS, If-Range → RemoteFileChangedException. Every request is recorded, and
 * [onRequest] / [fault] script interruptions per request.
 */
class FakeTransferServer : TransferApi {

    enum class Op { CREATE, CHUNK, STATUS, COMPLETE, DELETE, LIST, MANIFEST, RANGE }

    /** One request; [attempt] counts earlier requests with the same op and index (1-based). */
    data class Call(val op: Op, val id: String, val index: Int?, val attempt: Int)

    sealed interface Fault {
        /** Fails before anything is processed (503, connection drop, …). */
        data class Before(val error: Throwable) : Fault

        /** Processes and persists, then fails: the lost-response case. */
        data class After(val error: Throwable) : Fault

        /** One byte flipped in the body: a download's response, or an upload chunk's request. */
        data object Corrupt : Fault
    }

    /** [linked]: an instant session's copy of the original's bytes (the server's hard link), so deleting one never affects the other. */
    class Session(val request: CreateSessionRequest, val linked: ByteArray? = null) {
        val chunks = ConcurrentHashMap<Int, ByteArray>()
        @Volatile var completedSha: String? = null
        val instant: Boolean get() = linked != null
    }

    class Blob(val fileId: String, val name: String, @Volatile var bytes: ByteArray) {
        @Volatile var etag: String = etagOf(bytes)
    }

    val sessions = ConcurrentHashMap<String, Session>()
    val blobs = ConcurrentHashMap<String, Blob>()
    val calls = CopyOnWriteArrayList<Call>()

    /** Runs at the start of every request (after it is recorded); may suspend to hold it. */
    @Volatile var onRequest: suspend (Call) -> Unit = {}

    /** Instant upload (server 6.2a): when false, every create makes a normal session. */
    @Volatile var instantUploads: Boolean = true

    /** The hash COMPLETED sessions report; tests swap it to simulate a server answering a wrong SHA-256. */
    @Volatile var reportedSha: (String) -> String = { it }

    /** Decides a fault per request; null = behave normally. */
    @Volatile var fault: (Call) -> Fault? = { null }

    /** Requests currently suspended in [onRequest] (for concurrency tests). */
    val inFlight = MutableStateFlow(0)

    /** CHUNK and RANGE requests running right now, from arrival until they answer or fail. */
    val activeChunkRequests = AtomicInteger()

    /** The most CHUNK/RANGE requests ever running at once. */
    val peakChunkRequests = AtomicInteger()

    /** Runs when a CHUNK request ends (answered or failed) and when a RANGE request answers with a body. */
    @Volatile var onChunkBody: (Op, ended: Boolean) -> Unit = { _, _ -> }

    fun count(op: Op, index: Int? = null, id: String? = null): Int =
        calls.count { it.op == op && (index == null || it.index == index) && (id == null || it.id == id) }

    fun addFile(fileId: String, bytes: ByteArray, name: String = "$fileId.bin") {
        blobs[fileId] = Blob(fileId, name, bytes)
    }

    /** Like POST /admin/files/:id/mutate: new content, new ETag. */
    fun mutate(fileId: String) {
        val blob = blobs.getValue(fileId)
        val copy = blob.bytes.copyOf()
        if (copy.isEmpty()) blob.bytes = ByteArray(16) else { copy[copy.size / 2] = (copy[copy.size / 2] + 1).toByte(); blob.bytes = copy }
        blob.etag = etagOf(blob.bytes)
    }

    // ---- uploads ----

    override suspend fun createSession(uploadId: String, request: CreateSessionRequest): CreateSessionResponse {
        val call = begin(Op.CREATE, uploadId, null)
        return respond(call) {
            val session = sessions.computeIfAbsent(uploadId) { newSession(request) }
            if (session.request != request) throw http(409, "SESSION_CONFLICT")
            CreateSessionResponse(
                uploadId, totalChunks(request), request.chunkSize, received(session), state(session),
                instant = session.instant, sha256 = session.completedSha?.let(reportedSha),
            )
        }
    }

    /** Like the server's hash index: an existing COMPLETED session with this sha256 and size makes the new one instant. */
    private fun newSession(request: CreateSessionRequest): Session {
        val original = if (instantUploads) {
            sessions.entries.firstOrNull { (_, s) ->
                request.fileSize > 0 && s.completedSha == request.sha256 && s.request.fileSize == request.fileSize
            }?.key
        } else {
            null
        }
        return Session(request, linked = original?.let(::assembled)).also { if (original != null) it.completedSha = request.sha256 }
    }

    /** A COMPLETED session holds the whole file, so it lists every chunk (instant ones never received any). */
    private fun received(s: Session): List<Int> =
        if (s.completedSha != null) (0 until totalChunks(s.request)).toList() else s.chunks.keys.sorted()

    override suspend fun uploadChunk(
        uploadId: String,
        index: Int,
        bytes: ByteArray,
        sha256: String,
        onProgress: (Long) -> Unit,
    ): ChunkUploadResponse = chunkRequest {
        try {
            uploadChunkNow(uploadId, index, bytes, sha256, onProgress)
        } finally {
            onChunkBody(Op.CHUNK, true)
        }
    }

    private suspend fun uploadChunkNow(
        uploadId: String,
        index: Int,
        bytes: ByteArray,
        sha256: String,
        onProgress: (Long) -> Unit,
    ): ChunkUploadResponse {
        val call = begin(Op.CHUNK, uploadId, index)
        val f = fault(call)
        if (f is Fault.Before) { onProgress(bytes.size / 2L); throw f.error }
        onProgress(bytes.size.toLong())
        // Corrupt: a byte flipped in transit, which the server's per-chunk hash check must catch.
        val body = if (f is Fault.Corrupt && bytes.isNotEmpty()) bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else bytes
        val session = sessions[uploadId] ?: throw http(404, "SESSION_NOT_FOUND")
        if (session.completedSha != null) throw http(409, "SESSION_COMPLETED")
        val expected = ChunkPlanner.plan(session.request.fileSize, session.request.chunkSize)[index].length
        if (body.size != expected) throw http(400, "CHUNK_LENGTH_MISMATCH")
        val actual = FileStore.sha256Hex(body)
        if (actual != sha256) throw http(422, "CHUNK_HASH_MISMATCH")
        val existing = session.chunks[index]
        val status = when {
            existing == null -> { session.chunks[index] = body.copyOf(); ChunkUploadResponse.STATUS_STORED }
            FileStore.sha256Hex(existing) == actual -> ChunkUploadResponse.STATUS_ALREADY_RECEIVED
            else -> throw http(409, "CHUNK_CONFLICT")
        }
        if (f is Fault.After) throw f.error
        return ChunkUploadResponse(uploadId, index, status, actual)
    }

    override suspend fun getUploadStatus(uploadId: String): UploadStatus {
        val call = begin(Op.STATUS, uploadId, null)
        return respond(call) {
            val s = sessions[uploadId] ?: throw http(404, "SESSION_NOT_FOUND")
            UploadStatus(
                uploadId, s.request.fileName, s.request.fileSize, s.request.chunkSize, totalChunks(s.request),
                received(s), state(s), s.completedSha?.let(reportedSha),
            )
        }
    }

    override suspend fun completeUpload(uploadId: String): CompleteResponse {
        val call = begin(Op.COMPLETE, uploadId, null)
        return respond(call) {
            val s = sessions[uploadId] ?: throw http(404, "SESSION_NOT_FOUND")
            s.completedSha?.let { return@respond CompleteResponse(uploadId, "COMPLETED", reportedSha(it), s.request.fileSize) }
            val missing = (0 until totalChunks(s.request)).filter { !s.chunks.containsKey(it) }
            if (missing.isNotEmpty()) throw http(409, "MISSING_CHUNKS", missing)
            val sha = FileStore.sha256Hex(assembled(uploadId))
            if (sha != s.request.sha256) throw http(422, "FILE_HASH_MISMATCH")
            s.completedSha = sha
            CompleteResponse(uploadId, "COMPLETED", reportedSha(sha), s.request.fileSize)
        }
    }

    override suspend fun deleteUpload(uploadId: String) {
        val call = begin(Op.DELETE, uploadId, null)
        respond(call) { sessions.remove(uploadId); Unit }
    }

    fun assembled(uploadId: String): ByteArray {
        val s = sessions.getValue(uploadId)
        s.linked?.let { return it.copyOf() }
        return (0 until totalChunks(s.request)).map { s.chunks.getValue(it) }
            .fold(ByteArray(0)) { acc, b -> acc + b }
    }

    // ---- downloads ----

    override suspend fun listFiles(): List<RemoteFile> {
        val call = begin(Op.LIST, "", null)
        return respond(call) { blobs.values.map { RemoteFile(it.fileId, it.name, it.bytes.size.toLong(), FileStore.sha256Hex(it.bytes)) } }
    }

    override suspend fun getManifest(fileId: String, chunkSize: Int?): Manifest {
        val call = begin(Op.MANIFEST, fileId, null)
        return respond(call) {
            val blob = blobs[fileId] ?: throw http(404, "FILE_NOT_FOUND")
            val size = chunkSize ?: (2 * 1024 * 1024)
            val chunks = ChunkPlanner.plan(blob.bytes.size.toLong(), size).map {
                ManifestChunk(it.index, it.offset, it.length, FileStore.sha256Hex(blob.bytes.copyOfRange(it.offset.toInt(), it.offset.toInt() + it.length)))
            }
            Manifest(fileId, blob.name, blob.bytes.size.toLong(), FileStore.sha256Hex(blob.bytes), blob.etag, size, chunks)
        }
    }

    override suspend fun downloadRange(
        fileId: String,
        offset: Long,
        length: Int,
        etag: String,
        onProgress: (Long) -> Unit,
    ): RangeBody = chunkRequest {
        downloadRangeNow(fileId, offset, length, etag, onProgress).also { onChunkBody(Op.RANGE, false) }
    }

    private suspend fun downloadRangeNow(
        fileId: String,
        offset: Long,
        length: Int,
        etag: String,
        onProgress: (Long) -> Unit,
    ): RangeBody {
        val blob = blobs[fileId]
        val index = blob?.let { chunkIndexOf(offset) }
        val call = begin(Op.RANGE, fileId, index)
        val f = fault(call)
        if (f is Fault.Before) { onProgress(length / 2L); throw f.error }
        if (blob == null) throw http(404, "FILE_NOT_FOUND")
        if (etag != blob.etag) throw RemoteFileChangedException("Remote file $fileId changed")
        if (offset + length > blob.bytes.size) throw http(416, "RANGE_NOT_SATISFIABLE")
        val bytes = blob.bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        if (f is Fault.Corrupt) bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        onProgress(length.toLong())
        if (f is Fault.After) throw f.error
        return RangeBody(bytes, FileStore.sha256Hex(bytes))
    }

    /** Range requests carry offsets; tests think in chunk indices, so the chunk size is registered. */
    @Volatile var downloadChunkSize: Int = 1024

    private fun chunkIndexOf(offset: Long): Int = (offset / downloadChunkSize).toInt()

    // ---- plumbing ----

    private suspend fun begin(op: Op, id: String, index: Int?): Call {
        val call = Call(op, id, index, calls.count { it.op == op && it.id == id && it.index == index } + 1)
        calls += call
        inFlight.update { it + 1 }
        try {
            onRequest(call)
        } finally {
            inFlight.update { it - 1 }
        }
        return call
    }

    private inline fun <T> chunkRequest(block: () -> T): T {
        val now = activeChunkRequests.incrementAndGet()
        peakChunkRequests.accumulateAndGet(now, ::maxOf)
        try {
            return block()
        } finally {
            activeChunkRequests.decrementAndGet()
        }
    }

    private inline fun <T> respond(call: Call, block: () -> T): T {
        val f = fault(call)
        if (f is Fault.Before) throw f.error
        val result = block()
        if (f is Fault.After) throw f.error
        return result
    }

    private fun totalChunks(r: CreateSessionRequest) = ChunkPlanner.totalChunks(r.fileSize, r.chunkSize)

    private fun state(s: Session) = if (s.completedSha != null) "COMPLETED" else "UPLOADING"

    companion object {
        fun http(status: Int, code: String, missing: List<Int>? = null) =
            HttpStatusException(status, code, "fake $code", missing)

        fun etagOf(bytes: ByteArray) = "\"${FileStore.sha256Hex(bytes)}\""
    }
}
