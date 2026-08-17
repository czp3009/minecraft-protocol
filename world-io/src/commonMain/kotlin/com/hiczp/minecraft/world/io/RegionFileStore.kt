package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import okio.*
import kotlin.time.Clock

data class RegionChunkStreamInfo(
    val compression: Compression,
    val compressedLength: Long,
    val external: Boolean,
    val timestamp: Int,
)

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
) {
    private var closed = false

    fun read(position: ChunkPosition): RegionChunk? {
        return read(position) { info, source ->
            val bytes = source.readByteArray()
            val payload = if (info.external) {
                RegionChunkPayload.External(bytes)
            } else {
                RegionChunkPayload.Inline(bytes)
            }
            RegionChunk(info.compression, payload, info.timestamp)
        }
    }

    /** Lends one compressed chunk stream without retaining its payload. */
    fun <T> read(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T? {
        checkOpen()
        return readStoredChunk(local(position), headerForRead(), block)
    }

    fun readAll(): RegionFile {
        checkOpen()
        val header = headerForRead()
        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            readStoredChunk(position, header) { info, source ->
                val bytes = source.readByteArray()
                val payload = if (info.external) {
                    RegionChunkPayload.External(bytes)
                } else {
                    RegionChunkPayload.Inline(bytes)
                }
                RegionChunk(info.compression, payload, info.timestamp)
            }?.let {
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
                record.compressedLength <=
                location.allocatedBytes - REGION_CHUNK_RECORD_HEADER_BYTES
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
        val compressedBytes = validatedCompressedPayload(position, chunk)
        write(position, chunk.compression, compressedBytes.size.toLong()) {
            write(compressedBytes)
        }
    }

    /** Writes one already-compressed payload directly from [block]. */
    fun write(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: BufferedSink.() -> Unit,
    ) {
        checkOpen()
        require(compressedLength >= 0L) { "Compressed length must be non-negative" }
        val local = local(position)
        val writer = requireWriter()
        val maximumInlineBytes = (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1L) * REGION_SECTOR_BYTES
        if (compressedLength > maximumInlineBytes - REGION_CHUNK_RECORD_HEADER_BYTES) {
            writeExternal(local, position, compression, compressedLength, writer, block)
        } else {
            val inlineSectors = regionSectorsForBytes(
                REGION_CHUNK_RECORD_HEADER_BYTES.toLong() + compressedLength,
            )
            writeInternal(local, position, compression, compressedLength, inlineSectors, writer, block)
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

    private fun <T> readStoredChunk(
        position: LocalChunkPosition,
        header: RegionHeader,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T? {
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
        val timestamp = header.timestamp(position)
        return if (record.external) {
            val absolute = regionPosition.chunk(position)
            val externalPath = externalPath(absolute)
            files.readFile(externalPath) { source, size ->
                block(
                    RegionChunkStreamInfo(record.compression, size, external = true, timestamp),
                    source,
                )
            }
        } else {
            val maximumPayload = location.allocatedBytes - REGION_CHUNK_RECORD_HEADER_BYTES
            if (record.compressedLength !in 0..maximumPayload) {
                throw RegionFormatException(
                    "Chunk $position has invalid length ${record.length} in ${location.sectorCount} allocated sectors",
                )
            }
            val source = handle.source(
                location.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES,
            ).limit(record.compressedLength.toLong()).buffer()
            useResource(source, { it.close() }) {
                val value = block(
                    RegionChunkStreamInfo(
                        record.compression,
                        record.compressedLength.toLong(),
                        external = false,
                        timestamp,
                    ),
                    source,
                )
                if (!source.exhausted()) {
                    throw WorldIOException("Chunk $position payload was not fully consumed")
                }
                value
            }
        }
    }

    private fun writeInternal(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        allocatedSectors: Int,
        writer: RegionWriterState,
        block: BufferedSink.() -> Unit,
    ) {
        val oldLocation = writer.header.location(position)
        val newLocation = writer.allocator.allocate(allocatedSectors)
        var committed = false
        try {
            val header = RegionChunkRecordHeader(
                length = compressedLength.toInt() + 1,
                compression = compression,
                external = false,
            ).encode()
            handle.write(newLocation.byteOffset, header, 0, header.size)
            writePayload(
                handle.sink(newLocation.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES),
                compressedLength,
                block,
            )
            if (writer.syncWrites) {
                handle.flushDurably(files.fileSystem, path)
            }
            committed = true
            writer.header.set(position, newLocation, systemEpochSeconds())
            writeHeader(writer)
            files.fileSystem.deleteIfExists(externalPath(chunkPosition))
            writer.allocator.free(oldLocation)
        } catch (failure: Throwable) {
            if (!committed) writer.allocator.free(newLocation)
            throw failure
        }
    }

    private fun writeExternal(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        writer: RegionWriterState,
        block: BufferedSink.() -> Unit,
    ) {
        val oldLocation = writer.header.location(position)
        val newLocation = writer.allocator.allocate(1)
        var committed = false
        val temporary = files.fileSystem.openUniqueTemporarySink(
            directory = directory,
            prefix = ".mcc-",
            suffix = ".tmp",
        )
        try {
            writePayload(temporary.sink, compressedLength, block)

            val stub = RegionChunkRecordHeader(
                length = 1,
                compression = compression,
                external = true,
            ).encode()
            handle.write(
                newLocation.byteOffset,
                stub,
                0,
                stub.size,
            )
            if (writer.syncWrites) {
                handle.flushDurably(files.fileSystem, path)
            }
            committed = true
            writer.header.set(position, newLocation, systemEpochSeconds())
            writeHeader(writer)
            files.fileSystem.moveReplacing(
                temporary.path,
                externalPath(chunkPosition),
            )
            writer.allocator.free(oldLocation)
        } catch (failure: Throwable) {
            if (!committed) writer.allocator.free(newLocation)
            files.fileSystem.deleteIfExistsPreserving(
                temporary.path,
                failure,
            )
            throw failure
        }
    }

    private fun writePayload(
        sink: Sink,
        compressedLength: Long,
        block: BufferedSink.() -> Unit,
    ) {
        val fixedLength = FixedLengthSink(sink, compressedLength)
        val buffered = fixedLength.buffer()
        useResource(buffered, { it.close() }) {
            it.block()
        }
        fixedLength.requireComplete()
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
            syncWrites: Boolean = true,
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
                syncWrites = syncWrites,
            )
        }

        internal fun open(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            syncWrites: Boolean = true,
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

private class FixedLengthSink(
    private val delegate: Sink,
    private val expectedBytes: Long,
) : Sink {
    private var bytesWritten = 0L

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L)
        if (byteCount > expectedBytes - bytesWritten) {
            throw WorldIOException("Compressed payload exceeds declared length $expectedBytes")
        }
        delegate.write(source, byteCount)
        bytesWritten += byteCount
    }

    override fun flush() = delegate.flush()

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = delegate.close()

    fun requireComplete() {
        if (bytesWritten != expectedBytes) {
            throw WorldIOException(
                "Compressed payload wrote $bytesWritten byte(s), expected $expectedBytes",
            )
        }
    }
}

internal fun validatedCompressedPayload(
    position: ChunkPosition,
    chunk: RegionChunk,
): ByteArray {
    return chunk.payload.compressedBytes
        ?: throw RegionFormatException(
            "External chunk $position has not been resolved",
        )
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
