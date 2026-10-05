package com.maanit.stableshare.data.net

import com.maanit.stableshare.data.settings.ServerProfile
import com.maanit.stableshare.data.settings.ServerProfiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ServerHealth {
    data class Online(val latencyMs: Long, val serverVersion: String?) : ServerHealth

    /** HOSTED only: no answer within [ServerHealthChecker.WAKING_AFTER_MS], request still pending. */
    data object Waking : ServerHealth

    data class Unreachable(val reason: UnreachableReason) : ServerHealth

    /** The URL failed normalisation; nothing was sent. */
    data object Invalid : ServerHealth
}

sealed interface UnreachableReason {
    data object NoInternet : UnreachableReason
    data object DnsFailure : UnreachableReason
    data object Timeout : UnreachableReason

    /** The network security config refused http:// to this host. */
    data object CleartextBlocked : UnreachableReason

    data class HttpError(val status: Int) : UnreachableReason

    /** Any other transport failure, such as a refused connection (no server on that port). */
    data class ConnectionFailed(val message: String?) : UnreachableReason
}

/**
 * Asks a server's `GET /health` whether it is up. The hosted server sleeps when idle and can take
 * close to a minute to wake, so HOSTED waits 60 s and reports [ServerHealth.Waking] after 3 s;
 * every other profile gives up after 5 s.
 */
class ServerHealthChecker(
    client: OkHttpClient,
    private val isOnline: () -> Boolean,
    private val json: Json = ProtocolClient.DefaultJson,
) {
    private val hostedClient = client.withTimeout(HOSTED_TIMEOUT_MS)
    private val localClient = client.withTimeout(LOCAL_TIMEOUT_MS)

    /** Emits [ServerHealth.Waking] at most once (HOSTED), then exactly one final result, then completes. */
    fun check(profile: ServerProfile, url: String): Flow<ServerHealth> = channelFlow {
        val base = ServerProfiles.normalizeServerUrl(url)
        if (base == null) {
            send(ServerHealth.Invalid)
            return@channelFlow
        }
        if (!isOnline()) {
            send(ServerHealth.Unreachable(UnreachableReason.NoInternet))
            return@channelFlow
        }
        val hosted = profile == ServerProfile.HOSTED
        val waking = if (hosted) launch { delay(WAKING_AFTER_MS); send(ServerHealth.Waking) } else null
        val result = probe(if (hosted) hostedClient else localClient, base)
        waking?.cancel()
        send(result)
    }

    private suspend fun probe(client: OkHttpClient, base: String): ServerHealth {
        val request = Request.Builder()
            .url(base.toHttpUrl().newBuilder().addPathSegment("health").build())
            .get()
            .build()
        val started = System.nanoTime()
        return try {
            val (status, body) = client.newCall(request).await()
            if (status !in 200..299) return ServerHealth.Unreachable(UnreachableReason.HttpError(status))
            ServerHealth.Online(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), versionOf(body))
        } catch (e: IOException) {
            ServerHealth.Unreachable(reasonFor(e))
        }
    }

    private fun reasonFor(e: IOException): UnreachableReason = when {
        e is UnknownServiceException && e.message.orEmpty().contains("CLEARTEXT", ignoreCase = true) ->
            UnreachableReason.CleartextBlocked
        e is UnknownHostException -> if (isOnline()) UnreachableReason.DnsFailure else UnreachableReason.NoInternet
        // SocketTimeoutException, and the call timeout's InterruptedIOException("timeout").
        e is InterruptedIOException -> UnreachableReason.Timeout
        !isOnline() -> UnreachableReason.NoInternet
        else -> UnreachableReason.ConnectionFailed(e.message)
    }

    /** The server's `version` field if it sends one; today's `/health` answers only `{ ok: true }`. */
    private fun versionOf(body: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** Status and (small) body; cancelling the coroutine cancels the Call. */
    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { it.code to it.peekBody(MAX_BODY).string() } }
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }

    companion object {
        const val WAKING_AFTER_MS = 3_000L
        const val HOSTED_TIMEOUT_MS = 60_000L
        const val LOCAL_TIMEOUT_MS = 5_000L
        private const val MAX_BODY = 4L * 1024

        private fun OkHttpClient.withTimeout(ms: Long): OkHttpClient = newBuilder()
            .callTimeout(ms, TimeUnit.MILLISECONDS)
            .connectTimeout(ms, TimeUnit.MILLISECONDS)
            .readTimeout(ms, TimeUnit.MILLISECONDS)
            .build()
    }
}
