package com.maanit.stableshare.engine

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.maanit.stableshare.data.db.AppDatabase
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.EventType
import com.maanit.stableshare.domain.RetryPolicy
import com.maanit.stableshare.domain.TransferState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The network as the engine, the guard and the classifier see it: a [state] plus the Wi-Fi only
 * setting, with [usableNetwork] recomputed synchronously on every change.
 */
class FakeConnectivity(state: NetworkState = NetworkState.Unmetered, wifiOnly: Boolean = false) : ConnectivityMonitor {
    override val networkState = MutableStateFlow(state)
    override val usableNetwork = MutableStateFlow(NetworkPolicy.usable(state, wifiOnly))

    var state: NetworkState
        get() = networkState.value
        set(value) {
            networkState.value = value
            recompute()
        }

    var wifiOnly: Boolean = wifiOnly
        set(value) {
            field = value
            recompute()
        }

    /** Offline/online shorthand: online means an unmetered network. */
    var online: Boolean
        get() = state != NetworkState.Offline
        set(value) {
            state = if (value) NetworkState.Unmetered else NetworkState.Offline
        }

    override fun blockReason(): ErrorCode? = NetworkPolicy.blockReason(networkState.value, usableNetwork.value)

    private fun recompute() {
        usableNetwork.value = NetworkPolicy.usable(networkState.value, wifiOnly)
    }
}

/** Disk-full simulation for [FileStore.writeChunkAt], plus hooks that see chunk buffers come and go. */
class FullDiskFileStore(context: Context, downloads: File, generated: File, io: CoroutineDispatcher = Dispatchers.IO) :
    FileStore(context, downloads, generated, io) {
    @Volatile var full = false

    /** Runs after an upload chunk was read into memory. */
    @Volatile var onChunkRead: () -> Unit = {}

    /** Runs after a download chunk's write ended (written and synced, or failed). */
    @Volatile var onChunkWritten: () -> Unit = {}

    override suspend fun writeChunkAt(part: File, offset: Long, bytes: ByteArray) {
        try {
            if (full) throw java.io.IOException("write failed: ENOSPC (No space left on device)")
            super.writeChunkAt(part, offset, bytes)
        } finally {
            onChunkWritten()
        }
    }

    override suspend fun readChunk(uri: Uri, offset: Long, length: Int, expectedSize: Long, expectedLastModified: Long?) =
        super.readChunk(uri, offset, length, expectedSize, expectedLastModified).also { onChunkRead() }
}

/**
 * Chunk buffers alive at once: an upload chunk from its read until its request ends, a download
 * chunk from its response until its write ends. Valid for runs without faults that drop a buffer
 * on another path (a corrupt body is never written).
 */
class BufferMeter(h: EngineHarness) {
    private val live = AtomicInteger()
    val peak = AtomicInteger()

    init {
        h.files.onChunkRead = ::acquire
        h.files.onChunkWritten = { live.decrementAndGet() }
        h.server.onChunkBody = { op, ended -> if (op == FakeTransferServer.Op.CHUNK && ended) live.decrementAndGet() else acquire() }
    }

    private fun acquire() {
        peak.accumulateAndGet(live.incrementAndGet(), ::maxOf)
    }
}

/**
 * One "process": a database (shared or its own), the real engine, pipelines, repository,
 * controller and file store, over a [FakeTransferServer] and [FakeConnectivity]. [clock] is the
 * test's virtual clock when run under runTest.
 */
