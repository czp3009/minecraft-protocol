package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/**
 * Caller-owned coordinated access to one Region.
 *
 * The Region keeps one underlying entry and, once needed, one `.mca` handle alive until [close].
 * It does not hold a read or write lock between calls. Methods may be called concurrently: reads
 * share access, writes serialize, and a waiting writer blocks later readers. Blocking filesystem
 * and codec work stays on each calling coroutine's dispatcher.
 */
class WorldRegion internal constructor(
    private val owner: WorldRegionStore,
    internal val entry: WorldRegionEntry,
    private val afterRelease: suspend () -> Throwable? = { null },
) {
    val position: RegionPosition
        get() = entry.position

    val chunkNbtFormat: RegionChunkNbtFormat
        get() = owner.chunkNbtFormat

    val configuration: WorldRegionStoreConfiguration
        get() = owner.configuration

    private val state = Mutex()
    private var closed = false
    private var activeOperations = 0
    private var drained: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null

    suspend fun readRegion(): RegionFile? = withOperation {
        owner.readRegion(entry)
    }

    suspend fun <T> readRegion(block: RegionReadScope.() -> T): T? = withOperation {
        owner.readRegion(entry, block)
    }

    suspend fun writeRegion(region: RegionFile) = withOperation {
        owner.writeRegion(entry, region)
    }

    suspend fun writeRegion(block: RegionWriteScope.() -> Unit) = withOperation {
        owner.writeRegion(entry, block)
    }

    suspend fun clearRegion() = withOperation {
        owner.clearRegion(entry)
    }

    suspend fun doesRegionExist(): Boolean = withOperation {
        owner.doesRegionExist(entry)
    }

    suspend fun readChunk(position: LocalChunkPosition): RegionChunk? = withOperation {
        owner.readChunk(entry, position)
    }

    suspend fun readChunk(position: ChunkPosition): RegionChunk? = readChunk(local(position))

    suspend fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = withOperation {
        owner.readChunk(entry, position, block)
    }

    suspend fun <T> readChunk(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = readChunk(local(position), block)

    suspend fun doesChunkExist(position: LocalChunkPosition): Boolean = withOperation {
        owner.doesChunkExist(entry, position)
    }

    suspend fun doesChunkExist(position: ChunkPosition): Boolean = doesChunkExist(local(position))

    suspend fun writeChunk(
        position: LocalChunkPosition,
        chunk: RegionChunk,
    ) = withOperation {
        owner.writeChunk(entry, position, chunk)
    }

    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk,
    ) = writeChunk(local(position), chunk)

    /** Streams one already-compressed Chunk whose exact length is known before allocation. */
    suspend fun writeChunk(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) = withOperation {
        owner.writeChunk(entry, position, compression, compressedLength, block)
    }

    suspend fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) = writeChunk(local(position), compression, compressedLength, block)

    suspend fun clearChunk(position: LocalChunkPosition) = withOperation {
        owner.clearChunk(entry, position)
    }

    suspend fun clearChunk(position: ChunkPosition) = clearChunk(local(position))

    suspend fun readChunkNbtDocument(position: LocalChunkPosition): NbtDocument? = withOperation {
        owner.readChunkNbtDocument(entry, position)
    }

    suspend fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(local(position))

    suspend fun <T> readChunkNbt(
        position: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = withOperation {
        owner.readChunkNbt(entry, position, deserializer)
    }

    suspend fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = readChunkNbt(local(position), deserializer)

    suspend inline fun <reified T> readChunkNbt(position: LocalChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend fun writeChunkNbtDocument(
        position: LocalChunkPosition,
        document: NbtDocument,
    ) = withOperation {
        owner.writeChunkNbtDocument(entry, position, document, owner.configuration.writeCompression)
    }

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbtDocument(local(position), document)

    suspend fun writeChunkNbtDocument(
        position: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = withOperation {
        owner.writeChunkNbtDocument(entry, position, document, compression)
    }

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = writeChunkNbtDocument(local(position), document, compression)

    suspend fun <T> writeChunkNbt(
        position: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = withOperation {
        owner.writeChunkNbt(entry, position, serializer, value, compression)
    }

    suspend fun <T> writeChunkNbt(
        position: ChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(local(position), serializer, value, compression)

    suspend inline fun <reified T> writeChunkNbt(
        position: LocalChunkPosition,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(
        position,
        chunkNbtFormat.nbt.serializersModule.serializer(),
        value,
        compression,
    )

    suspend inline fun <reified T> writeChunkNbt(
        position: ChunkPosition,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = writeChunkNbt(
        position,
        chunkNbtFormat.nbt.serializersModule.serializer(),
        value,
        compression,
    )

    suspend fun flush() = withOperation {
        owner.flush(entry)
    }

    /** Seals new calls, waits for admitted calls, and releases this Region's entry pin. */
    suspend fun close() {
        val completion: CompletableDeferred<Unit>
        val drain: CompletableDeferred<Unit>
        val ownerOfClose: Boolean
        state.withLock {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
                drain = drained ?: error("Closed Region has no drain barrier")
                ownerOfClose = false
            } else {
                closed = true
                completion = CompletableDeferred()
                closeCompletion = completion
                drain = CompletableDeferred()
                drained = drain
                if (activeOperations == 0) drain.complete(Unit)
                ownerOfClose = true
            }
        }
        if (!ownerOfClose) {
            if (!completion.isCompleted) completion.await()
            closeFailure?.let { throw it }
            return
        }

        val failure = withContext(NonCancellable) {
            drain.await()
            var result = try {
                owner.releaseRegion(entry)
            } catch (caught: Throwable) {
                caught
            }
            val ownerFailure = try {
                afterRelease()
            } catch (caught: Throwable) {
                caught
            }
            if (ownerFailure != null) {
                result = combineFailures(result, ownerFailure)
            }
            state.withLock {
                closeFailure = result
            }
            completion.complete(Unit)
            result
        }
        throwFailureOrCancellation(failure)
    }

    private suspend fun acquireOperation() {
        state.withLock {
            check(!closed) { "Region is closed: $position" }
            activeOperations++
        }
    }

    private fun local(position: ChunkPosition): LocalChunkPosition = this.position.local(position)

    private suspend fun releaseOperation(): Throwable? {
        val completion = state.withLock {
            check(activeOperations > 0) { "Region operation is not active: $position" }
            activeOperations--
            if (closed && activeOperations == 0) drained else null
        }
        completion?.complete(Unit)
        return null
    }

    private suspend fun <T> withOperation(block: suspend () -> T): T {
        acquireOperation()
        return withCleanup(
            cleanup = { releaseOperation() },
            block = block,
        )
    }
}
