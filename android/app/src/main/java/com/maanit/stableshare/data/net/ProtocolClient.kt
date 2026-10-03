package com.maanit.stableshare.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bytes of one Range response plus their SHA-256 (computed while reading). */
class RangeBody(val bytes: ByteArray, val sha256: String)

/**
 * The client half of DESIGN.md §3. Every call is a suspend function whose cancellation cancels
 * the OkHttp [Call], which aborts the socket, so a blocked read or write ends immediately.
 * Non-2xx responses become [HttpStatusException]; the engine maps errors via [ErrorClassifier].
 */
class ProtocolClient(
    private val client: OkHttpClient,
    private val baseUrl: suspend () -> String,
    private val json: Json = DefaultJson,
) : TransferApi {

    // ---- uploads ----

    override suspend fun createSession(uploadId: String, request: CreateSessionRequest): CreateSessionResponse =
        send(
            Request.Builder()
                .url(url("api", "uploads", uploadId))
                .put(json.encodeToString(request).toRequestBody(JSON)),
        ) { decode(it) }

    /** Streams [bytes] in 64 KiB segments; [onProgress] receives bytes written so far. */
    override suspend fun uploadChunk(
        uploadId: String,
        index: Int,
        bytes: ByteArray,
        sha256: String,
        onProgress: (Long) -> Unit,
    ): ChunkUploadResponse = send(
        Request.Builder()
            .url(url("api", "uploads", uploadId, "chunks", index.toString()))
            .header(HEADER_CHUNK_SHA256, sha256.lowercase())
            .put(ProgressRequestBody(bytes, onProgress)),
    ) { decode(it) }

    override suspend fun getUploadStatus(uploadId: String): UploadStatus =
        send(Request.Builder().url(url("api", "uploads", uploadId)).get()) { decode(it) }

    override suspend fun completeUpload(uploadId: String): CompleteResponse =
        send(
            Request.Builder()
                .url(url("api", "uploads", uploadId, "complete"))
                .post(ByteArray(0).toRequestBody(JSON)),
        ) { decode(it) }

    /** Idempotent: 204 whether or not the session existed. */
    override suspend fun deleteUpload(uploadId: String) {
        send(Request.Builder().url(url("api", "uploads", uploadId)).delete()) { }
    }

    // ---- downloads ----

    override suspend fun listFiles(): List<RemoteFile> =
        send(Request.Builder().url(url("api", "files")).get()) { decode(it) }

    override suspend fun getManifest(fileId: String, chunkSize: Int?): Manifest {
        val u = url("api", "files", fileId, "manifest").newBuilder()
            .apply { if (chunkSize != null) addQueryParameter("chunkSize", chunkSize.toString()) }
            .build()
        return send(Request.Builder().url(u).get()) { decode(it) }
    }

    /**
     * Fetches bytes [offset, offset + length) with `Range` + `If-Range: etag`. Requires 206 with
     * the exact Content-Range we asked for. A 200 means the ETag no longer matches: the body is
     * discarded unread and [RemoteFileChangedException] is thrown.
     */
    override suspend fun downloadRange(
        fileId: String,
        offset: Long,
        length: Int,
        etag: String,
        onProgress: (Long) -> Unit,
    ): RangeBody {
        require(offset >= 0 && length > 0) { "invalid range $offset+$length" }
        val last = offset + length - 1
        val request = Request.Builder()
            .url(url("api", "files", fileId, "content"))
            .header("Range", "bytes=$offset-$last")
            .header("If-Range", etag)
            .get()
        return send(request, expect = setOf(206, 200)) { response ->
            if (response.code == 200) {
                throw RemoteFileChangedException(
                    "Remote file $fileId changed (sent $etag, server has ${response.header("ETag")})",
                )
            }
            val contentRange = response.header("Content-Range")
            val match = contentRange?.let { CONTENT_RANGE.matchEntire(it.trim()) }
                ?: throw ProtocolViolationException("206 without a valid Content-Range: $contentRange")
            val (start, end) = match.destructured
            if (start.toLong() != offset || end.toLong() != last) {
                throw ProtocolViolationException("Asked for bytes $offset-$last, got $contentRange")
            }
            val declared = response.body.contentLength()
            if (declared != -1L && declared != length.toLong()) {
                throw ProtocolViolationException("Content-Length $declared for a $length-byte range")
            }
            readExactly(response, length, onProgress)
        }
    }

    suspend fun health(): Health = send(Request.Builder().url(url("health")).get()) { decode(it) }

    // ---- plumbing ----

    private suspend fun url(vararg segments: String): HttpUrl {
        val builder = baseUrl().toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    /**
     * Runs the exchange on OkHttp's dispatcher and hands the response to [handle] there (blocking
     * body I/O is fine: cancellation cancels the Call, which unblocks it). Statuses outside
     * [expect] (default: any 2xx) become [HttpStatusException].
     */
    private suspend fun <T> send(
        builder: Request.Builder,
        expect: Set<Int>? = null,
        handle: (Response) -> T,
    ): T {
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val ok = expect?.contains(it.code) ?: it.isSuccessful
                            if (!ok) throw toHttpError(it)
                            handle(it)
                        }
                    }
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
    }

    private inline fun <reified T> decode(response: Response): T {
        val text = response.body.string()
        return try {
            json.decodeFromString<T>(text)
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw ProtocolViolationException("Unexpected ${response.code} body from ${response.request.url.encodedPath}", e)
        }
    }

    private fun toHttpError(response: Response): HttpStatusException {
        val text = runCatching { response.peekBody(MAX_ERROR_BODY).string() }.getOrDefault("")
        val body = runCatching { json.decodeFromString<ErrorBody>(text) }.getOrNull()
        val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000)
        return HttpStatusException(
            status = response.code,
            code = body?.error,
            serverMessage = body?.message ?: text.take(200).ifBlank { null },
            missing = body?.missing,
            retryAfterMs = retryAfterMs,
        )
    }

    private fun readExactly(response: Response, length: Int, onProgress: (Long) -> Unit): RangeBody {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = ByteArray(length)
        val source = response.body.byteStream()
        var read = 0
        while (read < length) {
            val n = source.read(out, read, minOf(SEGMENT, length - read))
            if (n < 0) throw EOFException("Body ended after $read of $length bytes")
            digest.update(out, read, n)
            read += n
            onProgress(read.toLong())
        }
        if (source.read() != -1) throw ProtocolViolationException("Body longer than the $length-byte range")
        return RangeBody(out, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Fixed-length body that reports progress as OkHttp writes it to the socket. */
    private class ProgressRequestBody(
        private val bytes: ByteArray,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var written = 0
            while (written < bytes.size) {
                val n = minOf(SEGMENT, bytes.size - written)
                sink.write(bytes, written, n)
                sink.flush()
                written += n
                onProgress(written.toLong())
            }
        }
    }

    companion object {
        const val HEADER_CHUNK_SHA256 = "X-Chunk-SHA256"
        private const val SEGMENT = 64 * 1024
        private const val MAX_ERROR_BODY = 64L * 1024
        private val JSON = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

        val DefaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** We own retries (rule 6): OkHttp never silently retries a request. */
        fun buildOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
