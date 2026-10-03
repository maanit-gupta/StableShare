package com.maanit.stableshare.domain

/** Chunk `index` covers bytes [offset, offset + length). */
data class ChunkSpec(val index: Int, val offset: Long, val length: Int)

/** Splits a file into fixed-size chunks; the last may be shorter. Matches DESIGN.md §3. */
object ChunkPlanner {

    fun totalChunks(fileSize: Long, chunkSize: Int): Int {
        validate(fileSize, chunkSize)
        val count = (fileSize + chunkSize - 1) / chunkSize
        require(count <= Int.MAX_VALUE) { "too many chunks: $count" }
        return count.toInt()
    }

    fun plan(fileSize: Long, chunkSize: Int): List<ChunkSpec> {
        val count = totalChunks(fileSize, chunkSize)
        return List(count) { index ->
            val offset = index.toLong() * chunkSize
            ChunkSpec(index, offset, minOf(chunkSize.toLong(), fileSize - offset).toInt())
        }
    }

    private fun validate(fileSize: Long, chunkSize: Int) {
        require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
        require(fileSize >= 0) { "fileSize must not be negative, was $fileSize" }
    }
}
