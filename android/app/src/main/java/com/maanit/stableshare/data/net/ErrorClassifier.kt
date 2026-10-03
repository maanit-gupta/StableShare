package com.maanit.stableshare.data.net

import com.maanit.stableshare.data.files.DiskFullException
import com.maanit.stableshare.data.files.PartFileMissingException
import com.maanit.stableshare.data.files.SourceChangedException
import com.maanit.stableshare.data.files.SourceMissingException
import com.maanit.stableshare.data.files.isDiskFull
import com.maanit.stableshare.domain.ErrorCode
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.cancellation.CancellationException

/** What the engine should do about a failure (DESIGN.md §7). */
sealed interface Outcome {
    val code: ErrorCode

    /** Back off and retry; consumes one attempt of the current chunk. */
    data class Retryable(override val code: ErrorCode, val retryAfterMs: Long? = null) : Outcome

    /** No network: RETRYING with NETWORK_UNAVAILABLE, no attempt consumed, resume on connectivity. */
    data object WaitForNetwork : Outcome {
        override val code = ErrorCode.NETWORK_UNAVAILABLE
    }

    /** Stop: FAILED with this code. */
    data class Fatal(override val code: ErrorCode) : Outcome
}

/**
 * True when the request may have taken effect even though we saw a failure (the lost-response
 * case, DESIGN.md §8): the caller must ask GET status before resending a chunk or `complete`.
 */
val Outcome.isAmbiguous: Boolean
    get() = this is Outcome.Retryable && (code == ErrorCode.TIMEOUT || code == ErrorCode.CONNECTION_LOST)

class ErrorClassifier(private val connectivity: ConnectivityChecker) {

    /** Never classifies cancellation: it is rethrown so structured concurrency keeps working. */
    fun classify(error: Throwable): Outcome {
        if (error is CancellationException) throw error
        return when {
            error is DiskFullException || error.isDiskFull() -> Outcome.Fatal(ErrorCode.DISK_FULL)
            error is SourceChangedException -> Outcome.Fatal(ErrorCode.SOURCE_CHANGED)
            error is SourceMissingException -> Outcome.Fatal(ErrorCode.SOURCE_MISSING)
            error is PartFileMissingException -> Outcome.Fatal(ErrorCode.UNKNOWN)
            error is RemoteFileChangedException -> Outcome.Fatal(ErrorCode.REMOTE_FILE_CHANGED)
            error is ProtocolViolationException -> Outcome.Fatal(ErrorCode.UNKNOWN)
            error is ChunkHashMismatchException -> Outcome.Retryable(ErrorCode.CHUNK_HASH_MISMATCH)
            error is HttpStatusException -> classifyHttp(error)
            error is IOException -> classifyTransport(error)
            else -> Outcome.Fatal(ErrorCode.UNKNOWN)
        }
    }

    /** Human-readable text for transfers.errorMessage. */
    fun describe(error: Throwable): String = when (error) {
        is HttpStatusException -> error.message ?: "HTTP ${error.status}"
        else -> listOfNotNull(error::class.simpleName, error.message).joinToString(": ")
    }

    private fun classifyHttp(e: HttpStatusException): Outcome = when (e.status) {
        507 -> Outcome.Fatal(ErrorCode.DISK_FULL)
        in 500..599 -> Outcome.Retryable(ErrorCode.SERVER_ERROR, e.retryAfterMs)
        429 -> Outcome.Retryable(ErrorCode.RATE_LIMITED, e.retryAfterMs)
        404 -> when (e.code) {
            "SESSION_NOT_FOUND" -> Outcome.Fatal(ErrorCode.SESSION_NOT_FOUND)
            "FILE_NOT_FOUND" -> Outcome.Fatal(ErrorCode.REMOTE_FILE_CHANGED)
            else -> Outcome.Fatal(ErrorCode.UNKNOWN)
        }
        // SESSION_CONFLICT, CHUNK_CONFLICT, SESSION_COMPLETED; MISSING_CHUNKS is handled by the
        // pipeline (re-sync, at most twice) before it ever reaches the classifier.
        409 -> Outcome.Fatal(ErrorCode.SESSION_CONFLICT)
        422 -> when (e.code) {
            "CHUNK_HASH_MISMATCH" -> Outcome.Retryable(ErrorCode.CHUNK_HASH_MISMATCH)
            "FILE_HASH_MISMATCH" -> Outcome.Fatal(ErrorCode.FILE_HASH_MISMATCH)
            else -> Outcome.Fatal(ErrorCode.UNKNOWN)
        }
        // A range we computed from the manifest is no longer satisfiable: the file shrank.
        416 -> Outcome.Fatal(ErrorCode.REMOTE_FILE_CHANGED)
        // The server saw our body end early: a transport drop, not a client bug.
        400 -> if (e.code == "INCOMPLETE_BODY") classifyTransport(e) else Outcome.Fatal(ErrorCode.UNKNOWN)
        else -> Outcome.Fatal(ErrorCode.UNKNOWN) // 413 and other 4xx: a client bug, retrying cannot help
    }

    private fun classifyTransport(e: IOException): Outcome = when {
        !connectivity.isNetworkAvailable() -> Outcome.WaitForNetwork
        e is InterruptedIOException -> Outcome.Retryable(ErrorCode.TIMEOUT) // includes SocketTimeoutException
        else -> Outcome.Retryable(ErrorCode.CONNECTION_LOST)
    }
}
