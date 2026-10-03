package com.maanit.stableshare.data.net

import java.io.IOException

/** A non-2xx response with the server's `{error, message, …}` body decoded where possible. */
class HttpStatusException(
    val status: Int,
    /** Server error code, e.g. SESSION_NOT_FOUND; null if the body was not the JSON error shape. */
    val code: String?,
    val serverMessage: String?,
    /** 409 MISSING_CHUNKS only. */
    val missing: List<Int>? = null,
    /** Retry-After header in milliseconds, if present. */
    val retryAfterMs: Long? = null,
) : IOException("HTTP $status${code?.let { " $it" } ?: ""}${serverMessage?.let { ": $it" } ?: ""}")

/** A Range request with If-Range came back 200 (full body): the remote file changed. */
class RemoteFileChangedException(message: String) : IOException(message)

/** The server answered, but not as the protocol requires (bad Content-Range, undecodable JSON). */
class ProtocolViolationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A downloaded chunk's bytes do not hash to the manifest value (corrupted in transit). */
class ChunkHashMismatchException(val index: Int, expected: String, actual: String) :
    IOException("Chunk $index hash mismatch: expected $expected, got $actual")
