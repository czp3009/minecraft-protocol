package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.io.Source
import okio.FileSystem
import okio.Path

/**
 * One uncoordinated live read-only handle for an exact `.mca` file and its sidecars.
 *
 * This reader takes no world or in-process lock and intentionally permits another process to
 * mutate, delete, or replace the covered files. Reads may therefore observe stale or torn state.
 * Callers own all read/close exclusion and must close the reader.
 */
class LiveRegionFileReader private constructor(
    private val store: RegionFileStore,
) {
    val regionPosition: RegionPosition
        get() = store.regionPosition

    val path: Path
        get() = store.path

    fun readRegion(): RegionFile = store.readRegion()

    fun <T> readRegion(block: RegionReadScope.() -> T): T = store.readRegion(block)

    fun readChunk(position: LocalChunkPosition): RegionChunk? = store.readChunk(position)

    fun readChunk(position: ChunkPosition): RegionChunk? = store.readChunk(position)

    fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, Source) -> T,
    ): T? = store.readChunk(position, block)

    fun <T> readChunk(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, Source) -> T,
    ): T? = store.readChunk(position, block)

    fun doesChunkExist(position: LocalChunkPosition): Boolean = store.doesChunkExist(position)

    fun doesChunkExist(position: ChunkPosition): Boolean = store.doesChunkExist(position)

    fun close() = store.close()

    companion object {
        fun open(
            regionFile: Path,
            fileSystem: FileSystem = systemFileSystem,
        ): LiveRegionFileReader {
            val directory = regionFile.parent
                ?: throw WorldIOException("Region file has no parent directory: $regionFile")
            val position = parseRegionFileName(regionFile.name)
                ?: throw WorldIOException("Not a region file: $regionFile")
            return open(WorldFileAccess.liveReadOnly(fileSystem), directory, position)
        }

        internal fun open(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
        ): LiveRegionFileReader = LiveRegionFileReader(
            RegionFileStore.openLive(files, directory, position),
        )
    }
}
