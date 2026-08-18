package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.io.Source
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer

/**
 * Caller-owned non-locking access to one live Region.
 *
 * All reads reuse one live handle and take no lock. Callers must exclude [close] from concurrent
 * reads. [LiveMinecraftWorldReader.withRegion] provides structured ownership when the Region does
 * not need to escape one callback.
 */
class LiveWorldRegion internal constructor(
    private val reader: LiveRegionFileReader,
    val chunkNbtFormat: RegionChunkNbtFormat,
) {
    val position: RegionPosition
        get() = reader.regionPosition

    private var closed = false

    fun readRegion(): RegionFile {
        checkValid()
        return reader.readRegion()
    }

    fun <T> readRegion(block: RegionReadScope.() -> T): T {
        checkValid()
        return reader.readRegion(block)
    }

    fun readChunk(position: LocalChunkPosition): RegionChunk? {
        checkValid()
        return reader.readChunk(position)
    }

    fun readChunk(position: ChunkPosition): RegionChunk? = readChunk(local(position))

    fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, Source) -> T,
    ): T? {
        checkValid()
        return reader.readChunk(position, block)
    }

    fun <T> readChunk(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, Source) -> T,
    ): T? = readChunk(local(position), block)

    fun doesChunkExist(position: LocalChunkPosition): Boolean {
        checkValid()
        return reader.doesChunkExist(position)
    }

    fun doesChunkExist(position: ChunkPosition): Boolean = doesChunkExist(local(position))

    fun readChunkNbtDocument(position: LocalChunkPosition): NbtDocument? {
        checkValid()
        return reader.readChunk(position) { info, source ->
            val absolute = this.position.chunk(position)
            withOkioIoExceptions("Cannot decode chunk $absolute") {
                chunkNbtFormat.decodeFromSource(source, info.compression)
            }
        }
    }

    fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? = readChunkNbtDocument(local(position))

    fun <T> readChunkNbt(
        position: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? {
        checkValid()
        return reader.readChunk(position) { info, source ->
            val absolute = this.position.chunk(position)
            withOkioIoExceptions("Cannot decode chunk $absolute") {
                chunkNbtFormat.decodeFromSource(deserializer, source, info.compression)
            }
        }
    }

    fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = readChunkNbt(local(position), deserializer)

    inline fun <reified T> readChunkNbt(position: LocalChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    fun close() {
        if (closed) return
        closed = true
        reader.close()
    }

    private fun checkValid() {
        check(!closed) { "Live Region is closed: $position" }
    }

    private fun local(position: ChunkPosition): LocalChunkPosition = this.position.local(position)
}
