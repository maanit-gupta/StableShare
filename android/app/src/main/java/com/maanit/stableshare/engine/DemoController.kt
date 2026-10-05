package com.maanit.stableshare.engine

import android.net.Uri
import android.util.Log
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.data.net.RemoteFile
import com.maanit.stableshare.data.net.ServerHealth
import com.maanit.stableshare.data.net.TransferApi
import com.maanit.stableshare.data.repo.TransferRepository
import com.maanit.stableshare.data.settings.DemoStore
import com.maanit.stableshare.data.settings.ServerProfile
import com.maanit.stableshare.data.settings.Settings
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class DemoKind(val sizeMb: Int) {
    QUICK(20),
    BIG(200),
    DOWNLOAD(0),
}

/** What a running demo is doing before its transfer exists. */
sealed interface DemoPhase {
    /** Writing the test file; [percent] 0–100. */
    data class Making(val percent: Int) : DemoPhase

    /** The hosted server is waking; the demo starts once it answers. */
    data object Waking : DemoPhase

    data object Starting : DemoPhase
}

data class DemoProgress(val kind: DemoKind, val phase: DemoPhase)

/** The server has no non-empty file to download. */
class NoSampleException : Exception("The server has no files")

/**
 * Demo mode: test files are made in the cache dir and handed to [TransferController] exactly like
 * a picked file; downloads use the server's own list. Demo transfer ids live in [DemoStore], and
 * [start] deletes each demo file once its upload is verified or cancelled.
 */
class DemoController(
    private val files: FileStore,
    private val controller: TransferController,
    private val api: TransferApi,
    private val repo: TransferRepository,
    private val store: DemoStore,
    private val settings: suspend () -> Settings,
    private val checkHealth: (ServerProfile, String) -> Flow<ServerHealth>,
) {
    private val _progress = MutableStateFlow<DemoProgress?>(null)

    /** The running demo, or null; one at a time. */
    val progress: StateFlow<DemoProgress?> = _progress.asStateFlow()

    /** Keeps the tags in step with the database and deletes finished demo files. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var swept = false
            combine(repo.observeTransfers(), store.demoIds, ::Pair).collect { (rows, ids) ->
                val byId = rows.associateBy { it.id }
                store.untag(ids.filter { it !in byId })
                rows.filter { it.type == TransferType.UPLOAD && it.state in DONE_STATES && files.isDemoFile(it.localUri) }
                    .forEach { delete(fileOf(it)) }
                // Once, while no demo is being made: leftovers from a process that died mid-generation.
                if (!swept && _progress.value == null) {
                    swept = true
                    val referenced = rows.mapNotNullTo(HashSet()) { fileOf(it)?.canonicalPath }
                    files.demoFiles().filter { it.canonicalPath !in referenced }.forEach(::delete)
                }
            }
        }
    }

    /**
     * Runs one demo and returns its transfer. Returns null if another demo is already running.
     * While the hosted server wakes, it waits rather than failing.
     */
    suspend fun run(kind: DemoKind): TransferEntity? {
        if (!claim(kind)) return null
        try {
            val transfer = if (kind == DemoKind.DOWNLOAD) download() else upload(kind.sizeMb)
            store.tag(transfer.id)
            return transfer
        } finally {
            _progress.value = null
        }
    }

    private suspend fun upload(sizeMb: Int): TransferEntity {
        val total = sizeMb.toLong() * FileStore.MIB
        val file = files.generateDemoFile(sizeMb) { written ->
            set(DemoPhase.Making(((written * 100) / total).toInt()))
        }
        try {
            awaitServer()
            set(DemoPhase.Starting)
            return controller.uploadUri(Uri.fromFile(file))
        } catch (t: Throwable) {
            delete(file)
            throw t
        }
    }

    private suspend fun download(): TransferEntity {
        awaitServer()
        set(DemoPhase.Starting)
        val sample = pickSample(api.listFiles()) ?: throw NoSampleException()
        return controller.download(sample.fileId)
    }

    /**
     * Free tier: if the hosted server is asleep, waits for it (each check allows a minute). Any
     * other answer hands off at once and the transfer behaves as usual.
     */
    private suspend fun awaitServer() {
        val s = settings()
        if (s.serverProfile != ServerProfile.HOSTED) return
        repeat(WAKE_CHECKS) {
            var waking = false
            var result: ServerHealth? = null
            checkHealth(s.serverProfile, s.serverUrl).collect {
                if (it == ServerHealth.Waking) {
                    waking = true
                    set(DemoPhase.Waking)
                } else {
                    result = it
                }
            }
            if (result is ServerHealth.Online || !waking) return
        }
    }

    private fun claim(kind: DemoKind): Boolean {
        val initial = DemoProgress(kind, if (kind == DemoKind.DOWNLOAD) DemoPhase.Starting else DemoPhase.Making(0))
        return _progress.compareAndSet(null, initial)
    }

    private fun set(phase: DemoPhase) {
        _progress.update { it?.copy(phase = phase) }
    }

    private fun fileOf(row: TransferEntity): File? =
        Uri.parse(row.localUri).takeIf { it.scheme == "file" }?.path?.let(::File)

    private fun delete(file: File?) {
        if (file == null || !file.exists()) return
        if (!file.delete()) Log.w(TAG, "could not delete demo file ${file.name}")
    }

    companion object {
        private const val TAG = "StableShare"
        private const val WAKE_CHECKS = 3
        private val DONE_STATES = setOf(TransferState.COMPLETED, TransferState.CANCELLED)
        const val SAMPLE_FILE_ID = "sample-50MB"

        /** sample-50MB when the server has it, else its smallest non-empty file. */
        fun pickSample(files: List<RemoteFile>): RemoteFile? =
            files.find { it.fileId == SAMPLE_FILE_ID && it.size > 0 } ?: files.filter { it.size > 0 }.minByOrNull { it.size }

        /** The Big upload's file name, for its one-time tip. */
        fun isBigUpload(fileName: String): Boolean = fileName.startsWith("demo-${DemoKind.BIG.sizeMb}MB-")
    }
}
