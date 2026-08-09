package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import okio.Buffer
import okio.FileHandle
import okio.Path
import okio.use
import kotlin.time.Clock

internal class OpenRegionFile private constructor(
    private val files: WorldFileAccess,
    private val directory: Path,
    private val regionPosition: RegionPosition,
    private val path: Path,
    private val handle: FileHandle,
    private val writer: RegionWriterState?,
    private val maximumCompressedChunkBytes: Int,
) {
    private var closed = false

    fun read(position: LocalChunkPosition): RegionChunk? {
        checkOpen()
        return readStoredChunk(position, headerForRead())
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

    fun exists(position: LocalChunkPosition): Boolean {
        checkOpen()
        val header = headerForRead()
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
            return files.fileSystem.metadataOrNull(
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
        val writer = requireWriter()
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
            writeExternal(position, chunkPosition, record, writer)
        } else {
            writeInternal(position, chunkPosition, record, writer)
        }
    }

    fun clear(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
    ) {
        checkOpen()
        val writer = requireWriter()
        val oldLocation = writer.header.location(position) ?: return
        writer.header.set(
            position,
            location = null,
            timestamp = systemEpochSeconds(),
        )
        writeHeader(writer)
        files.fileSystem.deleteIfExists(externalPath(chunkPosition))
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
            { handle.flushDurably(files.fileSystem, path) },
            handle::close,
        )
        failure?.let { throw it }
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
            temporary.sink.use { sink ->
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
        ?: throw IllegalStateException("Region file is live read-only: $path")

    private fun externalPath(position: ChunkPosition): Path =
        directory / "c.${position.x}.${position.z}.mcc"

    private fun checkOpen() {
        check(!closed) { "Region file is closed: $path" }
    }

    companion object {
        fun open(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            maximumCompressedChunkBytes: Int,
            syncWrites: Boolean,
        ): OpenRegionFile {
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
                return OpenRegionFile(
                    files = files,
                    directory = directory,
                    regionPosition = position,
                    path = path,
                    handle = handle,
                    writer = writer,
                    maximumCompressedChunkBytes =
                        maximumCompressedChunkBytes,
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
