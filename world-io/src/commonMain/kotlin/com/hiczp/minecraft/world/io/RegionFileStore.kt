package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.Path
import kotlin.time.Clock

data class RegionFileStoreConfiguration(
    val maximumCompressedChunkBytes: Int = 256 * 1_048_576,
    val syncWrites: Boolean = true,
) {
    init {
        require(maximumCompressedChunkBytes >= 0)
    }
}

/**
 * One open `.mca` region file together with its external `.mcc` sidecars.
 *
 * Writes allocate new sectors, write them in place, commit the complete
 * header, and then retire old allocations; the whole file is never replaced
 * or shrunk. Timestamps and the internal/sidecar threshold are automatic.
 *
 * This primitive does not coordinate reads, writes, or close. Okio file handles support concurrent
 * positional reads, but callers are responsible for excluding writes and close from those reads
 * and for coordinating multiple instances that cover the same file. [WorldRegionStore] and
 * [MinecraftWorldAccess] provide higher-level shared-read/exclusive-write coordination. Chunk
 * coordinates must belong to the opened region; other regions are rejected instead of routed.
 */
class RegionFileStore private constructor(
    private val files: WorldFileAccess,
    val regionPosition: RegionPosition,
    private val directory: Path,
    val path: Path,
    private val handle: FileHandle,
    private val writer: RegionWriterState?,
    private val maximumCompressedChunkBytes: Int,
) {
    private var closed = false

    fun read(position: ChunkPosition): RegionChunk? {
        checkOpen()
        return readStoredChunk(local(position), headerForRead())
    }

    fun readAll(): RegionFile {
        checkOpen()
        val header = headerForRead()
        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            readStoredChunk(position, header)?.let {
                chunks[position] = it
            }
        }
        return RegionFile(chunks)
    }

    fun exists(position: ChunkPosition): Boolean {
        checkOpen()
        val local = local(position)
        val header = headerForRead()
        val location = header.location(local) ?: return false
        val prefix = handle.readAtMost(
            location.byteOffset,
            REGION_CHUNK_RECORD_HEADER_BYTES,
        )
        if (prefix.size < REGION_CHUNK_RECORD_HEADER_BYTES) return false
        val record = try {
            RegionChunkRecordHeader.decode(prefix)
        } catch (_: RegionFormatException) {
            return false
        }
        if (record.external) {
            return files.fileSystem.metadataOrNull(
                externalPath(position),
            )?.isRegularFile == true
        }
        return record.length != 0 &&
                record.compressedLength >= 0 &&
                record.compressedLength <= location.allocatedBytes
    }

    /** Writes one chunk; `null` clears the position. */
    fun write(
        position: ChunkPosition,
        chunk: RegionChunk?,
    ) {
        if (chunk == null) {
            clear(position)
            return
        }
        checkOpen()
        val writer = requireWriter()
        val compressedBytes = validatedCompressedPayload(position, chunk, maximumCompressedChunkBytes)
        val record = EncodedRegionChunkRecord.encode(
            compression = chunk.compression,
            compressedPayload = compressedBytes,
        )
        if (record.external) {
            writeExternal(local(position), position, record, writer)
        } else {
            writeInternal(local(position), position, record, writer)
        }
    }

    fun clear(position: ChunkPosition) {
        checkOpen()
        val local = local(position)
        val writer = requireWriter()
        val oldLocation = writer.header.location(local) ?: return
        writer.header.set(
            local,
            location = null,
            timestamp = systemEpochSeconds(),
        )
        writeHeader(writer)
        files.fileSystem.deleteIfExists(externalPath(position))
        writer.allocator.free(oldLocation)
    }

    fun flush() {
        checkOpen()
        if (writer != null) {
            handle.flushDurably(files.fileSystem, path)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (writer == null) {
            handle.close()
            return
        }
        var failure: Throwable? = null
        val size = try {
            handle.size()
        } catch (caught: Throwable) {
            failure = caught
            -1L
        }
        if (size > 0L) {
            val remainder = size % REGION_SECTOR_BYTES
            if (remainder != 0L) {
                try {
                    handle.resize(size + REGION_SECTOR_BYTES - remainder)
                } catch (resizeFailure: Throwable) {
                    failure = combineFailures(failure, resizeFailure)
                }
            }
        }
        closeAllPreserving(
            failure,
            { handle.flushDurably(files.fileSystem, path) },
            handle::close,
        )
        failure?.let { throw it }
    }

    private fun checkOpen() {
        check(!closed) { "Region file store is closed: $path" }
    }

    private fun local(position: ChunkPosition): LocalChunkPosition {
        require(position.region == regionPosition) {
            "Chunk $position belongs to region ${position.region}, not $regionPosition"
        }
        return position.local
    }

    private fun readStoredChunk(
        position: LocalChunkPosition,
        header: RegionHeader,
    ): RegionChunk? {
        val location = header.location(position) ?: return null
        val prefix = handle.readAtMost(
            location.byteOffset,
            REGION_CHUNK_RECORD_HEADER_BYTES,
        )
        if (prefix.size < REGION_CHUNK_RECORD_HEADER_BYTES) {
            throw RegionFormatException(
                "Chunk $position has a truncated record header",
            )
        }
        val record = RegionChunkRecordHeader.decode(prefix)
        if (record.length == 0) {
            throw RegionFormatException(
                "Chunk $position has an allocated but missing stream",
            )
        }
        val payload = if (record.external) {
            val absolute = regionPosition.chunk(position)
            val bytes = files.readFileWithinLimit(
                externalPath(absolute),
                maximumCompressedChunkBytes,
            )
            RegionChunkPayload.External(bytes)
        } else {
            val maximumPayload = location.allocatedBytes - REGION_CHUNK_RECORD_HEADER_BYTES
            if (record.compressedLength !in 0..maximumPayload) {
                throw RegionFormatException(
                    "Chunk $position has invalid length ${record.length} in ${location.sectorCount} allocated sectors",
                )
            }
            if (record.compressedLength > maximumCompressedChunkBytes) {
                val compressedLength = record.compressedLength
                throw RegionFormatException(
                    "Chunk $position compressed size $compressedLength exceeds limit $maximumCompressedChunkBytes",
                )
            }
            val bytes = handle.readAtMost(
                location.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES,
                record.compressedLength,
            )
            if (bytes.size != record.compressedLength) {
                throw RegionFormatException(
                    "Chunk $position payload is truncated",
                )
            }
            RegionChunkPayload.Inline(bytes)
        }
        return RegionChunk(
            compression = record.compression,
            payload = payload,
            timestamp = header.timestamp(position),
        )
    }

    private fun writeInternal(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        record: EncodedRegionChunkRecord,
        writer: RegionWriterState,
    ) {
        val oldLocation = writer.header.location(position)
        val newLocation = writer.allocator.allocate(record.allocatedSectors)
        handle.write(
            newLocation.byteOffset,
            record.bytes,
            0,
            record.bytes.size,
        )
        if (writer.syncWrites) {
            handle.flushDurably(files.fileSystem, path)
        }
        writer.header.set(position, newLocation, systemEpochSeconds())
        writeHeader(writer)
        files.fileSystem.deleteIfExists(externalPath(chunkPosition))
        writer.allocator.free(oldLocation)
    }

    private fun writeExternal(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        record: EncodedRegionChunkRecord,
        writer: RegionWriterState,
    ) {
        val payload = checkNotNull(record.externalPayload)
        val oldLocation = writer.header.location(position)
        val newLocation = writer.allocator.allocate(1)
        val temporary = files.fileSystem.openUniqueTemporarySink(
            directory = directory,
            prefix = ".mcc-",
            suffix = ".tmp",
        )
        try {
            val buffer = Buffer().apply { write(payload) }
            useResource(temporary.sink, { it.close() }) { sink ->
                sink.write(buffer, payload.size.toLong())
            }

            handle.write(
                newLocation.byteOffset,
                record.bytes,
                0,
                record.bytes.size,
            )
            if (writer.syncWrites) {
                handle.flushDurably(files.fileSystem, path)
            }
            writer.header.set(position, newLocation, systemEpochSeconds())
            writeHeader(writer)
            files.fileSystem.moveReplacing(
                temporary.path,
                externalPath(chunkPosition),
            )
            writer.allocator.free(oldLocation)
        } catch (failure: Throwable) {
            files.fileSystem.deleteIfExistsPreserving(
                temporary.path,
                failure,
            )
            throw failure
        }
    }

    private fun writeHeader(writer: RegionWriterState) {
        val bytes = writer.header.encode()
        handle.write(0L, bytes, 0, bytes.size)
        if (writer.syncWrites) {
            handle.flushDurably(files.fileSystem, path)
        }
    }

    private fun headerForRead(): RegionHeader =
        writer?.header ?: readUsableHeader(handle)

    private fun requireWriter(): RegionWriterState = writer
        ?: throw IllegalStateException("Region file store is live read-only: $path")

    private fun externalPath(position: ChunkPosition): Path =
        directory / "c.${position.x}.${position.z}.mcc"

    companion object {
        /**
         * Opens one exact `.mca` file; its region coordinates come from the
         * canonical `r.<x>.<z>.mca` name, and sidecars are resolved next to
         * it. The file is created when missing.
         */
        fun open(
            regionFile: Path,
            fileSystem: FileSystem = systemFileSystem,
            configuration: RegionFileStoreConfiguration = RegionFileStoreConfiguration(),
        ): RegionFileStore {
            val directory = regionFile.parent
                ?: throw WorldIOException(
                    "Region file has no parent directory: $regionFile",
                )
            val position = parseRegionFileName(regionFile.name)
                ?: throw WorldIOException("Not a region file: $regionFile")
            return open(
                files = WorldFileAccess.mutable(fileSystem),
                directory = directory,
                position = position,
                maximumCompressedChunkBytes = configuration.maximumCompressedChunkBytes,
                syncWrites = configuration.syncWrites,
            )
        }

        internal fun open(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            maximumCompressedChunkBytes: Int,
            syncWrites: Boolean,
        ): RegionFileStore {
            if (!files.liveReadOnly) {
                files.fileSystem.createDirectories(directory)
            }
            val path = directory / "r.${position.x}.${position.z}.mca"
            val handle = files.openRegionHandle(path)
            var failure: Throwable? = null
            try {
                val header = readUsableHeader(handle)
                val writer = if (files.liveReadOnly) {
                    null
                } else {
                    RegionWriterState(
                        header = header,
                        allocator = allocatorFor(header),
                        syncWrites = syncWrites,
                    )
                }
                return RegionFileStore(
                    files = files,
                    regionPosition = position,
                    directory = directory,
                    path = path,
                    handle = handle,
                    writer = writer,
                    maximumCompressedChunkBytes = maximumCompressedChunkBytes,
                )
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                if (failure != null) {
                    closeAllPreserving(failure, handle::close)
                }
            }
        }
    }
}

