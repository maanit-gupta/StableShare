package com.maanit.stableshare.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** The upload source no longer matches the size/mtime recorded when the transfer was created. */
class SourceChangedException(message: String) : IOException(message)

/** The upload source is gone, or its permission was revoked. */
class SourceMissingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A download's .part file disappeared mid-transfer. */
class PartFileMissingException(message: String) : IOException(message)

/** Local storage is full (ENOSPC). */
class DiskFullException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class SourceInfo(val name: String, val size: Long, val mimeType: String?, val lastModified: Long?)

class ChunkData(val bytes: ByteArray, val sha256: String)

/**
 * All local file I/O. Upload sources are read through positioned channels and re-checked for
 * size/mtime before every chunk. Download bytes are written to a per-transfer .part file and
 * fsynced before the caller records them (rule 2). Every loop calls ensureActive() so pause and
 * cancel stop work promptly. Open only so tests can simulate a full disk.
 */
open class FileStore(
    private val context: Context,
    private val downloadsDir: File = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "downloads"),
    private val generatedDir: File = context.getExternalFilesDir("generated")
        ?: File(context.filesDir, "generated"),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    // ---- upload sources ----

    suspend fun querySource(uri: Uri): SourceInfo = withContext(io) {
        if (uri.scheme == "file") {
            val file = fileOf(uri)
            if (!file.isFile) throw SourceMissingException("Source file not found: ${file.path}")
            SourceInfo(file.name, file.length(), guessMime(file.name), file.lastModified().takeIf { it > 0 })
        } else {
            val doc = try {
                DocumentFile.fromSingleUri(context, uri)
            } catch (e: SecurityException) {
                throw SourceMissingException("No permission to read $uri", e)
            }
            if (doc == null || !doc.exists()) throw SourceMissingException("Source not found: $uri")
            SourceInfo(
                name = doc.name ?: uri.lastPathSegment ?: "file",
                size = doc.length(),
                mimeType = doc.type,
                lastModified = doc.lastModified().takeIf { it > 0 },
            )
        }
    }

    /** Streaming SHA-256 with a 1 MiB buffer; [onProgress] receives bytes hashed so far. */
    suspend fun sha256(uri: Uri, onProgress: (Long) -> Unit = {}): String = withContext(io) {
        openSourceStream(uri).use { hashStream(it, onProgress) }
    }

    suspend fun sha256(file: File, onProgress: (Long) -> Unit = {}): String = withContext(io) {
        FileInputStream(file).use { hashStream(it, onProgress) }
    }

    /**
     * Reads [length] bytes at [offset] and hashes them. Throws [SourceChangedException] if the
     * source's size (or a known mtime) differs from what was recorded, or it is shorter than
     * expected, and [SourceMissingException] if it cannot be opened.
     */
    suspend fun readChunk(
        uri: Uri,
        offset: Long,
        length: Int,
        expectedSize: Long,
        expectedLastModified: Long?,
    ): ChunkData = withContext(io) {
        val info = querySource(uri)
        if (info.size != expectedSize) {
            throw SourceChangedException("Source size changed: expected $expectedSize, now ${info.size}")
        }
        if (expectedLastModified != null && info.lastModified != null && info.lastModified != expectedLastModified) {
            throw SourceChangedException("Source modified at ${info.lastModified}, expected $expectedLastModified")
        }
        val buffer = ByteBuffer.allocate(length)
        openSourceChannel(uri).use { channel ->
            var position = offset
            while (buffer.hasRemaining()) {
                coroutineContext.ensureActive()
                val n = channel.read(buffer, position)
                if (n < 0) throw SourceChangedException("Source ended at $position, expected ${offset + length}")
                position += n
            }
        }
        val bytes = buffer.array()
        ChunkData(bytes, sha256Hex(bytes))
    }

    private fun openSourceStream(uri: Uri): InputStream = try {
        if (uri.scheme == "file") {
            FileInputStream(fileOf(uri))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw SourceMissingException("Cannot open $uri")
        }
    } catch (e: FileNotFoundException) {
        throw SourceMissingException("Source not found: $uri", e)
    } catch (e: SecurityException) {
        throw SourceMissingException("No permission to read $uri", e)
    }

    private fun openSourceChannel(uri: Uri): FileChannel = try {
        if (uri.scheme == "file") {
            RandomAccessFile(fileOf(uri), "r").channel
        } else {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw SourceMissingException("Cannot open $uri")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
        }
    } catch (e: FileNotFoundException) {
        throw SourceMissingException("Source not found: $uri", e)
    } catch (e: SecurityException) {
        throw SourceMissingException("No permission to read $uri", e)
    }

    /** Keeps read access to a SAF-picked file across restarts. Returns false if not grantable. */
    fun takePersistableReadPermission(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    fun releasePersistableReadPermission(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Never held, or already released.
        }
    }

    // ---- download part files ----

    /** One part file per transfer, so two downloads of the same file never share one. */
    fun partFileFor(transferId: String, fileName: String): File =
        File(downloadsDir, "${safeName(fileName)}.$transferId.part")

    /** Creates (or keeps) the part file at exactly [size] bytes. Existing bytes are preserved. */
    suspend fun createPartFile(transferId: String, fileName: String, size: Long): File = withContext(io) {
        val part = partFileFor(transferId, fileName)
        diskOp {
            downloadsDir.mkdirs()
            RandomAccessFile(part, "rw").use { it.setLength(size) }
        }
        part
    }

    fun partFileValid(part: File, size: Long): Boolean = part.isFile && part.length() == size

    /** Positional write followed by fsync; returns only once the bytes are durable. */
    open suspend fun writeChunkAt(part: File, offset: Long, bytes: ByteArray) = withContext(io) {
        if (!part.isFile) throw PartFileMissingException("Part file missing: ${part.name}")
        diskOp {
            RandomAccessFile(part, "rw").use { raf ->
                val buffer = ByteBuffer.wrap(bytes)
                var position = offset
                while (buffer.hasRemaining()) {
                    position += raf.channel.write(buffer, position)
                }
                raf.fd.sync()
            }
        }
    }

    /** Re-hashes one chunk on disk; false if it is missing, short or different. */
    suspend fun verifyChunkOnDisk(part: File, offset: Long, length: Int, sha256: String): Boolean =
        withContext(io) {
            if (!part.isFile || part.length() < offset + length) return@withContext false
            val buffer = ByteBuffer.allocate(length)
            RandomAccessFile(part, "r").use { raf ->
                var position = offset
                while (buffer.hasRemaining()) {
                    coroutineContext.ensureActive()
                    val n = raf.channel.read(buffer, position)
                    if (n < 0) return@withContext false
                    position += n
                }
            }
            sha256Hex(buffer.array()).equals(sha256, ignoreCase = true)
        }

    /**
     * Renames a verified part file to its final name, never overwriting: "name.ext", then
     * "name (1).ext", "name (2).ext", …
     */
    suspend fun finalizePart(part: File, desiredName: String): File = withContext(io) {
        val name = safeName(desiredName)
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val base = if (dot != null) name.substring(0, dot) else name
        val ext = if (dot != null) name.substring(dot) else ""
        var n = 0
        while (true) {
            val candidate = File(part.parentFile, if (n == 0) name else "$base ($n)$ext")
            if (!candidate.exists()) {
                try {
                    diskOp { Files.move(part.toPath(), candidate.toPath()) }
                    return@withContext candidate
                } catch (_: FileAlreadyExistsException) {
                    // Lost a race for this name; try the next one.
                }
            }
            n++
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    fun deletePart(part: File): Boolean = !part.exists() || part.delete()

    open fun availableBytes(): Long = downloadsDir.apply { mkdirs() }.usableSpace

    // ---- generated test files ----

    /** Writes [sizeMb] MiB of pseudo-random bytes; a cancelled or failed run leaves no file. */
    suspend fun generateTestFile(
        sizeMb: Int,
        seed: Long = System.nanoTime(),
        onProgress: (Long) -> Unit = {},
    ): File = withContext(io) {
        require(sizeMb > 0) { "sizeMb must be positive" }
        generatedDir.mkdirs()
        val file = File(generatedDir, "test-${sizeMb}MB-${System.currentTimeMillis()}.bin")
        val random = Random(seed)
        val buffer = ByteArray(MIB)
        try {
            diskOp {
                FileOutputStream(file).use { out ->
                    for (i in 1..sizeMb) {
                        coroutineContext.ensureActive()
                        random.nextBytes(buffer)
                        out.write(buffer)
                        onProgress(i.toLong() * MIB)
                    }
                    out.fd.sync()
                }
            }
            file
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    // ---- helpers ----

    private suspend fun hashStream(input: InputStream, onProgress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(MIB)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
            onProgress(total)
        }
        return digest.digest().toHex()
    }

    private fun fileOf(uri: Uri): File = File(requireNotNull(uri.path) { "file URI without a path: $uri" })

    private fun guessMime(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    companion object {
        const val MIB = 1024 * 1024

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        /** Strips path separators and control characters from a server-supplied name. */
        fun safeName(name: String): String {
            val cleaned = name.replace(Regex("[/\\\\\\u0000-\\u001f]"), "_").trim().trimStart('.')
            return cleaned.ifEmpty { "file" }.take(200)
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** ENOSPC (28 on Linux/Android), as an ErrnoException or in an IOException message. */
fun Throwable.isDiskFull(): Boolean = generateSequence(this) { it.cause }.any { t ->
    (t is ErrnoException && t.errno == ENOSPC) ||
        t.message?.let { "ENOSPC" in it || "No space left" in it } == true
}

private const val ENOSPC = 28

private inline fun <T> diskOp(block: () -> T): T = try {
    block()
} catch (e: IOException) {
    if (e !is DiskFullException && e.isDiskFull()) throw DiskFullException("Device storage is full", e)
    throw e
}
