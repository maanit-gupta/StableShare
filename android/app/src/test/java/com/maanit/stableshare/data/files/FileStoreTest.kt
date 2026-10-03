package com.maanit.stableshare.data.files

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: FileStore
    private lateinit var downloads: File
    private lateinit var generated: File

    @Before
    fun setUp() {
        downloads = tmp.newFolder("downloads")
        generated = tmp.newFolder("generated")
        store = FileStore(ApplicationProvider.getApplicationContext(), downloads, generated, Dispatchers.IO)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sourceFile(size: Int, seed: Int = 1): Pair<File, ByteArray> {
        val bytes = Random(seed).nextBytes(size)
        val f = tmp.newFile("src-$seed-$size.bin").apply { writeBytes(bytes) }
        return f to bytes
    }

    // ---- hashing ----

    @Test
    fun sha256OfKnownVectors() = runBlocking {
        val empty = tmp.newFile("empty")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", store.sha256(empty))
        val abc = tmp.newFile("abc").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", store.sha256(Uri.fromFile(abc)))
    }

    @Test
    fun sha256StreamsLargerThanBufferAndReportsProgress() = runBlocking {
        val (file, bytes) = sourceFile(FileStore.MIB * 2 + 17)
        val progress = mutableListOf<Long>()
        assertEquals(sha(bytes), store.sha256(file) { progress += it })
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.size >= 3)
    }

    // ---- source reads ----

    @Test
    fun querySourceReportsSizeNameAndMtime() = runBlocking {
        val (file, bytes) = sourceFile(1234)
        val info = store.querySource(Uri.fromFile(file))
        assertEquals(file.name, info.name)
        assertEquals(bytes.size.toLong(), info.size)
        assertEquals(file.lastModified(), info.lastModified)
    }

    @Test
    fun readChunkAtOffsetIncludingShortLastChunk() = runBlocking {
        val (file, bytes) = sourceFile(10_000)
        val uri = Uri.fromFile(file)
        val mtime = file.lastModified()
        val middle = store.readChunk(uri, 4_096, 4_096, 10_000, mtime)
        assertArrayEquals(bytes.copyOfRange(4_096, 8_192), middle.bytes)
        assertEquals(sha(bytes.copyOfRange(4_096, 8_192)), middle.sha256)
        val last = store.readChunk(uri, 8_192, 1_808, 10_000, mtime)
        assertArrayEquals(bytes.copyOfRange(8_192, 10_000), last.bytes)
        assertEquals(sha(last.bytes), last.sha256)
    }

    @Test
    fun readChunkDetectsChangedSource() = runBlocking {
        val (file, _) = sourceFile(10_000)
        val uri = Uri.fromFile(file)
        val mtime = file.lastModified()
        // Size changed.
        assertThrows(SourceChangedException::class.java) {
            runBlocking { store.readChunk(uri, 0, 100, 9_999, mtime) }
        }
        // Same size, different mtime.
        file.setLastModified(mtime - 60_000)
        assertThrows(SourceChangedException::class.java) {
            runBlocking { store.readChunk(uri, 0, 100, 10_000, mtime) }
        }
        // Unknown recorded mtime: only size is checked.
        assertEquals(100, store.readChunk(uri, 0, 100, 10_000, null).bytes.size)
    }

    @Test
    fun readChunkDetectsMissingSource() {
        val (file, _) = sourceFile(100)
        file.delete()
        assertThrows(SourceMissingException::class.java) {
            runBlocking { store.readChunk(Uri.fromFile(file), 0, 10, 100, null) }
        }
        assertThrows(SourceMissingException::class.java) {
            runBlocking { store.querySource(Uri.fromFile(file)) }
        }
    }

    // ---- part files ----

    @Test
    fun partFileWriteThenVerify() = runBlocking {
        val part = store.createPartFile("t-1", "movie.bin", 3_000)
        assertTrue(store.partFileValid(part, 3_000))
        assertTrue(part.name.contains("t-1") && part.name.endsWith(".part"))

        val chunk1 = Random(9).nextBytes(1_000)
        store.writeChunkAt(part, 1_000, chunk1)
        assertTrue(store.verifyChunkOnDisk(part, 1_000, 1_000, sha(chunk1)))
        assertTrue(store.verifyChunkOnDisk(part, 1_000, 1_000, sha(chunk1).uppercase()))
        // Chunk 0 is still zeros, so it must not verify against chunk1's hash.
        assertFalse(store.verifyChunkOnDisk(part, 0, 1_000, sha(chunk1)))
        // Past the end of the file.
        assertFalse(store.verifyChunkOnDisk(part, 2_500, 1_000, sha(chunk1)))
        assertEquals(3_000L, part.length())
    }

    @Test
    fun corruptedByteOnDiskIsDetected() = runBlocking {
        val part = store.createPartFile("t-2", "x.bin", 2_000)
        val chunk = Random(3).nextBytes(2_000)
        store.writeChunkAt(part, 0, chunk)
        java.io.RandomAccessFile(part, "rw").use { it.seek(1_500); it.write(chunk[1_500].toInt() xor 0xFF) }
        assertFalse(store.verifyChunkOnDisk(part, 0, 2_000, sha(chunk)))
    }

    @Test
    fun createPartFileKeepsExistingBytesOnResume() = runBlocking {
        val part = store.createPartFile("t-3", "x.bin", 1_000)
        val chunk = Random(4).nextBytes(500)
        store.writeChunkAt(part, 500, chunk)
        val again = store.createPartFile("t-3", "x.bin", 1_000)
        assertEquals(part, again)
        assertTrue(store.verifyChunkOnDisk(again, 500, 500, sha(chunk)))
    }

    @Test
    fun twoTransfersOfTheSameFileGetDistinctPartFiles() {
        assertTrue(store.partFileFor("a", "same.bin") != store.partFileFor("b", "same.bin"))
    }

    @Test
    fun writeToMissingPartFileFails() {
        val part = File(downloads, "gone.part")
        assertThrows(PartFileMissingException::class.java) {
            runBlocking { store.writeChunkAt(part, 0, ByteArray(1)) }
        }
    }

    @Test
    fun finalizeRenamesWithCollisionSuffixes() = runBlocking {
        val names = (1..3).map { i ->
            val part = store.createPartFile("t$i", "x.bin", 10)
            store.finalizePart(part, "x.bin").also { assertFalse(part.exists()) }.name
        }
        assertEquals(listOf("x.bin", "x (1).bin", "x (2).bin"), names)

        val noExt = (1..2).map { i -> store.finalizePart(store.createPartFile("n$i", "README", 1), "README").name }
        assertEquals(listOf("README", "README (1)"), noExt)
    }

    @Test
    fun finalizeNeverOverwritesExistingFile() = runBlocking {
        val existing = File(downloads, "keep.bin").apply { writeText("original") }
        val part = store.createPartFile("t", "keep.bin", 4)
        val target = store.finalizePart(part, "keep.bin")
        assertEquals("keep (1).bin", target.name)
        assertEquals("original", existing.readText())
    }

    @Test
    fun serverSuppliedNamesCannotEscapeTheDirectory() {
        assertEquals("_.._etc_passwd", FileStore.safeName("/../etc/passwd"))
        assertEquals("file", FileStore.safeName("..."))
        val part = store.partFileFor("id", "../../evil.bin")
        assertEquals(downloads, part.parentFile)
    }

    @Test
    fun deletePart() = runBlocking {
        val part = store.createPartFile("d", "x.bin", 1)
        assertTrue(store.deletePart(part))
        assertFalse(part.exists())
        assertTrue(store.deletePart(part))
    }

    // ---- generated files ----

    @Test
    fun generateTestFileHasRequestedSize() = runBlocking {
        val file = store.generateTestFile(3, seed = 42)
        assertEquals(3L * FileStore.MIB, file.length())
        assertEquals(generated, file.parentFile)
        val again = store.generateTestFile(3, seed = 42)
        assertEquals(store.sha256(file), store.sha256(again))
    }

    @Test
    fun cancelledGenerationLeavesNoFile() = runBlocking {
        var job: kotlinx.coroutines.Job? = null
        job = launch(Dispatchers.IO) {
            store.generateTestFile(512, seed = 1) { written -> if (written >= 2L * FileStore.MIB) job?.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(generated.listFiles()!!.isEmpty())
    }

    // ---- disk full ----

    @Test
    fun diskFullDetection() {
        assertTrue(IOException("write failed: ENOSPC (No space left on device)").isDiskFull())
        assertTrue(IOException("outer", IOException("No space left on device")).isDiskFull())
        assertFalse(IOException("Connection reset").isDiskFull())
        assertNotNull(DiskFullException("full").message)
    }
}