class EngineHarness(
    val context: Context,
    val dir: File,
    val clock: () -> Long,
    val server: FakeTransferServer = FakeTransferServer(),
    val net: FakeConnectivity = FakeConnectivity(),
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(),
    settings: Settings = Settings(maxConcurrent = 2, uploadChunkSizeBytes = CHUNK),
    sleep: (suspend (Long) -> Unit)? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val settings = MutableStateFlow(settings)
    val repo = TransferRepository(db, clock)
    val files = FullDiskFileStore(context, File(dir, "downloads").apply { mkdirs() }, File(dir, "generated").apply { mkdirs() }, io)
    val tracker = TransferProgressTracker(clock)
    val wakeups = CopyOnWriteArrayList<WakeupPlan?>()
    val guard = NetworkGuard(net)

    /** What the pipelines talk to: the fake server behind the network guard. */
    val pipelineApi = GuardedTransferApi(server, guard)
    val ensureRunningCalls = CopyOnWriteArrayList<Long>()
    val env = PipelineEnv(
        repo = repo,
        classifier = ErrorClassifier(net),
        retryPolicy = RetryPolicy(random = Random(7)),
        settings = { this.settings.value },
        tracker = tracker,
        network = guard,
        clock = clock,
        sleep = sleep ?: { delay(it) },
    )
    val engine = TransferEngine(
        repo = repo,
        settings = this.settings,
        connectivity = net,
        upload = UploadPipeline(pipelineApi, files, env),
        download = DownloadPipeline(pipelineApi, files, env),
        tracker = tracker,
        wakeups = { wakeups += it },
        clock = clock,
    )
    val scheduler = TransferScheduler { ensureRunningCalls += clock() }
    val controller = TransferController(repo, server, files, { this.settings.value }, scheduler, engine)

    init {
        server.downloadChunkSize = settings.uploadChunkSizeBytes
        net.wifiOnly = settings.wifiOnly
    }

    /** Flips the Wi-Fi only setting, as the Settings switch does (DataStore and the monitor's view). */
    fun setWifiOnly(on: Boolean) {
        settings.value = settings.value.copy(wifiOnly = on)
        net.wifiOnly = on
    }

    /**
     * A source file of [size] random bytes, queued for upload through the controller. Same seed and
     * size = same bytes; pass a distinct [fileName] to get an identical copy without touching
     * (and so changing the mtime of) a file another upload is still reading.
     */
    suspend fun upload(size: Int, seed: Int = size, fileName: String = "src-$seed-$size.bin"): Pair<TransferEntity, ByteArray> {
        val bytes = Random(seed).nextBytes(size)
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        return controller.uploadUri(Uri.fromFile(file)) to bytes
    }

    /** A server file of [size] random bytes, queued for download through the controller. */
    suspend fun download(size: Int, seed: Int = size, fileId: String = "file-$seed"): Pair<TransferEntity, ByteArray> {
        val bytes = Random(seed).nextBytes(size)
        server.addFile(fileId, bytes)
        return controller.download(fileId) to bytes
    }

    /**
     * Holds the first request for chunk [index] of [op] in flight until its coroutine is cancelled
     * (pause, cancel, stop). The returned deferred completes once the request is being held.
     */
    fun holdAt(op: FakeTransferServer.Op, index: Int): CompletableDeferred<Unit> {
        val reached = CompletableDeferred<Unit>()
        server.onRequest = { c ->
            if (c.op == op && c.index == index && c.attempt == 1) {
                reached.complete(Unit)
                awaitCancellation()
            }
        }
        return reached
    }

    /**
     * Like [holdAt], but the held request carries on once [release] completes (if it is still
     * running by then).
     */
    fun holdUntil(op: FakeTransferServer.Op, index: Int, release: CompletableDeferred<Unit>): CompletableDeferred<Unit> {
        val reached = CompletableDeferred<Unit>()
        server.onRequest = { c ->
            if (c.op == op && c.index == index && c.attempt == 1) {
                reached.complete(Unit)
                release.await()
            }
        }
        return reached
    }

    /** Corrupts one byte of chunk [index] in a download's part file on disk. */
    suspend fun corruptOnDisk(id: String, index: Int) {
        val chunk = repo.getChunks(id)[index]
        RandomAccessFile(localFile(id), "rw").use { raf ->
            raf.seek(chunk.offset)
            val b = raf.read()
            raf.seek(chunk.offset)
            raf.write(b xor 0xFF)
        }
    }

    suspend fun row(id: String): TransferEntity = repo.getTransfer(id)!!
    suspend fun state(id: String): TransferState = row(id).state
    suspend fun events(id: String) = repo.getEvents(id)
    suspend fun eventTypes(id: String): List<EventType> = events(id).map { it.type }

    /** Suspends until the row satisfies [predicate] (Room flow, no polling). */
    suspend fun awaitRow(id: String, predicate: (TransferEntity) -> Boolean): TransferEntity =
        repo.observeTransfer(id).mapNotNull { it }.first(predicate)

    /** The file a finished download was saved to. */
    suspend fun localFile(id: String): File = File(Uri.parse(row(id).localUri).path!!)

    fun close() = db.close()

    companion object {
        const val CHUNK = 1024
    }
}
