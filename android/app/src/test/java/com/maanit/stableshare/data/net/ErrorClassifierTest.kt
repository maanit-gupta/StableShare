package com.maanit.stableshare.data.net

import com.maanit.stableshare.data.files.DiskFullException
import com.maanit.stableshare.data.files.PartFileMissingException
import com.maanit.stableshare.data.files.SourceChangedException
import com.maanit.stableshare.data.files.SourceMissingException
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.engine.NetworkPolicy
import com.maanit.stableshare.engine.NetworkState
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorClassifierTest {

    private var state = NetworkState.Unmetered
    private var wifiOnly = false
    private val classifier = ErrorClassifier { NetworkPolicy.blockReason(state, NetworkPolicy.usable(state, wifiOnly)) }

    private fun http(status: Int, code: String? = null, retryAfterMs: Long? = null) =
        HttpStatusException(status, code, "msg", retryAfterMs = retryAfterMs)

    private fun retryable(code: ErrorCode) = Outcome.Retryable(code)
    private fun fatal(code: ErrorCode) = Outcome.Fatal(code)
    private fun waitFor(code: ErrorCode) = Outcome.WaitForNetwork(code)

    @Test
    fun transportErrorsWhileOnline() {
        assertEquals(retryable(ErrorCode.TIMEOUT), classifier.classify(SocketTimeoutException("read timed out")))
        assertEquals(retryable(ErrorCode.TIMEOUT), classifier.classify(InterruptedIOException("timeout")))
        assertEquals(retryable(ErrorCode.CONNECTION_LOST), classifier.classify(SocketException("Connection reset")))
        assertEquals(retryable(ErrorCode.CONNECTION_LOST), classifier.classify(EOFException("unexpected end of stream")))
        assertEquals(retryable(ErrorCode.CONNECTION_LOST), classifier.classify(ConnectException("refused")))
        assertEquals(retryable(ErrorCode.CONNECTION_LOST), classifier.classify(UnknownHostException("host")))
    }

    @Test
    fun transportErrorsWhileOfflineWaitForNetwork() {
        state = NetworkState.Offline
        listOf(
            SocketTimeoutException(), SocketException("reset"), ConnectException(), UnknownHostException(), IOException(),
        ).forEach { assertEquals(it.toString(), waitFor(ErrorCode.NETWORK_UNAVAILABLE), classifier.classify(it)) }
    }

    @Test
    fun transportErrorsOnMeteredWithWifiOnlyWaitForWifi() {
        state = NetworkState.Metered
        wifiOnly = true
        listOf(
            SocketTimeoutException(), SocketException("reset"), ConnectException(), UnknownHostException(), IOException(),
        ).forEach { assertEquals(it.toString(), waitFor(ErrorCode.METERED_NETWORK), classifier.classify(it)) }
        assertEquals(waitFor(ErrorCode.METERED_NETWORK), classifier.classify(http(400, "INCOMPLETE_BODY")))
        // An answer from the server is not about the network.
        assertEquals(retryable(ErrorCode.SERVER_ERROR), classifier.classify(http(503)))
        assertEquals(fatal(ErrorCode.SESSION_CONFLICT), classifier.classify(http(409, "SESSION_CONFLICT")))
    }

    @Test
    fun meteredWithoutWifiOnlyIsAnOrdinaryNetwork() {
        state = NetworkState.Metered
        assertEquals(retryable(ErrorCode.TIMEOUT), classifier.classify(SocketTimeoutException("read timed out")))
        assertEquals(retryable(ErrorCode.CONNECTION_LOST), classifier.classify(SocketException("Connection reset")))
    }

    @Test
    fun offlineWinsOverWifiOnly() {
        state = NetworkState.Offline
        wifiOnly = true
        assertEquals(waitFor(ErrorCode.NETWORK_UNAVAILABLE), classifier.classify(ConnectException()))
    }

    @Test
    fun aGuardStopWaitsWithItsOwnCode() {
        // Whatever the network says by now, the guard's verdict stands.
        assertEquals(waitFor(ErrorCode.METERED_NETWORK), classifier.classify(NetworkUnusableException(ErrorCode.METERED_NETWORK)))
        assertEquals(waitFor(ErrorCode.NETWORK_UNAVAILABLE), classifier.classify(NetworkUnusableException(ErrorCode.NETWORK_UNAVAILABLE)))
    }

    @Test
    fun serverErrors() {
        assertEquals(retryable(ErrorCode.SERVER_ERROR), classifier.classify(http(500, "INTERNAL")))
        assertEquals(retryable(ErrorCode.SERVER_ERROR), classifier.classify(http(503, "INJECTED_FAULT")))
        assertEquals(retryable(ErrorCode.SERVER_ERROR), classifier.classify(http(502)))
        assertEquals(fatal(ErrorCode.DISK_FULL), classifier.classify(http(507, "INSUFFICIENT_STORAGE")))
        assertEquals(Outcome.Retryable(ErrorCode.SERVER_ERROR, 2_000), classifier.classify(http(503, retryAfterMs = 2_000)))
    }

    @Test
    fun rateLimited() {
        assertEquals(Outcome.Retryable(ErrorCode.RATE_LIMITED, 5_000), classifier.classify(http(429, retryAfterMs = 5_000)))
        assertEquals(retryable(ErrorCode.RATE_LIMITED), classifier.classify(http(429)))
    }

    @Test
    fun notFound() {
        assertEquals(fatal(ErrorCode.SESSION_NOT_FOUND), classifier.classify(http(404, "SESSION_NOT_FOUND")))
        assertEquals(fatal(ErrorCode.REMOTE_FILE_CHANGED), classifier.classify(http(404, "FILE_NOT_FOUND")))
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(http(404, "NOT_FOUND")))
    }

    @Test
    fun conflicts() {
        listOf("SESSION_CONFLICT", "CHUNK_CONFLICT", "SESSION_COMPLETED", "MISSING_CHUNKS").forEach {
            assertEquals(it, fatal(ErrorCode.SESSION_CONFLICT), classifier.classify(http(409, it)))
        }
    }

    @Test
    fun unprocessableByBodyCode() {
        assertEquals(retryable(ErrorCode.CHUNK_HASH_MISMATCH), classifier.classify(http(422, "CHUNK_HASH_MISMATCH")))
        assertEquals(fatal(ErrorCode.FILE_HASH_MISMATCH), classifier.classify(http(422, "FILE_HASH_MISMATCH")))
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(http(422, "SOMETHING_ELSE")))
    }

    @Test
    fun otherClientErrorsAreFatal() {
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(http(413, "CHUNK_TOO_LARGE")))
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(http(413, "FILE_TOO_LARGE")))
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(http(400, "CHUNK_LENGTH_MISMATCH")))
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(http(411, "LENGTH_REQUIRED")))
        assertEquals(fatal(ErrorCode.REMOTE_FILE_CHANGED), classifier.classify(http(416, "RANGE_NOT_SATISFIABLE")))
    }

    @Test
    fun incompleteBodyIsATransportDrop() {
        assertEquals(retryable(ErrorCode.CONNECTION_LOST), classifier.classify(http(400, "INCOMPLETE_BODY")))
        state = NetworkState.Offline
        assertEquals(waitFor(ErrorCode.NETWORK_UNAVAILABLE), classifier.classify(http(400, "INCOMPLETE_BODY")))
    }

    @Test
    fun localAndProtocolErrors() {
        assertEquals(fatal(ErrorCode.DISK_FULL), classifier.classify(DiskFullException("full")))
        assertEquals(fatal(ErrorCode.DISK_FULL), classifier.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(fatal(ErrorCode.SOURCE_CHANGED), classifier.classify(SourceChangedException("size")))
        assertEquals(fatal(ErrorCode.SOURCE_MISSING), classifier.classify(SourceMissingException("gone")))
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(PartFileMissingException("gone")))
        assertEquals(fatal(ErrorCode.REMOTE_FILE_CHANGED), classifier.classify(RemoteFileChangedException("200")))
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(ProtocolViolationException("range")))
        assertEquals(retryable(ErrorCode.CHUNK_HASH_MISMATCH), classifier.classify(ChunkHashMismatchException(1, "a", "b")))
    }

    @Test
    fun localErrorsDoNotDependOnConnectivity() {
        state = NetworkState.Offline
        assertEquals(fatal(ErrorCode.DISK_FULL), classifier.classify(DiskFullException("full")))
        assertEquals(fatal(ErrorCode.SOURCE_MISSING), classifier.classify(SourceMissingException("gone")))
        assertEquals(retryable(ErrorCode.SERVER_ERROR), classifier.classify(http(503)))
    }

    @Test
    fun nonIoErrorsAreFatalUnknown() {
        assertEquals(fatal(ErrorCode.UNKNOWN), classifier.classify(IllegalStateException("bug")))
    }

    @Test
    fun cancellationIsRethrownNotClassified() {
        assertThrows(CancellationException::class.java) { classifier.classify(CancellationException("paused")) }
    }

    @Test
    fun ambiguityMarksLostResponseCandidates() {
        assertTrue(retryable(ErrorCode.TIMEOUT).isAmbiguous)
        assertTrue(retryable(ErrorCode.CONNECTION_LOST).isAmbiguous)
        assertFalse(retryable(ErrorCode.SERVER_ERROR).isAmbiguous)
        assertFalse(retryable(ErrorCode.CHUNK_HASH_MISMATCH).isAmbiguous)
        assertFalse(waitFor(ErrorCode.NETWORK_UNAVAILABLE).isAmbiguous)
        assertFalse(waitFor(ErrorCode.METERED_NETWORK).isAmbiguous)
        assertFalse(fatal(ErrorCode.UNKNOWN).isAmbiguous)
    }

    @Test
    fun describeIsHumanReadable() {
        assertEquals("HTTP 503 INJECTED_FAULT: msg", classifier.describe(http(503, "INJECTED_FAULT")))
        assertEquals("SocketTimeoutException: read timed out", classifier.describe(SocketTimeoutException("read timed out")))
    }
}
