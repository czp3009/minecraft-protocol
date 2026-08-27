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
    val regionPosition: RegionPosition
        get() = entry.regionPosition

    val chunkNbtFormat: CompressedNbtFormat
        get() = owner.chunkNbtFormat

    val regionStorageConfiguration: RegionStorageConfiguration
        get() = owner.regionStorageConfiguration

    private val state = Mutex()
    private var closed = false
    private var activeOperations = 0
    private var drained: CompletableDeferred<Unit>? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null

    suspend fun hasRegion(): Boolean = withOperation {
        owner.hasRegion(entry)
    }

    suspend fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? = withOperation {
        owner.readChunkInfo(entry, localChunkPosition)
    }

    suspend fun readChunkInfo(chunkPosition: ChunkPosition): RegionChunkInfo? = readChunkInfo(local(chunkPosition))

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
    suspend fun readChunkPositions(): List<ChunkPosition> = readLocalChunkPositions().map(regionPosition::chunk)

    /** Whether the Region index contains [localChunkPosition], without reading Chunk record metadata. */
    suspend fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = withOperation {
        owner.hasChunk(entry, localChunkPosition)
    }

    suspend fun hasChunk(chunkPosition: ChunkPosition): Boolean = hasChunk(local(chunkPosition))

    suspend fun hasChunk(blockPosition: BlockPosition): Boolean = hasChunk(blockPosition.chunkPosition)

    suspend fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withOperation {
        owner.withCompressedChunkSource(entry, localChunkPosition, block)
    }

    suspend fun <R> withCompressedChunkSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withCompressedChunkSource(local(chunkPosition), block)

    /** Copies one complete compressed Chunk payload without retaining it in memory or closing [kotlinxSink]. */
    suspend fun readCompressedChunkTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            source.transferTo(kotlinxSink)
            regionChunkInfo
        }

    suspend fun readCompressedChunkTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        readCompressedChunkTo(local(chunkPosition), kotlinxSink)

    suspend fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? = withOperation {
        owner.readCompressedChunk(entry, localChunkPosition)
    }

    suspend fun readCompressedChunk(chunkPosition: ChunkPosition): CompressedChunk? = readCompressedChunk(local(chunkPosition))

    suspend fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compressedChunkInput: CompressedChunkInput,
    ) = withOperation {
        owner.writeCompressedChunk(entry, localChunkPosition, compressedChunkInput)
    }

    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compressedChunkInput: CompressedChunkInput,
    ) = writeCompressedChunk(local(chunkPosition), compressedChunkInput)

    /** Streams one already-compressed Chunk whose exact length is known before allocation. */
    suspend fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = withOperation {
        owner.writeCompressedChunk(entry, localChunkPosition, compression, compressedByteCount, block)
    }

    suspend fun writeCompressedChunk(
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) = writeCompressedChunk(local(chunkPosition), compression, compressedByteCount, block)

    suspend fun removeChunk(localChunkPosition: LocalChunkPosition): Boolean = withOperation {
        owner.removeChunk(entry, localChunkPosition)
    }

    suspend fun removeChunk(chunkPosition: ChunkPosition): Boolean = removeChunk(local(chunkPosition))

    suspend fun <R> withChunkNbtSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withOperation {
        owner.withChunkNbtSource(entry, localChunkPosition, block)
    }

    suspend fun <R> withChunkNbtSource(
        chunkPosition: ChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? = withChunkNbtSource(local(chunkPosition), block)

    /** Copies one complete decompressed unnamed-root Chunk NBT stream without closing [kotlinxSink]. */
    suspend fun readChunkNbtTo(localChunkPosition: LocalChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        withChunkNbtSource(localChunkPosition) { regionChunkInfo, source ->
            source.transferTo(kotlinxSink)
            regionChunkInfo
        }

    suspend fun readChunkNbtTo(chunkPosition: ChunkPosition, kotlinxSink: KotlinxSink): RegionChunkInfo? =
        readChunkNbtTo(local(chunkPosition), kotlinxSink)

    suspend fun readChunkNbtDocument(localChunkPosition: LocalChunkPosition): NbtDocument? = withOperation {
        owner.readChunkNbtDocument(entry, localChunkPosition)
    }

    suspend fun readChunkNbtDocument(chunkPosition: ChunkPosition): NbtDocument? =
        readChunkNbtDocument(local(chunkPosition))

    suspend fun <T> readChunkNbt(
        localChunkPosition: LocalChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = withOperation {
        owner.readChunkNbt(entry, localChunkPosition, deserializationStrategy)
    }

    suspend fun <T> readChunkNbt(
        chunkPosition: ChunkPosition,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readChunkNbt(local(chunkPosition), deserializationStrategy)

    suspend inline fun <reified T> readChunkNbt(localChunkPosition: LocalChunkPosition): T? =
        readChunkNbt(localChunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    suspend inline fun <reified T> readChunkNbt(chunkPosition: ChunkPosition): T? =
        readChunkNbt(chunkPosition, chunkNbtFormat.nbtFormat.serializersModule.serializer())

    suspend fun <B : Any, M : Any> readChunk(
        localChunkPosition: LocalChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = withOperation {
        owner.readChunk(entry, localChunkPosition, chunkNbtCodec)
    }

    suspend fun <B : Any, M : Any> readChunk(
        chunkPosition: ChunkPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(local(chunkPosition), chunkNbtCodec)

    suspend fun <B : Any, M : Any> readChunk(
        blockPosition: BlockPosition,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
    ): Chunk<B, M>? = readChunk(blockPosition.chunkPosition, chunkNbtCodec)

    suspend fun writeChunkNbtDocument(
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
    ) = withOperation {
        owner.writeChunkNbtDocument(entry, localChunkPosition, nbtDocument, owner.regionStorageConfiguration.writeCompression)
    }

    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
    ) = writeChunkNbtDocument(local(chunkPosition), nbtDocument)

    suspend fun writeChunkNbtDocument(
        localChunkPosition: LocalChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
    ) = withOperation {
        owner.writeChunkNbtDocument(entry, localChunkPosition, nbtDocument, compression)
    }

    suspend fun writeChunkNbtDocument(
        chunkPosition: ChunkPosition,
        nbtDocument: NbtDocument,
        compression: Compression,
    ) = writeChunkNbtDocument(local(chunkPosition), nbtDocument, compression)

    /**
     * Writes complete uncompressed unnamed-root Chunk NBT bytes supplied inside [block].
     *
     * The callback is required because the compressing Sink must be closed before its exact
     * compressed length is known and the Region write can begin.
     */
    suspend fun writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = withOperation {
        owner.writeChunkNbt(entry, localChunkPosition, compression, block)
    }

    suspend fun writeChunkNbt(
        chunkPosition: ChunkPosition,
        compression: Compression = regionStorageConfiguration.writeCompression,
        block: (KotlinxSink) -> Unit,
    ) = writeChunkNbt(local(chunkPosition), compression, block)

    suspend fun <T> writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = withOperation {
        owner.writeChunkNbt(entry, localChunkPosition, serializationStrategy, value, compression)
    }

    suspend fun <T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(local(chunkPosition), serializationStrategy, value, compression)

    suspend inline fun <reified T> writeChunkNbt(
        localChunkPosition: LocalChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(
        localChunkPosition,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
        value,
        compression,
    )

    /** Writes [chunk] at its retained position after validating Region membership. */
    suspend fun <B : Any, M : Any> writeChunk(
        chunk: Chunk<B, M>,
        chunkNbtCodec: ChunkNbtCodec<B, M>,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = withOperation {
        owner.writeChunk(entry, regionPosition.local(chunk.chunkPosition), chunk, chunkNbtCodec, compression)
    }

    suspend inline fun <reified T> writeChunkNbt(
        chunkPosition: ChunkPosition,
        value: T,
        compression: Compression = regionStorageConfiguration.writeCompression,
    ) = writeChunkNbt(
        chunkPosition,
        chunkNbtFormat.nbtFormat.serializersModule.serializer(),
        value,
        compression,
    )

    suspend fun clear() = withOperation {
        owner.clear(entry)
    }

    suspend fun replaceRegion(chunks: Collection<RegionChunkInput>) = withOperation {
        owner.replaceRegion(entry, chunks)
    }

    /**
     * Runs [block] under one shared-read admission with one consistent Region header snapshot.
     * Coordinated writes to this Region cannot interleave with the callback. The typed scope can
     * decode semantic Chunks while reusing that Header state.
     */
    suspend fun <R> withReadScope(block: RegionReadScope.() -> R): R = withReadScopeCore {
        block(RegionReadScope(this, chunkNbtFormat))
    }

    internal suspend fun <R> withReadScopeCore(block: RegionReadScopeCore.() -> R): R = withOperation {
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
            check(!closed) { "Region is closed: $regionPosition" }
            activeOperations++
        }
    }

    private fun local(chunkPosition: ChunkPosition): LocalChunkPosition = this.regionPosition.local(chunkPosition)

    private suspend fun releaseOperation(): Throwable? {
        val completion = state.withLock {
            check(activeOperations > 0) { "Region operation is not active: $regionPosition" }
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
