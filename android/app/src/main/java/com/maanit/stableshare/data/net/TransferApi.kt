package com.maanit.stableshare.data.net

/**
 * The protocol operations the transfer pipelines use (DESIGN.md §3). [ProtocolClient] is the
 * OkHttp implementation; tests substitute an in-memory server. Every call is cancellable and
 * reports failures as exceptions for [ErrorClassifier].
 */
interface TransferApi {
    suspend fun createSession(uploadId: String, request: CreateSessionRequest): CreateSessionResponse

    suspend fun uploadChunk(
        uploadId: String,
        index: Int,
        bytes: ByteArray,
        sha256: String,
        onProgress: (Long) -> Unit = {},
    ): ChunkUploadResponse

    suspend fun getUploadStatus(uploadId: String): UploadStatus

    suspend fun completeUpload(uploadId: String): CompleteResponse

    suspend fun deleteUpload(uploadId: String)

    suspend fun listFiles(): List<RemoteFile>

    suspend fun getManifest(fileId: String, chunkSize: Int? = null): Manifest

    suspend fun downloadRange(
        fileId: String,
        offset: Long,
        length: Int,
        etag: String,
        onProgress: (Long) -> Unit = {},
    ): RangeBody
}
