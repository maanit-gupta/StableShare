package com.maanit.stableshare.engine

import com.maanit.stableshare.data.net.ChunkUploadResponse
import com.maanit.stableshare.data.net.CompleteResponse
import com.maanit.stableshare.data.net.CreateSessionRequest
import com.maanit.stableshare.data.net.CreateSessionResponse
import com.maanit.stableshare.data.net.Manifest
import com.maanit.stableshare.data.net.NetworkUnusableException
import com.maanit.stableshare.data.net.RangeBody
import com.maanit.stableshare.data.net.RemoteFile
import com.maanit.stableshare.data.net.TransferApi
import com.maanit.stableshare.data.net.UploadStatus
import com.maanit.stableshare.domain.ErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the engine's requests off networks they may not use (offline, or metered with Wi-Fi only
 * on; DESIGN.md §7). Requests on a metered network succeed, so classifying failures is not
 * enough: a request waits up to [debounceMs] for a usable network before it starts, and is
 * cancelled — its OkHttp Call with it — once the network has been unusable for [debounceMs], so a
 * Wi-Fi roaming blip interrupts nothing. Both throw [NetworkUnusableException], which the
 * classifier turns into WaitForNetwork (RETRYING, no attempt consumed, the job ends).
 */
class NetworkGuard(
    private val connectivity: ConnectivityMonitor,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    suspend fun <T> guard(request: suspend () -> T): T {
        if (!connectivity.usableNetwork.value) {
            withTimeoutOrNull(debounceMs) { connectivity.usableNetwork.first { it } }
                ?: throw NetworkUnusableException(reason())
        }
        return coroutineScope {
            val call = async { request() }
            val stop = async { awaitUnusable() }
            select {
                call.onAwait { result ->
                    stop.cancel()
                    result
                }
                stop.onAwait { code ->
                    call.cancelAndJoin()
                    throw NetworkUnusableException(code)
                }
            }
        }
    }

    /**
     * A backoff wait: [sleep]s [ms], or returns early with the block reason once the network has
     * been unusable for [debounceMs] (so the job can end and free its slot). Null = slept fully.
     */
    suspend fun sleep(ms: Long, sleep: suspend (Long) -> Unit): ErrorCode? = coroutineScope {
        val nap = async { sleep(ms) }
        val stop = async { awaitUnusable() }
        select {
            nap.onAwait {
                stop.cancel()
                null
            }
            stop.onAwait { code ->
                nap.cancel()
                code
            }
        }
    }

    /** Suspends until the network has been unusable for [debounceMs] in one stretch. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitUnusable(): ErrorCode {
        connectivity.usableNetwork
            .transformLatest { usable ->
                if (!usable) {
                    delay(debounceMs)
                    emit(Unit)
                }
            }
            .first()
        return reason()
    }

    private fun reason(): ErrorCode =
        NetworkPolicy.blockReason(connectivity.networkState.value, usable = false) ?: ErrorCode.NETWORK_UNAVAILABLE

    companion object {
        /** How long the network must stay unusable before a running request is stopped. */
        const val DEBOUNCE_MS = 1_500L
    }
}

/** The pipelines' [TransferApi]: every call goes through [NetworkGuard]. */
class GuardedTransferApi(private val api: TransferApi, private val guard: NetworkGuard) : TransferApi {
    override suspend fun createSession(uploadId: String, request: CreateSessionRequest): CreateSessionResponse =
        guard.guard { api.createSession(uploadId, request) }

    override suspend fun uploadChunk(
        uploadId: String,
        index: Int,
        bytes: ByteArray,
        sha256: String,
        onProgress: (Long) -> Unit,
    ): ChunkUploadResponse = guard.guard { api.uploadChunk(uploadId, index, bytes, sha256, onProgress) }

    override suspend fun getUploadStatus(uploadId: String): UploadStatus = guard.guard { api.getUploadStatus(uploadId) }

    override suspend fun completeUpload(uploadId: String): CompleteResponse = guard.guard { api.completeUpload(uploadId) }

    override suspend fun deleteUpload(uploadId: String) = guard.guard { api.deleteUpload(uploadId) }

    override suspend fun listFiles(): List<RemoteFile> = guard.guard { api.listFiles() }

    override suspend fun getManifest(fileId: String, chunkSize: Int?): Manifest = guard.guard { api.getManifest(fileId, chunkSize) }

    override suspend fun downloadRange(
        fileId: String,
        offset: Long,
        length: Int,
        etag: String,
        onProgress: (Long) -> Unit,
    ): RangeBody = guard.guard { api.downloadRange(fileId, offset, length, etag, onProgress) }
}
