package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.*
import kotlinx.io.files.FileSystem
import kotlinx.io.files.SystemFileSystem

data class WorldRegionStoreConfiguration(
    val maximumRegionBytes: Int = 512 * 1_048_576,
    val maximumExternalChunkBytes: Int = 256 * 1_048_576,
) {
    init {
        require(maximumRegionBytes >= 0)
        require(maximumExternalChunkBytes >= 0)
    }
}

/**
 * Filesystem adapter for chunk, entity, and point-of-interest region files.
 *
 * Region mutations rewrite one complete `.mca` file. For batch updates, read a
 * region once, update its immutable map, then call [writeRegion] once.
 */
class WorldRegionStore(
    val paths: MinecraftWorldPaths,
    val fileSystem: FileSystem = SystemFileSystem,
    val regionFormat: RegionFileFormat = RegionFileFormat,
    val chunkNbtFormat: RegionChunkNbtFormat = RegionChunkNbtFormat(),
    val configuration: WorldRegionStoreConfiguration =
        WorldRegionStoreConfiguration(),
) {
    fun readRegion(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionFile? {
        val regionPath = paths.regionFile(position, storage, dimension)
        if (!fileSystem.exists(regionPath)) return null
        val encoded = fileSystem.readFileWithinLimit(
            regionPath,
            configuration.maximumRegionBytes,
        )
        val parsed = regionFormat.decodeFromByteArray(encoded)
        if (parsed.chunks.values.none { it.payload.isExternal }) return parsed

        return RegionFile(
            parsed.chunks.mapValues { (local, chunk) ->
                if (!chunk.payload.isExternal) {
                    chunk
                } else {
                    val absolute = position.chunk(local)
                    val externalPath =
                        paths.externalChunk(absolute, storage, dimension)
                    val bytes = fileSystem.readFileWithinLimit(
                        externalPath,
                        configuration.maximumExternalChunkBytes,
                    )
                    chunk.copy(payload = RegionChunkPayload.External(bytes))
                }
            },
        )
    }

    fun writeRegion(
        position: RegionPosition,
        region: RegionFile,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) {
        val regionPath = paths.regionFile(position, storage, dimension)
        val previousExternal = readExternalPositions(
            position,
            regionPathExists = fileSystem.exists(regionPath),
            storage = storage,
            dimension = dimension,
        )
        val encoded = regionFormat.encodeToByteArray(region)
        if (encoded.bytes.size > configuration.maximumRegionBytes) {
            throw WorldIOException(
                "Region size ${encoded.bytes.size} exceeds configured limit " +
                        configuration.maximumRegionBytes,
            )
        }
        encoded.externalChunks.forEach { (local, bytes) ->
            if (bytes.size > configuration.maximumExternalChunkBytes) {
                throw WorldIOException(
                    "External chunk $local size ${bytes.size} exceeds " +
                            "configured limit " +
                            configuration.maximumExternalChunkBytes,
                )
            }
        }
        encoded.externalChunks.forEach { (local, bytes) ->
            fileSystem.writeByteArrayAtomically(
                paths.externalChunk(position.chunk(local), storage, dimension),
                bytes,
            )
        }
        fileSystem.writeByteArrayAtomically(regionPath, encoded.bytes)

        (previousExternal - encoded.externalChunks.keys).forEach { local ->
            fileSystem.deleteIfExists(
                paths.externalChunk(position.chunk(local), storage, dimension),
            )
        }
    }

    fun readChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): RegionChunk? =
        readRegion(position.region, storage, dimension)?.get(position.local)

    fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk?,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) {
        val chunks = readRegion(position.region, storage, dimension)
            ?.chunks
            ?.toMutableMap()
            ?: linkedMapOf()
        if (chunk == null) {
            chunks.remove(position.local)
        } else {
            chunks[position.local] = chunk
        }
        writeRegion(
            position.region,
            RegionFile(chunks),
            storage,
            dimension,
        )
    }

    suspend fun readChunkNbt(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): NbtDocument? =
        readChunk(position, storage, dimension)?.let {
            chunkNbtFormat.decode(it)
        }

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        timestamp: Int,
        compression: RegionCompression = RegionCompression.ZLIB,
        external: Boolean = false,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ) {
        writeChunk(
            position = position,
            chunk = chunkNbtFormat.encode(
                document = document,
                compression = compression,
                timestamp = timestamp,
                external = external,
            ),
            storage = storage,
            dimension = dimension,
        )
    }

    private fun readExternalPositions(
        position: RegionPosition,
        regionPathExists: Boolean,
        storage: RegionStorageDirectory,
        dimension: DimensionDirectory,
    ): Set<LocalChunkPosition> {
        if (!regionPathExists) return emptySet()
        val path = paths.regionFile(position, storage, dimension)
        val encoded = fileSystem.readFileWithinLimit(
            path,
            configuration.maximumRegionBytes,
        )
        return regionFormat
            .decodeFromByteArray(encoded)
            .chunks
            .filterValues { it.payload.isExternal }
            .keys
    }
}
