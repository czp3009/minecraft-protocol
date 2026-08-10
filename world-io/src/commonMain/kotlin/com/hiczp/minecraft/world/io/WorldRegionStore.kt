package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path

data class WorldRegionStoreConfiguration(
    val maximumCompressedChunkBytes: Int = 256 * 1_048_576,
    val maximumOpenRegions: Int = 256,
    val syncWrites: Boolean = true,
    /** Default used by NBT writes that do not select compression per chunk. */
    val writeCompression: RegionCompression = RegionCompression.ZLIB,
) {
    init {
        require(maximumCompressedChunkBytes >= 0)
        require(maximumOpenRegions > 0)
    }
}

/**
 * One vanilla-style region storage directory for one dimension.
 *
 * A store owns an independent 256-entry LRU by default. Chunk mutations write
 * new sectors in place, commit the complete header, then retire the old
 * allocation. They never replace an entire `.mca` file.
 */
class WorldRegionStore internal constructor(
    val directory: Path,
    internal val files: WorldFileAccess,
    val chunkNbtFormat: RegionChunkNbtFormat,
    val configuration: WorldRegionStoreConfiguration,
) {
    constructor(
        directory: Path,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
        configuration: WorldRegionStoreConfiguration =
            WorldRegionStoreConfiguration(),
    ) : this(
        directory = directory,
        files = WorldFileAccess.mutable(fileSystem),
        chunkNbtFormat = chunkNbtFormat,
        configuration = configuration,
    )

    constructor(
        paths: MinecraftWorldPaths,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
        fileSystem: FileSystem = systemFileSystem,
        chunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
        configuration: WorldRegionStoreConfiguration =
            WorldRegionStoreConfiguration(),
    ) : this(
        directory = paths.regionDirectory(storage, dimension),
        files = WorldFileAccess.mutable(fileSystem),
        chunkNbtFormat = chunkNbtFormat,
        configuration = configuration,
    )

    internal constructor(
        paths: MinecraftWorldPaths,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
        files: WorldFileAccess,
        chunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
        configuration: WorldRegionStoreConfiguration =
            WorldRegionStoreConfiguration(),
    ) : this(
        directory = paths.regionDirectory(storage, dimension),
        files = files,
        chunkNbtFormat = chunkNbtFormat,
        configuration = configuration,
    )

    val fileSystem: FileSystem
        get() = files.fileSystem

    private val mutex = Mutex()
    private val regions = linkedMapOf<RegionPosition, OpenRegionFile>()
    private var closed = false

    /** Reads one complete in-memory snapshot without reading unrelated files. */
    suspend fun readRegion(position: RegionPosition): RegionFile =
        mutex.withLock {
            checkOpen()
            region(position)?.readAll() ?: RegionFile()
        }

    suspend fun readChunk(position: ChunkPosition): RegionChunk? =
        mutex.withLock {
            checkOpen()
            region(position.region)?.read(position.local)
        }

    suspend fun doesChunkExist(position: ChunkPosition): Boolean =
        mutex.withLock {
            checkOpen()
            region(position.region)?.exists(position.local) == true
        }

    /**
     * Writes compressed chunk data with an automatic timestamp and automatic
     * internal/external selection. The input payload marker and timestamp are
     * representation details and do not control the filesystem commit.
     */
    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk?,
    ) = mutex.withLock {
        checkOpen()
        files.requireWritable()
        if (chunk == null) {
            checkNotNull(region(position.region))
                .clear(position.local, position)
        } else {
            validateChunkForWrite(position, chunk)
            checkNotNull(region(position.region)).write(
                position.local,
                position,
                chunk,
            )
        }
    }

    suspend fun clearChunk(position: ChunkPosition) {
        writeChunk(position, null)
    }

    suspend fun readChunkNbt(position: ChunkPosition): NbtDocument? =
        readChunk(position)?.let { chunk ->
            withOkioIoExceptions("Cannot decode chunk $position") {
                chunkNbtFormat.decode(chunk)
            }
        }

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
    ) = writeChunkNbt(
        position = position,
        document = document,
        compression = configuration.writeCompression,
    )

    /** Encodes and writes one chunk with a per-write compression selection. */
    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        compression: RegionCompression,
    ) {
        val chunk = withOkioIoExceptions("Cannot encode chunk $position") {
            chunkNbtFormat.encode(
                document = document,
                compression = compression,
            )
        }
        writeChunk(position = position, chunk = chunk)
    }

    suspend fun flush() = mutex.withLock {
        checkOpen()
        var failure: Throwable? = null
        regions.values.forEach { region ->
            try {
                region.flush()
            } catch (caught: Throwable) {
                val current = failure
                if (current == null) {
                    failure = caught
                } else {
                    current.addSuppressed(caught)
                }
            }
        }
        failure?.let { throw it }
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        var failure: Throwable? = null
        regions.values.forEach { region ->
            try {
                region.close()
            } catch (caught: Throwable) {
                val current = failure
                if (current == null) {
                    failure = caught
                } else {
                    current.addSuppressed(caught)
                }
            }
        }
        regions.clear()
        failure?.let { throw it }
    }

    private fun region(position: RegionPosition): OpenRegionFile? {
        regions.remove(position)?.let { existing ->
            regions[position] = existing
            return existing
        }
        if (files.liveReadOnly) {
            val path = directory / "r.${position.x}.${position.z}.mca"
            val metadata = fileSystem.metadataOrNull(path) ?: return null
            if (!metadata.isRegularFile) {
                throw WorldIOException("Path is not a regular file: $path")
            }
        }
        if (regions.size >= configuration.maximumOpenRegions) {
            val leastRecentlyUsed = regions.entries.first()
            val evictedPosition = leastRecentlyUsed.key
            val evictedRegion = leastRecentlyUsed.value
            regions.remove(evictedPosition)
            evictedRegion.close()
        }
        val opened = OpenRegionFile.open(
            files = files,
            directory = directory,
            position = position,
            maximumCompressedChunkBytes =
                configuration.maximumCompressedChunkBytes,
            syncWrites = configuration.syncWrites,
        )
        regions[position] = opened
        return opened
    }

    private fun checkOpen() {
        check(!closed) { "Region store is closed: $directory" }
    }

    private fun validateChunkForWrite(
        position: ChunkPosition,
        chunk: RegionChunk,
    ) {
        val compressedBytes = chunk.payload.compressedBytes
            ?: throw RegionFormatException(
                "External chunk $position has not been resolved",
            )
        if (
            compressedBytes.size >
            configuration.maximumCompressedChunkBytes
        ) {
            throw RegionFormatException(
                "Chunk $position compressed size ${compressedBytes.size} exceeds configured limit ${configuration.maximumCompressedChunkBytes}",
            )
        }
    }
}
