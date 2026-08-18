package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

data class RegionChunkStreamInfo(
    val compression: Compression,
    val compressedLength: Long,
    val external: Boolean,
    val timestamp: Int,
)

/**
 * Borrowed streaming view of one Region Header snapshot.
 *
 * [chunkPositions] contains the positions referenced by that Header. Every Chunk stream must be
 * consumed inside the enclosing `readRegion` callback; this scope is invalid afterward.
 */
class RegionReadScope internal constructor(
    private val store: RegionFileStore,
    private val header: RegionHeader,
) {
    private val positions = buildList {
        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            if (header.location(position) != null) add(position)
        }
    }
    private var valid = true

    val chunkPositions: List<LocalChunkPosition>
        get() {
            checkValid()
            return positions
        }

    fun readChunk(position: LocalChunkPosition): RegionChunk? {
        checkValid()
        return readChunk(position) { info, source ->
            val bytes = source.readByteArray()
            val payload = if (info.external) {
                RegionChunkPayload.External(bytes)
            } else {
                RegionChunkPayload.Inline(bytes)
            }
            RegionChunk(info.compression, payload, info.timestamp)
        }
    }

    fun readChunk(position: ChunkPosition): RegionChunk? {
        checkValid()
        return readChunk(store.regionPosition.local(position))
    }

    fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, Source) -> T,
    ): T? {
        checkValid()
        return store.readChunk(position, header, block)
    }

    fun <T> readChunk(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, Source) -> T,
    ): T? {
        checkValid()
        return readChunk(store.regionPosition.local(position), block)
    }

    fun doesChunkExist(position: LocalChunkPosition): Boolean {
        checkValid()
        return store.doesChunkExist(position, header)
    }

    fun doesChunkExist(position: ChunkPosition): Boolean {
        checkValid()
        return doesChunkExist(store.regionPosition.local(position))
    }

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region read scope is no longer valid: ${store.path}" }
    }
}

/**
 * Borrowed streaming builder for one complete Region replacement.
 *
 * Each position may be supplied once. Positions omitted by the callback are cleared when the
 * batch commits. Every Chunk sink is valid only for its `writeChunk` callback, and this scope is
 * invalid after the enclosing `writeRegion` callback returns. Anvil allocation requires the
 * compressed length before the sink is opened, so streaming writes accept already-compressed
 * bytes and an exact `compressedLength`; compression is deliberately not hidden
 * behind a temporary file or a second encoding pass.
 */
class RegionWriteScope internal constructor(
    private val regionPosition: RegionPosition,
    private val streamChunk: (
        LocalChunkPosition,
        Compression,
        Long,
        (Sink) -> Unit,
    ) -> Unit,
) {
    private var valid = true

    fun writeChunk(
        position: LocalChunkPosition,
        chunk: RegionChunk,
    ) {
        checkValid()
        val compressedBytes = validatedCompressedPayload(regionPosition.chunk(position), chunk)
        writeChunk(position, chunk.compression, compressedBytes.size.toLong()) { sink ->
            sink.write(compressedBytes)
        }
    }

    fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk,
    ) {
        checkValid()
        writeChunk(regionPosition.local(position), chunk)
    }

    fun writeChunk(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (Sink) -> Unit,
    ) {
        checkValid()
        streamChunk(position, compression, compressedLength, block)
    }

    fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (Sink) -> Unit,
    ) {
        checkValid()
        writeChunk(regionPosition.local(position), compression, compressedLength, block)
    }

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "Region write scope is no longer valid: $regionPosition" }
    }
}