internal fun validatedCompressedPayload(
    position: ChunkPosition,
    chunk: RegionChunk,
    maximumCompressedChunkBytes: Int,
): ByteArray {
    val compressedBytes = chunk.payload.compressedBytes
        ?: throw RegionFormatException(
            "External chunk $position has not been resolved",
        )
    if (compressedBytes.size > maximumCompressedChunkBytes) {
        throw RegionFormatException(
            "Chunk $position compressed size ${compressedBytes.size} exceeds limit $maximumCompressedChunkBytes",
        )
    }
    return compressedBytes
}

private data class RegionWriterState(
    val header: RegionHeader,
    val allocator: RegionSectorAllocator,
    val syncWrites: Boolean,
)

private fun readUsableHeader(handle: FileHandle): RegionHeader {
    val headerBytes = handle.readAtMost(0L, REGION_HEADER_BYTES)
    val header = RegionHeader.decode(headerBytes)
    val fileSize = handle.size()
    for (index in 0 until REGION_CHUNK_COUNT) {
        val local = LocalChunkPosition.fromIndex(index)
        val location = header.location(local) ?: continue
        if (!location.isUsableAtOpen(fileSize)) {
            header.clearLocation(local)
        }
    }
    return header
}

private fun allocatorFor(header: RegionHeader): RegionSectorAllocator {
    val allocator = RegionSectorAllocator()
    for (index in 0 until REGION_CHUNK_COUNT) {
        val local = LocalChunkPosition.fromIndex(index)
        header.location(local)?.let(allocator::mark)
    }
    return allocator
}

private fun parseRegionFileName(name: String): RegionPosition? {
    val parts = name.split('.')
    if (parts.size != 4 || parts[0] != "r" || parts[3] != "mca") {
        return null
    }
    val x = parts[1].toIntOrNull() ?: return null
    val z = parts[2].toIntOrNull() ?: return null
    val position = RegionPosition(x, z)
    if (name != "r.${position.x}.${position.z}.mca") {
        return null
    }
    return position
}

private fun systemEpochSeconds(): Int =
    Clock.System.now().epochSeconds.toInt()

private fun FileHandle.readAtMost(offset: Long, byteCount: Int): ByteArray {
    require(offset >= 0)
    require(byteCount >= 0)
    if (byteCount == 0) return ByteArray(0)
    val buffer = Buffer()
    val read = read(offset, buffer, byteCount.toLong())
    return if (read < 0L) ByteArray(0) else buffer.readByteArray()
}
