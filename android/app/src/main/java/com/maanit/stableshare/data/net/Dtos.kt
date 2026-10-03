package com.maanit.stableshare.data.net

import kotlinx.serialization.Serializable

// Field-for-field mirrors of the server JSON (DESIGN.md §3, server/src/routes/*.js).

@Serializable
data class CreateSessionRequest(
    val fileName: String,
    val fileSize: Long,
    val chunkSize: Int,
    val sha256: String,
)

@Serializable
data class CreateSessionResponse(
    val uploadId: String,
    val totalChunks: Int,
    val chunkSize: Int,
    val receivedChunks: List<Int>,
    val state: String,
)

@Serializable
data class ChunkUploadResponse(
    val uploadId: String,
    val index: Int,
    /** "stored" or "already_received". */
    val status: String,
    val sha256: String,
) {
    val alreadyReceived: Boolean get() = status == STATUS_ALREADY_RECEIVED

    companion object {
        const val STATUS_STORED = "stored"
        const val STATUS_ALREADY_RECEIVED = "already_received"
    }
}

@Serializable
data class UploadStatus(
    val uploadId: String,
    val fileName: String,
    val fileSize: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val receivedChunks: List<Int>,
    /** "UPLOADING" or "COMPLETED". */
    val state: String,
    /** Present only once COMPLETED: the hash the server verified. */
    val sha256: String? = null,
) {
    val isCompleted: Boolean get() = state == SERVER_STATE_COMPLETED
}

const val SERVER_STATE_COMPLETED = "COMPLETED"

@Serializable
data class CompleteResponse(
    val uploadId: String,
    val state: String,
    val sha256: String,
    val size: Long,
)

@Serializable
data class RemoteFile(
    val fileId: String,
    val name: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class ManifestChunk(
    val index: Int,
    val offset: Long,
    val length: Int,
    val sha256: String,
)

@Serializable
data class Manifest(
    val fileId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    /** Strong, quoted ETag, sent back verbatim as If-Range. */
    val etag: String,
    val chunkSize: Int,
    val chunks: List<ManifestChunk>,
)

@Serializable
data class ErrorBody(
    val error: String,
    val message: String? = null,
    /** 409 MISSING_CHUNKS only. */
    val missing: List<Int>? = null,
)

@Serializable
data class Health(val ok: Boolean)

/**
 * The mock server's fault configuration (DESIGN.md §3.3), minus the PRNG seed, which the app
 * never changes. Every field is required, so a PUT always sends the whole set.
 */
@Serializable
data class FaultSettings(
    val enabled: Boolean,
    val latencyMs: Int,
    val latencyJitterMs: Int,
    val bandwidthKbps: Int,
    val errorRate: Double,
    val timeoutRate: Double,
    val dropMidBodyRate: Double,
    val dropAfterProcessRate: Double,
    val corruptRate: Double,
)

@Serializable
data class FaultCounts(
    val latency: Long = 0,
    val error: Long = 0,
    val timeout: Long = 0,
    val dropMidBody: Long = 0,
    val dropAfterProcess: Long = 0,
    val corrupt: Long = 0,
)

/** GET /admin/stats. */
@Serializable
data class ServerStats(
    val requests: Long = 0,
    val apiRequests: Long = 0,
    val faults: FaultCounts = FaultCounts(),
    val dedupedChunks: Long = 0,
)
