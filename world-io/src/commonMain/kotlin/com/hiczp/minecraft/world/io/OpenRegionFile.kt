package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import okio.*
import kotlin.time.Clock

internal class OpenRegionFile private constructor(
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val regionPosition: RegionPosition,
    private val path: Path,
    private val handle: FileHandle,
    private val header: RegionHeader,
    private val allocator: RegionSectorAllocator,
    private val maximumCompressedChunkBytes: Int,
    private val syncWrites: Boolean,
) {
    private var closed = false

    fun read(position: LocalChunkPosition): RegionChunk? {
        checkOpen()
        return readStoredChunk(position)
    }

    fun readAll(): RegionFile {
        checkOpen()
        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            readStoredChunk(position)?.let { chunks[position] = it }
        }
        return RegionFile(chunks)
    }

    fun exists(position: LocalChunkPosition): Boolean {
        checkOpen()
        val location = header.location(position) ?: return false
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
            return fileSystem.metadataOrNull(
                externalPath(regionPosition.chunk(position)),
            )?.isRegularFile == true
        }
        return record.length != 0 &&
                record.compressedLength >= 0 &&
                record.compressedLength <= location.allocatedBytes
    }

    fun write(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        chunk: RegionChunk,
    ) {
        checkOpen()
        val compressedBytes = chunk.payload.compressedBytes
            ?: throw RegionFormatException(
                "External chunk $chunkPosition has not been resolved",
            )
        if (compressedBytes.size > maximumCompressedChunkBytes) {
            throw RegionFormatException(
                "Chunk $chunkPosition compressed size ${compressedBytes.size} exceeds configured limit $maximumCompressedChunkBytes",
            )
        }
        val record = EncodedRegionChunkRecord.encode(
            compression = chunk.compression,
            compressedPayload = compressedBytes,
        )
        if (record.external) {
            writeExternal(position, chunkPosition, record)
        } else {
            writeInternal(position, chunkPosition, record)
        }
    }

    fun clear(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
    ) {
        checkOpen()
        val oldLocation = header.location(position) ?: return
        header.set(
            position,
            location = null,
            timestamp = systemEpochSeconds(),
        )
        writeHeader()
        fileSystem.deleteIfExists(externalPath(chunkPosition))
        allocator.free(oldLocation)
    }

    fun flush() {
        checkOpen()
        handle.flushDurably(fileSystem, path)
    }

    fun close() {
        if (closed) return
        closed = true
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
                    val current = failure
                    if (current == null) {
                        failure = resizeFailure
                    } else {
                        current.addSuppressed(resizeFailure)
                    }
                }
            }
        }
        closeAllPreserving(
            failure,
            { handle.flushDurably(fileSystem, path) },
            handle::close,
        )
        failure?.let { throw it }
    }

    private fun readStoredChunk(position: LocalChunkPosition): RegionChunk? {
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
            val bytes = fileSystem.readFileWithinLimit(
                externalPath(absolute),
                maximumCompressedChunkBytes,
            )
            RegionChunkPayload.External(bytes)
        } else {
            val maximumPayload =
                location.allocatedBytes - REGION_CHUNK_RECORD_HEADER_BYTES
            if (record.compressedLength !in 0..maximumPayload) {
                throw RegionFormatException(
                    "Chunk $position declares invalid record length ${record.length} for ${location.sectorCount} allocated sector(s)",
                )
            }
            if (record.compressedLength > maximumCompressedChunkBytes) {
                throw RegionFormatException(
                    "Chunk $position compressed size ${record.compressedLength} exceeds configured limit $maximumCompressedChunkBytes",
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
    ) {
        val oldLocation = header.location(position)
        val newLocation = allocator.allocate(record.allocatedSectors)
        handle.write(
            newLocation.byteOffset,
            record.bytes,
            0,
            record.bytes.size,
        )
        if (syncWrites) handle.flushDurably(fileSystem, path)
        header.set(position, newLocation, systemEpochSeconds())
        writeHeader()
        fileSystem.deleteIfExists(externalPath(chunkPosition))
        allocator.free(oldLocation)
    }

    private fun writeExternal(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        record: EncodedRegionChunkRecord,
    ) {
        val payload = checkNotNull(record.externalPayload)
        val oldLocation = header.location(position)
        val newLocation = allocator.allocate(1)
        val temporary = fileSystem.openUniqueTemporarySink(
            directory = directory,
            prefix = ".mcc-",
            suffix = ".tmp",
        )
        try {
            val buffer = Buffer().apply { write(payload) }
            temporary.sink.use { sink ->
                sink.write(buffer, payload.size.toLong())
            }

            handle.write(
                newLocation.byteOffset,
                record.bytes,
                0,
                record.bytes.size,
            )
            if (syncWrites) handle.flushDurably(fileSystem, path)
            header.set(position, newLocation, systemEpochSeconds())
            writeHeader()
            fileSystem.moveReplacing(
                temporary.path,
                externalPath(chunkPosition),
            )
            allocator.free(oldLocation)
        } catch (failure: Throwable) {
            fileSystem.deleteIfExistsPreserving(temporary.path, failure)
            throw failure
        }
    }

    private fun writeHeader() {
        val bytes = header.encode()
        handle.write(0L, bytes, 0, bytes.size)
        if (syncWrites) handle.flushDurably(fileSystem, path)
    }

    private fun externalPath(position: ChunkPosition): Path =
        directory / "c.${position.x}.${position.z}.mcc"

    private fun checkOpen() {
        check(!closed) { "Region file is closed: $path" }
    }

    companion object {
        fun open(
            fileSystem: FileSystem,
            directory: Path,
            position: RegionPosition,
            maximumCompressedChunkBytes: Int,
            syncWrites: Boolean,
        ): OpenRegionFile {
            fileSystem.createDirectories(directory)
            val path = directory / "r.${position.x}.${position.z}.mca"
            val handle = fileSystem.openReadWrite(path)
            var failure: Throwable? = null
            try {
                val headerBytes = handle.readAtMost(0L, REGION_HEADER_BYTES)
                val header = RegionHeader.decode(headerBytes)
                val allocator = RegionSectorAllocator()
                val fileSize = handle.size()
                for (index in 0 until REGION_CHUNK_COUNT) {
                    val local = LocalChunkPosition.fromIndex(index)
                    val location = header.location(local) ?: continue
                    if (location.isUsableAtOpen(fileSize)) {
                        allocator.mark(location)
                    } else {
                        header.clearLocation(local)
                    }
                }
                return OpenRegionFile(
                    fileSystem = fileSystem,
                    directory = directory,
                    regionPosition = position,
                    path = path,
                    handle = handle,
                    header = header,
                    allocator = allocator,
                    maximumCompressedChunkBytes = maximumCompressedChunkBytes,
                    syncWrites = syncWrites,
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
