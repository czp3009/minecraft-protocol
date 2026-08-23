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
 * The handle pins one logical Region state until [close] but does not expose its physical files.
 * It does not hold a read or write lock between calls. Methods may be called concurrently: reads
 * share access, writes serialize, and a waiting writer blocks later readers. Blocking filesystem
 * and codec work stays on each calling coroutine's dispatcher.
 */
class RegionHandle internal constructor(
    private val owner: RegionStorage,
    internal val entry: RegionState,
    private val afterRelease: suspend () -> Throwable? = { null },
) {
    val position: RegionPosition
        get() = entry.position

    val chunkNbtFormat: CompressedNbtFormat
        get() = owner.chunkNbtFormat

    val configuration: RegionStorageConfiguration
        get() = owner.configuration

    private val state = Mutex()
    private var closed = false
    private var activeOperations = 0
    private var drained: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null

    suspend fun hasRegion(): Boolean = withOperation {
        owner.hasRegion(entry)
    }

    suspend fun readChunkInfo(local: LocalChunkPosition): RegionChunkInfo? = withOperation {
        owner.readChunkInfo(entry, local)
    }

    suspend fun readChunkInfo(position: ChunkPosition): RegionChunkInfo? = readChunkInfo(local(position))

    /** Reads detached stored metadata for every resolvable Chunk record in Region-local order. */
    suspend fun readChunkInfos(): List<RegionChunkInfo> = withOperation {
        owner.readChunkInfos(entry)
    }

    /** Reads the number of occupied Region header entries without reading Chunk record metadata. */
    suspend fun readChunkCount(): Int = withOperation {
        owner.readChunkCount(entry)
    }

    /** Reads a detached list of occupied Region-local positions in header order. */
    suspend fun readLocalChunkPositions(): List<LocalChunkPosition> = withOperation {
        owner.readLocalChunkPositions(entry)
    }

    /** Reads occupied absolute Chunk positions in Region header order. */
    suspend fun readChunkPositions(): List<ChunkPosition> = readLocalChunkPositions().map(position::chunk)

    /** Whether the Region index contains [local], without reading Chunk record metadata. */
    suspend fun hasChunk(local: LocalChunkPosition): Boolean = withOperation {
        owner.hasChunk(entry, local)
    }

    suspend fun hasChunk(position: ChunkPosition): Boolean = hasChunk(local(position))

    suspend fun hasChunk(position: BlockPosition): Boolean = hasChunk(position.chunk)

    suspend fun <R> withCompressedChunkSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withOperation {
        owner.withCompressedChunkSource(entry, local, block)
    }

    suspend fun <R> withCompressedChunkSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(local(position), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [sink]. */
    suspend fun readCompressedChunkTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        withCompressedChunkSource(local) { info, source ->
            source.transferTo(sink)
            info
        }

    suspend fun readCompressedChunkTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        readCompressedChunkTo(local(position), sink)

    suspend fun readCompressedChunk(local: LocalChunkPosition): CompressedChunk? = withOperation {
        owner.readCompressedChunk(entry, local)
    }

    suspend fun readCompressedChunk(position: ChunkPosition): CompressedChunk? = readCompressedChunk(local(position))

    suspend fun writeCompressedChunk(
        local: LocalChunkPosition,
        chunk: CompressedChunkInput,
    ) = withOperation {
        owner.writeCompressedChunk(entry, local, chunk)
    }

    suspend fun writeCompressedChunk(
        position: ChunkPosition,
        chunk: CompressedChunkInput,
    ) = writeCompressedChunk(local(position), chunk)

    /** Streams one already-compressed Chunk whose exact length is known before allocation. */
    suspend fun writeCompressedChunk(
        local: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = withOperation {
        owner.writeCompressedChunk(entry, local, compression, compressedByteCount, block)
    }

    suspend fun writeCompressedChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = writeCompressedChunk(local(position), compression, compressedByteCount, block)

    suspend fun removeChunk(local: LocalChunkPosition): Boolean = withOperation {
        owner.removeChunk(entry, local)
    }

    suspend fun removeChunk(position: ChunkPosition): Boolean = removeChunk(local(position))

    suspend fun <R> withChunkNbtSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withOperation {
        owner.withChunkNbtSource(entry, local, block)
    }

    suspend fun <R> withChunkNbtSource(
        position: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withChunkNbtSource(local(position), block)

    /** Copies one complete decompressed unnamed-root Chunk NBT stream without closing [sink]. */
    suspend fun readChunkNbtTo(local: LocalChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        withChunkNbtSource(local) { info, source ->
            source.transferTo(sink)
            info
        }

    suspend fun readChunkNbtTo(position: ChunkPosition, sink: KotlinxSink): RegionChunkInfo? =
        readChunkNbtTo(local(position), sink)

    suspend fun readChunkNbtDocument(local: LocalChunkPosition): NbtDocument? = withOperation {
        owner.readChunkNbtDocument(entry, local)
    }

    suspend fun readChunkNbtDocument(position: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(local(position))

    suspend fun <T> readChunkNbt(
        local: LocalChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = withOperation {
        owner.readChunkNbt(entry, local, deserializer)
    }

    suspend fun <T> readChunkNbt(
        position: ChunkPosition,
        deserializer: DeserializationStrategy<T>,
    ): T? = readChunkNbt(local(position), deserializer)

    suspend inline fun <reified T> readChunkNbt(position: LocalChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(position: ChunkPosition): T? =
        readChunkNbt(position, chunkNbtFormat.nbt.serializersModule.serializer())

    suspend fun <B : Any, M : Any> readChunk(
        local: LocalChunkPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withOperation {
        owner.readChunk(entry, local, codec)
    }

    suspend fun <B : Any, M : Any> readChunk(
        position: ChunkPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(local(position), codec)

    suspend fun <B : Any, M : Any> readChunk(
        position: BlockPosition,
        codec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(position.chunk, codec)

    suspend fun writeChunkNbtDocument(
        local: LocalChunkPosition,
        document: NbtDocument,
    ) = withOperation {
        owner.writeChunkNbtDocument(entry, local, document, owner.configuration.writeCompression)
    }

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbtDocument(local(position), document)

    suspend fun writeChunkNbtDocument(
        local: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = withOperation {
        owner.writeChunkNbtDocument(entry, local, document, compression)
    }

    suspend fun writeChunkNbtDocument(
        position: ChunkPosition,
        document: NbtDocument,
        compression: Compression,
    ) = writeChunkNbtDocument(local(position), document, compression)

    /**
     * Writes complete uncompressed unnamed-root Chunk NBT bytes supplied inside [block].
     *
     * The callback is required because the compressing Sink must be closed before its exact
     * compressed length is known and the Region write can begin.
     */
    suspend fun writeChunkNbt(
        local: LocalChunkPosition,
        compression: Compression = configuration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = withOperation {
        owner.writeChunkNbt(entry, local, compression, block)
    }

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        compression: Compression = configuration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = writeChunkNbt(local(position), compression, block)

    suspend fun <T> writeChunkNbt(
        local: LocalChunkPosition,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    ) = withOperation {
        owner.writeChunkNbt(entry, local, serializer, value, compression)
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

    /** Writes [chunk] at its retained position after validating Region membership. */
    suspend fun <B : Any, M : Any> writeChunk(
        chunk: Chunk<B, M>,
        codec: ChunkNbtCodec<B, M>,
        compression: Compression = configuration.writeCompression,
    ) = withOperation {
        owner.writeChunk(entry, position.local(chunk.position), chunk, codec, compression)
    }

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

    suspend fun clear() = withOperation {
        owner.clear(entry)
    }

    suspend fun replaceRegion(chunks: Collection<RegionChunkInput>) = withOperation {
        owner.replaceRegion(entry, chunks)
    }

    suspend fun <R> withReadScope(block: RegionReadScope.() -> R): R = withOperation {
        owner.withReadScope(entry, block)
    }

    suspend fun replaceRegion(block: RegionReplacementScope.() -> Unit) = withOperation {
        owner.replaceRegion(entry, block)
    }

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

    /** Runs [block] and then closes this Region handle with cancellation-safe cleanup. */
    suspend fun <T> use(block: suspend (RegionHandle) -> T): T =
        useSuspendingResource(this, RegionHandle::close, block)

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
