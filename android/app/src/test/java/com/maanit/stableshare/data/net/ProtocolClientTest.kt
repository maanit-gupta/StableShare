package com.maanit.stableshare.data.net

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ProtocolClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ProtocolClient
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = ProtocolClient(ProtocolClient.buildOkHttp(), { server.url("/").toString() })
    }

    @After
    fun tearDown() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit): T {
        try {
            runBlocking { block() }
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun okHttpConfigurationOwnsRetries() {
        val ok = ProtocolClient.buildOkHttp()
        assertFalse(ok.retryOnConnectionFailure)
        assertEquals(10_000, ok.connectTimeoutMillis)
        assertEquals(30_000, ok.readTimeoutMillis)
        assertEquals(30_000, ok.writeTimeoutMillis)
    }

    // ---- uploads ----

    @Test
    fun createSessionSendsJsonAndParsesResponse() = runBlocking {
        server.enqueue(json(201, """{"uploadId":"$id","totalChunks":3,"chunkSize":2097152,"receivedChunks":[],"state":"UPLOADING"}"""))
        val res = client.createSession(id, CreateSessionRequest("video.mp4", 5_242_880, 2_097_152, "a".repeat(64)))
        assertEquals(3, res.totalChunks)
        assertEquals(emptyList<Int>(), res.receivedChunks)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/uploads/$id", req.target)
        assertTrue(req.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("video.mp4", body["fileName"]!!.jsonPrimitive.content)
        assertEquals(5_242_880L, body["fileSize"]!!.jsonPrimitive.long)
        assertEquals(2_097_152L, body["chunkSize"]!!.jsonPrimitive.long)
        assertEquals("a".repeat(64), body["sha256"]!!.jsonPrimitive.content)
    }

    @Test
    fun createSessionExistingReturns200() = runBlocking {
        server.enqueue(json(200, """{"uploadId":"$id","totalChunks":3,"chunkSize":2097152,"receivedChunks":[0,2],"state":"UPLOADING"}"""))
        assertEquals(listOf(0, 2), client.createSession(id, CreateSessionRequest("a", 1, 1024, "b".repeat(64))).receivedChunks)
    }

    @Test
    fun uploadChunkSendsHashHeaderExactLengthAndReportsProgress() = runBlocking {
        val bytes = Random(1).nextBytes(200_000)
        val hash = sha(bytes)
        server.enqueue(json(200, """{"uploadId":"$id","index":2,"status":"stored","sha256":"$hash"}"""))
        val progress = mutableListOf<Long>()
        val res = client.uploadChunk(id, 2, bytes, hash.uppercase()) { progress += it }
        assertEquals("stored", res.status)
        assertFalse(res.alreadyReceived)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/uploads/$id/chunks/2", req.target)
        assertEquals(hash, req.headers[ProtocolClient.HEADER_CHUNK_SHA256])
        assertEquals("200000", req.headers["Content-Length"])
        assertEquals("application/octet-stream", req.headers["Content-Type"])
        assertArrayEquals(bytes, req.body!!.toByteArray())
        assertEquals(200_000L, progress.last())
        assertTrue(progress.size > 1)
    }

    @Test
    fun uploadChunkDuplicateIsAlreadyReceived() = runBlocking {
        server.enqueue(json(200, """{"uploadId":"$id","index":0,"status":"already_received","sha256":"${"c".repeat(64)}"}"""))
        assertTrue(client.uploadChunk(id, 0, ByteArray(10), "c".repeat(64)).alreadyReceived)
    }

    @Test
    fun getUploadStatusParsesInProgressAndCompleted() = runBlocking {
        server.enqueue(json(200, """{"uploadId":"$id","fileName":"v","fileSize":5,"chunkSize":2,"totalChunks":3,"receivedChunks":[0,2],"state":"UPLOADING"}"""))
        server.enqueue(json(200, """{"uploadId":"$id","fileName":"v","fileSize":5,"chunkSize":2,"totalChunks":3,"receivedChunks":[0,1,2],"state":"COMPLETED","sha256":"${"d".repeat(64)}"}"""))
        val partial = client.getUploadStatus(id)
        assertEquals(listOf(0, 2), partial.receivedChunks)
        assertFalse(partial.isCompleted)
        assertNull(partial.sha256)
        val done = client.getUploadStatus(id)
        assertTrue(done.isCompleted)
        assertEquals("d".repeat(64), done.sha256)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun completeUploadPosts() = runBlocking {
        server.enqueue(json(200, """{"uploadId":"$id","state":"COMPLETED","sha256":"${"e".repeat(64)}","size":5}"""))
        val res = client.completeUpload(id)
        assertEquals(5L, res.size)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/uploads/$id/complete", req.target)
    }

    @Test
    fun completeMissingChunksCarriesTheList() {
        server.enqueue(json(409, """{"error":"MISSING_CHUNKS","message":"1 chunk(s) missing","missing":[1]}"""))
        val e = assertThrowsSuspend<HttpStatusException> { client.completeUpload(id) }
        assertEquals(409, e.status)
        assertEquals("MISSING_CHUNKS", e.code)
        assertEquals(listOf(1), e.missing)
    }

    @Test
    fun deleteUploadAccepts204() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        client.deleteUpload(id)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/uploads/$id", req.target)
    }

    // ---- downloads ----

    @Test
    fun listFilesAndManifest() = runBlocking {
        server.enqueue(json(200, """[{"fileId":"sample-odd","name":"sample-odd.bin","size":3145851,"sha256":"${"a".repeat(64)}"}]"""))
        server.enqueue(
            json(
                200,
                """{"fileId":"sample-odd","name":"sample-odd.bin","size":3145851,"sha256":"${"a".repeat(64)}",
                   "etag":"\"${"a".repeat(64)}\"","chunkSize":1048576,
                   "chunks":[{"index":3,"offset":3145728,"length":123,"sha256":"${"b".repeat(64)}"}]}""",
            ),
        )
        val files = client.listFiles()
        assertEquals("sample-odd", files.single().fileId)
        val manifest = client.getManifest("sample-odd", 1_048_576)
        assertEquals("\"${"a".repeat(64)}\"", manifest.etag)
        assertEquals(123, manifest.chunks.single().length)
        assertEquals("/api/files", server.takeRequest().target)
        assertEquals("/api/files/sample-odd/manifest?chunkSize=1048576", server.takeRequest().target)
    }

    @Test
    fun manifestWithoutChunkSizeOmitsQuery() = runBlocking {
        server.enqueue(json(200, """{"fileId":"f","name":"f","size":0,"sha256":"${"a".repeat(64)}","etag":"\"x\"","chunkSize":2097152,"chunks":[]}"""))
        assertTrue(client.getManifest("f").chunks.isEmpty())
        assertEquals("/api/files/f/manifest", server.takeRequest().target)
    }

    @Test
    fun downloadRangeSendsRangeAndIfRangeAndHashesBody() = runBlocking {
        val bytes = Random(5).nextBytes(150_000)
        server.enqueue(
            MockResponse.Builder().code(206)
                .setHeader("Content-Range", "bytes 2097152-2247151/209715200")
                .setHeader("ETag", "\"tag\"")
                .body(Buffer().write(bytes))
                .build(),
        )
        val progress = mutableListOf<Long>()
        val body = client.downloadRange("sample-200MB", 2_097_152, 150_000, "\"tag\"") { progress += it }
        assertArrayEquals(bytes, body.bytes)
        assertEquals(sha(bytes), body.sha256)
        assertEquals(150_000L, progress.last())

        val req = server.takeRequest()
        assertEquals("/api/files/sample-200MB/content", req.target)
        assertEquals("bytes=2097152-2247151", req.headers["Range"])
        assertEquals("\"tag\"", req.headers["If-Range"])
    }

    @Test
    fun downloadRange200InsteadOf206MeansRemoteChanged() {
        server.enqueue(MockResponse.Builder().code(200).setHeader("ETag", "\"new\"").body("whole file").build())
        assertThrowsSuspend<RemoteFileChangedException> { client.downloadRange("f", 0, 4, "\"old\"") }
    }

    @Test
    fun downloadRangeRejectsWrongContentRange() {
        server.enqueue(MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 4-7/100").body("abcd").build())
        assertThrowsSuspend<ProtocolViolationException> { client.downloadRange("f", 0, 4, "\"e\"") }
        server.enqueue(MockResponse.Builder().code(206).body("abcd").build())
        assertThrowsSuspend<ProtocolViolationException> { client.downloadRange("f", 0, 4, "\"e\"") }
    }

    @Test
    fun downloadRangeRejectsWrongLength() {
        server.enqueue(MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 0-3/100").body("abcdef").build())
        assertThrowsSuspend<ProtocolViolationException> { client.downloadRange("f", 0, 4, "\"e\"") }
    }

    @Test
    fun downloadRange416IsHttpError() {
        server.enqueue(json(416, """{"error":"RANGE_NOT_SATISFIABLE","message":"nope"}"""))
        val e = assertThrowsSuspend<HttpStatusException> { client.downloadRange("f", 100, 4, "\"e\"") }
        assertEquals(416, e.status)
        assertEquals("RANGE_NOT_SATISFIABLE", e.code)
    }

    @Test
    fun downloadRangeBodyDroppedMidway() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .setHeader("Content-Range", "bytes 0-99999/100000")
                .body(Buffer().write(ByteArray(100_000)))
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )
        val e = assertThrowsSuspend<IOException> { client.downloadRange("f", 0, 100_000, "\"e\"") }
        assertFalse(e is HttpStatusException || e is ProtocolViolationException)
    }

    // ---- errors ----

    @Test
    fun serverErrorsBecomeTypedHttpErrors() {
        server.enqueue(json(503, """{"error":"INJECTED_FAULT","message":"injected"}"""))
        val e503 = assertThrowsSuspend<HttpStatusException> { client.getUploadStatus(id) }
        assertEquals(503, e503.status)
        assertEquals("INJECTED_FAULT", e503.code)

        server.enqueue(json(422, """{"error":"CHUNK_HASH_MISMATCH","message":"bad","expected":"x","actual":"y"}"""))
        val e422 = assertThrowsSuspend<HttpStatusException> { client.uploadChunk(id, 0, ByteArray(1), "f".repeat(64)) }
        assertEquals("CHUNK_HASH_MISMATCH", e422.code)

        server.enqueue(json(404, """{"error":"SESSION_NOT_FOUND","message":"gone"}"""))
        assertEquals("SESSION_NOT_FOUND", assertThrowsSuspend<HttpStatusException> { client.getUploadStatus(id) }.code)
    }

    @Test
    fun retryAfterHeaderIsParsed() {
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "3").body("slow down").build())
        val e = assertThrowsSuspend<HttpStatusException> { client.listFiles() }
        assertEquals(429, e.status)
        assertNull(e.code)
        assertEquals(3_000L, e.retryAfterMs)
    }

    @Test
    fun unexpectedSuccessBodyIsAProtocolViolation() {
        server.enqueue(json(200, """{"nope":true}"""))
        assertThrowsSuspend<ProtocolViolationException> { client.getUploadStatus(id) }
    }

    @Test
    fun readTimeoutSurfacesAsSocketTimeout() {
        val fast = ProtocolClient(
            OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build(),
            { server.url("/").toString() },
        )
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").headersDelay(5, TimeUnit.SECONDS).build())
        assertThrowsSuspend<SocketTimeoutException> { fast.health() }
    }

    @Test
    fun coroutineCancellationCancelsTheCall() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").headersDelay(10, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        val call = async { client.health() }
        delay(200)
        server.takeRequest(2, TimeUnit.SECONDS) // the request reached the server
        call.cancel()
        withTimeout(2_000) { runCatching { call.await() } }
        assertTrue(call.isCancelled)
        assertTrue("cancel returned promptly", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun healthParses() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        assertTrue(client.health().ok)
        assertEquals("/health", server.takeRequest().target)
    }

    @Test
    fun baseUrlWithPathPrefixIsRespected() = runBlocking {
        val prefixed = ProtocolClient(ProtocolClient.buildOkHttp(), { server.url("/proxy/").toString() })
        server.enqueue(json(200, """{"ok":true}"""))
        prefixed.health()
        assertEquals("/proxy/health", server.takeRequest().target)
    }
}
