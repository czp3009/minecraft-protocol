package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
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
 * This mutable primitive is private to one [RegionState]. The owning [RegionStorage] provides all
 * read/write/close coordination; callers cannot open or retain the physical file object directly.
 */
internal class MutableRegionFile private constructor(
    private val files: WorldFileAccess,
    val position: RegionPosition,
    private val directory: Path,
    val path: Path,
    private val handle: FileHandle,
    private val writer: RegionWriterState,
) {
    private var closed = false

    fun hasChunk(local: LocalChunkPosition): Boolean {
        checkOpen()
        return hasChunk(local, headerForRead())
    }

    internal fun hasChunk(local: LocalChunkPosition, header: RegionHeader): Boolean {
        checkOpen()
        return header.hasChunk(local)
    }

    fun readChunkCount(): Int {
        checkOpen()
        return headerForRead().chunkCount
    }

    fun readLocalChunkPositions(): List<LocalChunkPosition> {
        checkOpen()
        return headerForRead().localChunkPositions().toList()
    }

    fun readChunkInfo(local: LocalChunkPosition): RegionChunkInfo? {
        checkOpen()
        return readChunkInfo(local, headerForRead())
    }

    fun readChunkInfos(): List<RegionChunkInfo> = withReadScope { chunkInfos.toList() }

    internal fun readChunkInfo(local: LocalChunkPosition, header: RegionHeader): RegionChunkInfo? {
        checkOpen()
        return readRegionChunkInfo(files.fileSystem, directory, position, handle, header, local)
    }

    /** Lends one complete compressed Chunk stream without retaining its payload. */
    fun <R> withCompressedChunkSource(
        local: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        checkOpen()
        return readStoredChunk(local, headerForRead(), block)
    }

    internal fun <R> withCompressedChunkSource(
        local: LocalChunkPosition,
        header: RegionHeader,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        checkOpen()
        return readStoredChunk(local, header, block)
    }

    fun readCompressedChunk(local: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(local) { info, source ->
            CompressedChunk.readFromSource(source, info.compression)
        }

    fun readAnvilRegion(): AnvilRegion = withReadScope {
        val chunks = linkedMapOf<LocalChunkPosition, AnvilChunkRecord>()
        chunkInfos.forEach { listedInfo ->
            withCompressedChunkSource(listedInfo.localPosition) { info, source ->
                chunks[info.localPosition] = AnvilChunkRecord(
                    compression = info.compression,
                    content = CompressedChunk.readFromSource(source, info.compression),
                    placement = info.placement,
                    timestampEpochSeconds = info.timestampEpochSeconds,
                )
            }
        }
        AnvilRegion(chunks)
    }

    /** Lends one Header snapshot and streaming access to all referenced chunks. */
    fun <R> withReadScope(block: RegionReadScope.() -> R): R {
        checkOpen()
        return RegionReadScope(this, headerForRead()).use(block)
    }

    /** Writes one Chunk with automatic timestamp and inline/external selection. */
    fun writeCompressedChunk(local: LocalChunkPosition, chunk: CompressedChunkInput) =
        writeCompressedChunk(local, chunk.compression, chunk.compressedByteCount, chunk::writeTo)

    /**
     * Writes one already-compressed payload directly from [block].
     *
     * [compressedByteCount] must be known before [block] because Anvil stores it before the payload
     * and uses it to allocate sectors. The callback must write exactly that many bytes.
     */
    fun writeCompressedChunk(
        local: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) {
        checkOpen()
        require(compressedByteCount >= 0L) { "Compressed byte count must be non-negative" }
        val writer = writer
        if (shouldStoreExternally(compressedByteCount)) {
            writeExternal(local, compression, compressedByteCount, writer, block)
        } else {
            val inlineSectors = regionSectorsForBytes(
                REGION_CHUNK_RECORD_HEADER_BYTES.toLong() + compressedByteCount,
            )
            writeInternal(local, compression, compressedByteCount, inlineSectors, writer, block)
        }
    }

    fun removeChunk(local: LocalChunkPosition): Boolean {
        checkOpen()
        val writer = writer
        val oldLocation = writer.header.location(local) ?: return false
        writer.header.set(
            local,
            location = null,
            timestamp = Clock.System.now().epochSeconds.toInt(),
        )
        writeHeader(writer)
        files.fileSystem.delete(externalPath(local), mustExist = false)
        writer.allocator.free(oldLocation)
        return true
    }

    /** Replaces the complete logical Chunk set through the streaming Region path. */
    fun replaceRegion(region: AnvilRegion) {
        replaceRegion {
            region.chunks.forEach { (local, record) ->
                val content = record.content ?: throw AnvilFormatException(
                    "External Chunk ${position.chunk(local)} has not been resolved",
                )
                writeCompressedChunk(local, content)
            }
        }
    }

    fun replaceRegion(chunks: Collection<RegionChunkInput>) {
        replaceRegion {
            chunks.forEach { input -> writeCompressedChunk(input.position, input.content) }
        }
    }

    /** Streams a complete Region replacement and commits its Header once after [block] returns. */
    fun replaceRegion(block: RegionReplacementScope.() -> Unit) {
        checkOpen()
        val writer = writer
        val batch = RegionWriteBatch()
        val scope = RegionReplacementScope(position) { local, compression, compressedLength, writeBlock ->
            check(batch.failure == null) { "Region write has already failed" }
            try {
                require(compressedLength >= 0L) { "Compressed length must be non-negative" }
                check(batch.locals.add(local)) { "Chunk $local was written more than once" }
                batch.staged += stageRegionChunk(
                    local = local,
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

    fun clear() = replaceRegion {}

    fun flush() {
        checkOpen()
        handle.flushDurably(files.fileSystem, path)
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
        check(!closed) { "Region file is closed: $path" }
    }

    private fun <R> readStoredChunk(
        local: LocalChunkPosition,
        header: RegionHeader,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        val info = readChunkInfo(local, header) ?: return null
        return if (info.placement == AnvilChunkPlacement.EXTERNAL) {
            val externalPath = externalPath(local)
            files.readFile(externalPath) { source, size ->
                readPayload(
                    local,
                    info.copy(compressedByteCount = size),
                    source,
                    block,
                )
            }
        } else {
            val location = header.location(local)!!
            val source = handle.source(
                location.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES,
            ).limit(info.compressedByteCount).buffer()
            useResource(source, { it.close() }) {
                readPayload(local, info, source, block)
            }
        }
    }

    private fun writeInternal(
        local: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        allocatedSectors: Int,
        writer: RegionWriterState,
        block: (KotlinxSink) -> Unit,
    ) {
        val oldLocation = writer.header.location(local)
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
            writer.header.set(local, newLocation, Clock.System.now().epochSeconds.toInt())
            writeHeader(writer)
            files.fileSystem.delete(externalPath(local), mustExist = false)
            writer.allocator.free(oldLocation)
        } catch (failure: Throwable) {
            if (!committed) writer.allocator.free(newLocation)
            throw failure
        }
    }

    private fun writeExternal(
        local: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        writer: RegionWriterState,
        block: (KotlinxSink) -> Unit,
    ) {
        val oldLocation = writer.header.location(local)
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
            writer.header.set(local, newLocation, Clock.System.now().epochSeconds.toInt())
            writeHeader(writer)
            files.fileSystem.moveReplacing(
                temporary.path,
                externalPath(local),
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
        val timestamp = Clock.System.now().epochSeconds.toInt()
        batch.staged.forEach { chunk ->
            nextHeader.set(chunk.local, chunk.newLocation, timestamp)
        }
        for (index in 0 until REGION_CHUNK_COUNT) {
            val local = LocalChunkPosition.fromIndex(index)
            if (local in batch.locals) continue
            val oldLocation = writer.header.location(local) ?: continue
            batch.cleared += ClearedRegionChunk(local, oldLocation)
            nextHeader.set(local, location = null, timestamp = timestamp)
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
                    files.fileSystem.delete(externalPath(chunk.local), mustExist = false)
                } else {
                    files.fileSystem.moveReplacing(temporary, externalPath(chunk.local))
                }
                writer.allocator.free(chunk.oldLocation)
            } catch (caught: Throwable) {
                failure = combineFailures(failure, caught)
                chunk.externalTemporary?.let { temporary ->
                    try {
                        files.fileSystem.delete(temporary, mustExist = false)
                    } catch (cleanupFailure: Throwable) {
                        failure = combineFailures(failure, cleanupFailure)
                    }
                }
            }
        }
        batch.cleared.forEach { chunk ->
            try {
                files.fileSystem.delete(externalPath(chunk.local), mustExist = false)
                writer.allocator.free(chunk.oldLocation)
            } catch (caught: Throwable) {
                failure = combineFailures(failure, caught)
            }
        }
        failure?.let { throw it }
    }

    private fun stageRegionChunk(
        local: LocalChunkPosition,
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
        val oldLocation = writer.header.location(local)
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
                local = local,
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
                    files.fileSystem.delete(temporary, mustExist = false)
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

    private fun <R> readPayload(
        local: LocalChunkPosition,
        info: RegionChunkInfo,
        source: BufferedSource,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R = withOkioIoExceptions("Cannot read Chunk $local payload") {
        val converted = source.asKotlinxIoRawSource().buffered()
        val value = block(info, converted)
        if (!converted.exhausted()) {
            throw WorldIOException("Chunk $local payload was not fully consumed")
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

    private fun headerForRead(): RegionHeader = writer.header

    private fun externalPath(local: LocalChunkPosition): Path {
        return externalChunkPath(directory, position, local)
    }

    companion object {
        internal fun open(
            regionFile: Path,
            fileSystem: FileSystem = systemFileSystem,
            syncWrites: Boolean = true,
        ): MutableRegionFile {
            val directory = regionFile.parent
                ?: throw WorldIOException("Region file has no parent directory: $regionFile")
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
        ): MutableRegionFile {
            files.requireWritable()
            files.fileSystem.createDirectories(directory)
            return openHandle(files, directory, position, syncWrites)
        }

        internal fun openExistingMutable(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            syncWrites: Boolean = true,
        ): MutableRegionFile? {
            files.requireWritable()
            val path = regionFilePath(directory, position)
            val metadata = files.fileSystem.metadataOrNull(path) ?: return null
            if (!metadata.isRegularFile) {
                throw WorldIOException("Path is not a regular file: $path")
            }
            return openHandle(files, directory, position, syncWrites)
        }

        private fun openHandle(
            files: WorldFileAccess,
            directory: Path,
            position: RegionPosition,
            syncWrites: Boolean,
        ): MutableRegionFile {
            val path = regionFilePath(directory, position)
            val handle = files.openRegionHandle(path)
            var failure: Throwable? = null
            try {
                val header = readUsableHeader(handle)
                val writer = RegionWriterState(
                    header = header,
                    allocator = allocatorFor(header),
                    syncWrites = syncWrites,
                )
                return MutableRegionFile(
                    files = files,
                    position = position,
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

private data class RegionWriterState(
    var header: RegionHeader,
    val allocator: RegionSectorAllocator,
    val syncWrites: Boolean,
)

private data class StagedRegionChunk(
    val local: LocalChunkPosition,
    val oldLocation: RegionLocation?,
    val newLocation: RegionLocation,
    val externalTemporary: Path?,
)

private data class ClearedRegionChunk(
    val local: LocalChunkPosition,
    val oldLocation: RegionLocation,
)

private class RegionWriteBatch {
    val locals = mutableSetOf<LocalChunkPosition>()
    val staged = mutableListOf<StagedRegionChunk>()
    val cleared = mutableListOf<ClearedRegionChunk>()
    var headerAttempted = false
    var failure: Throwable? = null
}

private fun shouldStoreExternally(compressedLength: Long): Boolean {
    val maximumInlineBytes = (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1L) * REGION_SECTOR_BYTES
    return compressedLength > maximumInlineBytes - REGION_CHUNK_RECORD_HEADER_BYTES
}

internal fun readUsableHeader(handle: FileHandle): RegionHeader {
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

internal fun snapshotRegionPositions(fileSystem: FileSystem, directory: Path): List<RegionPosition> {
    val metadata = fileSystem.metadataOrNull(directory) ?: return emptyList()
    if (!metadata.isDirectory) {
        throw WorldIOException("Region path is not a directory: $directory")
    }
    return fileSystem.list(directory)
        .mapNotNull { path -> parseRegionFileName(path.name) }
        .distinct()
        .sortedWith(compareBy(RegionPosition::x, RegionPosition::z))
}

internal fun regionFilePath(directory: Path, position: RegionPosition): Path =
    directory / "r.${position.x}.${position.z}.mca"

internal fun externalChunkPath(
    directory: Path,
    region: RegionPosition,
    local: LocalChunkPosition,
): Path {
    val position = region.chunk(local)
    return directory / "c.${position.x}.${position.z}.mcc"
}

internal fun readRegionChunkInfo(
    fileSystem: FileSystem,
    directory: Path,
    region: RegionPosition,
    handle: FileHandle,
    header: RegionHeader,
    local: LocalChunkPosition,
): RegionChunkInfo? {
    val location = header.location(local) ?: return null
    val prefix = handle.readAtMost(location.byteOffset, REGION_CHUNK_RECORD_HEADER_BYTES)
    if (prefix.size < REGION_CHUNK_RECORD_HEADER_BYTES) {
        throw AnvilFormatException("Chunk $local has a truncated record header")
    }
    val record = RegionChunkRecordHeader.decode(prefix)
    if (record.length == 0) {
        throw AnvilFormatException("Chunk $local has an allocated but missing stream")
    }
    val timestamp = header.timestamp(local)
    if (record.external) {
        if (record.compressedLength != 0) {
            throw AnvilFormatException("External Chunk $local also contains inline bytes")
        }
        val externalPath = externalChunkPath(directory, region, local)
        val metadata = fileSystem.metadataOrNull(externalPath) ?: return null
        if (!metadata.isRegularFile) return null
        val compressedByteCount = metadata.size
            ?: throw WorldIOException("External Chunk file has no size: $externalPath")
        if (compressedByteCount < 0L) {
            throw WorldIOException("External Chunk file has a negative size: $externalPath")
        }
        return RegionChunkInfo(
            region = region,
            localPosition = local,
            compression = record.compression,
            compressedByteCount = compressedByteCount,
            placement = AnvilChunkPlacement.EXTERNAL,
            timestampEpochSeconds = timestamp,
        )
    }
    val maximumPayload = location.allocatedBytes - REGION_CHUNK_RECORD_HEADER_BYTES
    if (record.compressedLength !in 0..maximumPayload) {
        throw AnvilFormatException(
            "Chunk $local has invalid length ${record.length} in ${location.sectorCount} allocated sectors",
        )
    }
    return RegionChunkInfo(
        region = region,
        localPosition = local,
        compression = record.compression,
        compressedByteCount = record.compressedLength.toLong(),
        placement = AnvilChunkPlacement.INLINE,
        timestampEpochSeconds = timestamp,
    )
}

internal fun FileHandle.readAtMost(offset: Long, byteCount: Int): ByteArray {
    require(offset >= 0)
    require(byteCount >= 0)
    if (byteCount == 0) return ByteArray(0)
    val buffer = Buffer()
    val read = read(offset, buffer, byteCount.toLong())
    return if (read < 0L) ByteArray(0) else buffer.readByteArray()
}
