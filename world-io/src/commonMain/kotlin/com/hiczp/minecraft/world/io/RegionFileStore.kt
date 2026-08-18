package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.readByteArray
import okio.*
import kotlin.time.Clock
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

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
 * operations accept either local coordinates or absolute coordinates validated against the opened Region.
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

    fun readChunk(position: LocalChunkPosition): RegionChunk? {
        return readChunk(position) { info, source ->
            val bytes = source.readByteArray()
            val payload = if (info.external) {
                RegionChunkPayload.External(bytes)
            } else {
                RegionChunkPayload.Inline(bytes)
            }
            RegionChunk(info.compression, payload, info.timestamp)
        }
    }

    fun readChunk(position: ChunkPosition): RegionChunk? = readChunk(regionPosition.local(position))

    /** Lends one compressed chunk stream without retaining its payload. */
    fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? {
        checkOpen()
        return readStoredChunk(position, headerForRead(), block)
    }

    fun <T> readChunk(
        position: ChunkPosition,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? = readChunk(regionPosition.local(position), block)

    fun readRegion(): RegionFile = readRegion {
        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
        for (position in chunkPositions) {
            readChunk(position)?.let {
                chunks[position] = it
            }
        }
        RegionFile(chunks)
    }

    /** Lends one Header snapshot and streaming access to all of its chunks. */
    fun <T> readRegion(block: RegionReadScope.() -> T): T {
        checkOpen()
        val scope = RegionReadScope(this, headerForRead())
        return try {
            scope.block()
        } finally {
            scope.invalidate()
        }
    }

    fun doesChunkExist(position: LocalChunkPosition): Boolean {
        checkOpen()
        return doesChunkExist(position, headerForRead())
    }

    fun doesChunkExist(position: ChunkPosition): Boolean = doesChunkExist(regionPosition.local(position))

    internal fun doesChunkExist(
        position: LocalChunkPosition,
        header: RegionHeader,
    ): Boolean {
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
                externalPath(position),
            )?.isRegularFile == true
        }
        return record.length != 0 &&
                record.compressedLength >= 0 &&
                record.compressedLength <=
                location.allocatedBytes - REGION_CHUNK_RECORD_HEADER_BYTES
    }

    /** Writes one chunk with automatic timestamp and inline/external selection. */
    fun writeChunk(
        position: LocalChunkPosition,
        chunk: RegionChunk,
    ) {
        checkOpen()
        val compressedBytes = validatedCompressedPayload(regionPosition.chunk(position), chunk)
        writeChunk(position, chunk.compression, compressedBytes.size.toLong()) { sink ->
            sink.write(compressedBytes)
        }
    }

    fun writeChunk(
        position: ChunkPosition,
        chunk: RegionChunk,
    ) = writeChunk(regionPosition.local(position), chunk)

    /**
     * Writes one already-compressed payload directly from [block].
     *
     * [compressedLength] must be known before [block] because Anvil stores it before the payload
     * and uses it to allocate sectors. The callback must write exactly that many bytes.
     */
    fun writeChunk(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) {
        checkOpen()
        require(compressedLength >= 0L) { "Compressed length must be non-negative" }
        val writer = requireWriter()
        if (shouldStoreExternally(compressedLength)) {
            writeExternal(position, compression, compressedLength, writer, block)
        } else {
            val inlineSectors = regionSectorsForBytes(
                REGION_CHUNK_RECORD_HEADER_BYTES.toLong() + compressedLength,
            )
            writeInternal(position, compression, compressedLength, inlineSectors, writer, block)
        }
    }

    fun writeChunk(
        position: ChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) = writeChunk(regionPosition.local(position), compression, compressedLength, block)

    fun clearChunk(position: LocalChunkPosition) {
        checkOpen()
        val writer = requireWriter()
        val oldLocation = writer.header.location(position) ?: return
        writer.header.set(
            position,
            location = null,
            timestamp = systemEpochSeconds(),
        )
        writeHeader(writer)
        files.fileSystem.deleteIfExists(externalPath(position))
        writer.allocator.free(oldLocation)
    }

    fun clearChunk(position: ChunkPosition) = clearChunk(regionPosition.local(position))

    /** Replaces the complete logical chunk set through the streaming Region path. */
    fun writeRegion(region: RegionFile) {
        validateRegionPayloads(regionPosition, region)
        writeRegion {
            region.chunks.forEach { (position, chunk) ->
                writeChunk(position, chunk)
            }
        }
    }

    /** Streams a complete Region replacement and commits its Header once after [block] returns. */
    fun writeRegion(block: RegionWriteScope.() -> Unit) {
        checkOpen()
        val writer = requireWriter()
        val batch = RegionWriteBatch()
        val scope = RegionWriteScope(regionPosition) { position, compression, compressedLength, writeBlock ->
            check(batch.failure == null) { "Region write has already failed" }
            try {
                require(compressedLength >= 0L) { "Compressed length must be non-negative" }
                check(batch.positions.add(position)) { "Chunk $position was written more than once" }
                batch.staged += stageRegionChunk(
                    position = position,
                    compression = compression,
                    compressedLength = compressedLength,
                    writer = writer,
                    block = writeBlock,
                )
            } catch (caught: Throwable) {
                batch.failure = caught
                throw caught
            }
        }
        try {
            try {
                scope.block()
                batch.failure?.let { throw it }
            } finally {
                scope.invalidate()
            }
            commitRegionBatch(batch, writer)
        } catch (failure: Throwable) {
            throw rollbackRegionBatch(batch, writer, failure)
        }
    }

    fun clearRegion() {
        writeRegion {}
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

    private fun <T> readStoredChunk(
        position: LocalChunkPosition,
        header: RegionHeader,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
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
            val externalPath = externalPath(position)
            files.readFile(externalPath) { source, size ->
                readPayload(
                    position,
                    RegionChunkStreamInfo(record.compression, size, external = true, timestamp),
                    source,
                    block,
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
                readPayload(
                    position,
                    RegionChunkStreamInfo(
                        record.compression,
                        record.compressedLength.toLong(),
                        external = false,
                        timestamp,
                    ),
                    source,
                    block,
                )
            }
        }
    }

    internal fun <T> readChunk(
        position: LocalChunkPosition,
        header: RegionHeader,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T? {
        checkOpen()
        return readStoredChunk(position, header, block)
    }

    private fun writeInternal(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        allocatedSectors: Int,
        writer: RegionWriterState,
        block: (KotlinxSink) -> Unit,
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
            files.fileSystem.deleteIfExists(externalPath(position))
            writer.allocator.free(oldLocation)
        } catch (failure: Throwable) {
            if (!committed) writer.allocator.free(newLocation)
            throw failure
        }
    }

    private fun writeExternal(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        writer: RegionWriterState,
        block: (KotlinxSink) -> Unit,
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
                externalPath(position),
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

    private fun commitRegionBatch(
        batch: RegionWriteBatch,
        writer: RegionWriterState,
    ) {
        val nextHeader = writer.header.copy()
        val timestamp = systemEpochSeconds()
        batch.staged.forEach { chunk ->
            nextHeader.set(chunk.position, chunk.newLocation, timestamp)
        }
        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            if (position in batch.positions) continue
            val oldLocation = writer.header.location(position) ?: continue
            batch.cleared += ClearedRegionChunk(position, oldLocation)
            nextHeader.set(position, location = null, timestamp = timestamp)
        }
        if (writer.syncWrites && batch.staged.isNotEmpty()) {
            handle.flushDurably(files.fileSystem, path)
        }
        writer.header = nextHeader
        batch.headerAttempted = true
        writeHeader(writer)

        var failure: Throwable? = null
        batch.staged.forEach { chunk ->
            try {
                val temporary = chunk.externalTemporary
                if (temporary == null) {
                    files.fileSystem.deleteIfExists(externalPath(chunk.position))
                } else {
                    files.fileSystem.moveReplacing(temporary, externalPath(chunk.position))
                }
                writer.allocator.free(chunk.oldLocation)
            } catch (caught: Throwable) {
                failure = combineFailures(failure, caught)
                chunk.externalTemporary?.let { temporary ->
                    try {
                        files.fileSystem.deleteIfExists(temporary)
                    } catch (cleanupFailure: Throwable) {
                        failure = combineFailures(failure, cleanupFailure)
                    }
                }
            }
        }
        batch.cleared.forEach { chunk ->
            try {
                files.fileSystem.deleteIfExists(externalPath(chunk.position))
                writer.allocator.free(chunk.oldLocation)
            } catch (caught: Throwable) {
                failure = combineFailures(failure, caught)
            }
        }
        failure?.let { throw it }
    }

    private fun stageRegionChunk(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        writer: RegionWriterState,
        block: (KotlinxSink) -> Unit,
    ): StagedRegionChunk {
        val external = shouldStoreExternally(compressedLength)
        val sectorCount = if (external) {
            1
        } else {
            regionSectorsForBytes(
                REGION_CHUNK_RECORD_HEADER_BYTES + compressedLength,
            )
        }
        val oldLocation = writer.header.location(position)
        val newLocation = writer.allocator.allocate(sectorCount)
        var temporaryPath: Path? = null
        try {
            if (external) {
                val temporary = files.fileSystem.openUniqueTemporarySink(
                    directory = directory,
                    prefix = ".mcc-",
                    suffix = ".tmp",
                )
                temporaryPath = temporary.path
                writePayload(temporary.sink, compressedLength, block)
                val stub = RegionChunkRecordHeader(
                    length = 1,
                    compression = compression,
                    external = true,
                ).encode()
                handle.write(newLocation.byteOffset, stub, 0, stub.size)
            } else {
                val recordHeader = RegionChunkRecordHeader(
                    length = compressedLength.toInt() + 1,
                    compression = compression,
                    external = false,
                ).encode()
                handle.write(newLocation.byteOffset, recordHeader, 0, recordHeader.size)
                writePayload(
                    handle.sink(newLocation.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES),
                    compressedLength,
                    block,
                )
            }
            return StagedRegionChunk(
                position = position,
                oldLocation = oldLocation,
                newLocation = newLocation,
                externalTemporary = temporaryPath,
            )
        } catch (failure: Throwable) {
            writer.allocator.free(newLocation)
            temporaryPath?.let { temporary ->
                files.fileSystem.deleteIfExistsPreserving(temporary, failure)
            }
            throw failure
        }
    }

    private fun rollbackRegionBatch(
        batch: RegionWriteBatch,
        writer: RegionWriterState,
        failure: Throwable,
    ): Throwable {
        var result = failure
        batch.staged.forEach { chunk ->
            if (!batch.headerAttempted) writer.allocator.free(chunk.newLocation)
            chunk.externalTemporary?.let { temporary ->
                try {
                    files.fileSystem.deleteIfExists(temporary)
                } catch (cleanupFailure: Throwable) {
                    result = combineFailures(result, cleanupFailure)
                }
            }
        }
        return result
    }

    private fun writePayload(
        sink: Sink,
        compressedLength: Long,
        block: (KotlinxSink) -> Unit,
    ) {
        val fixedLength = FixedLengthSink(sink, compressedLength)
        val converted = fixedLength.asKotlinxIoRawSink().buffered()
        withOkioIoExceptions("Cannot write compressed Chunk payload") {
            useResource(converted, { it.close() }, block)
        }
        fixedLength.requireComplete()
    }

    private fun <T> readPayload(
        position: LocalChunkPosition,
        info: RegionChunkStreamInfo,
        source: BufferedSource,
        block: (RegionChunkStreamInfo, KotlinxSource) -> T,
    ): T = withOkioIoExceptions("Cannot read Chunk $position payload") {
        val converted = source.asKotlinxIoRawSource().buffered()
        val value = block(info, converted)
        if (!converted.exhausted()) {
            throw WorldIOException("Chunk $position payload was not fully consumed")
        }
        value
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

    private fun externalPath(position: LocalChunkPosition): Path {
        val absolute = regionPosition.chunk(position)
        return directory / "c.${absolute.x}.${absolute.z}.mcc"
    }

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
            return openMutable(
                files = WorldFileAccess.mutable(fileSystem),
                directory = directory,
                position = position,
                syncWrites = syncWrites,
            )
        }

        internal fun openMutable(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            syncWrites: Boolean = true,
        ): RegionFileStore {
            files.requireWritable()
            files.fileSystem.createDirectories(directory)
            return openHandle(files, directory, position, syncWrites)
        }

        internal fun openExistingMutable(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            syncWrites: Boolean = true,
        ): RegionFileStore? {
            files.requireWritable()
            val path = directory / "r.${position.x}.${position.z}.mca"
            val metadata = files.fileSystem.metadataOrNull(path) ?: return null
            if (!metadata.isRegularFile) {
                throw WorldIOException("Path is not a regular file: $path")
            }
            return openHandle(files, directory, position, syncWrites)
        }

        internal fun openLive(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
        ): RegionFileStore {
            require(files.liveReadOnly) { "Live region access requires live read-only files" }
            val path = directory / "r.${position.x}.${position.z}.mca"
            val metadata = files.fileSystem.metadataOrNull(path)
                ?: throw WorldIOException("Region file does not exist: $path")
            if (!metadata.isRegularFile) {
                throw WorldIOException("Path is not a regular file: $path")
            }
            return openHandle(files, directory, position, syncWrites = false)
        }

        private fun openHandle(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            syncWrites: Boolean,
        ): RegionFileStore {
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

internal fun validateRegionPayloads(
    position: RegionPosition,
    region: RegionFile,
) {
    region.chunks.forEach { (local, chunk) ->
        validatedCompressedPayload(position.chunk(local), chunk)
    }
}

private data class RegionWriterState(
    var header: RegionHeader,
    val allocator: RegionSectorAllocator,
    val syncWrites: Boolean,
)

private data class StagedRegionChunk(
    val position: LocalChunkPosition,
    val oldLocation: RegionLocation?,
    val newLocation: RegionLocation,
    val externalTemporary: Path?,
)

private data class ClearedRegionChunk(
    val position: LocalChunkPosition,
    val oldLocation: RegionLocation,
)

private class RegionWriteBatch {
    val positions = mutableSetOf<LocalChunkPosition>()
    val staged = mutableListOf<StagedRegionChunk>()
    val cleared = mutableListOf<ClearedRegionChunk>()
    var headerAttempted = false
    var failure: Throwable? = null
}

private fun shouldStoreExternally(compressedLength: Long): Boolean {
    val maximumInlineBytes = (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1L) * REGION_SECTOR_BYTES
    return compressedLength > maximumInlineBytes - REGION_CHUNK_RECORD_HEADER_BYTES
}

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

internal fun parseRegionFileName(name: String): RegionPosition? {
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
