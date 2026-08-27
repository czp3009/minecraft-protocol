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
    private val worldFileAccess: WorldFileAccess,
    override val regionPosition: RegionPosition,
    private val directory: Path,
    override val path: Path,
    private val fileHandle: FileHandle,
    private val regionWriterState: RegionWriterState,
) : RegionReadAccess {
    private var closed = false

    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean {
        checkOpen()
        return hasChunk(localChunkPosition, headerForRead())
    }

    override fun hasChunk(localChunkPosition: LocalChunkPosition, regionHeader: RegionHeader): Boolean {
        checkOpen()
        return regionHeader.hasChunk(localChunkPosition)
    }

    fun readChunkCount(): Int {
        checkOpen()
        return headerForRead().chunkCount
    }

    fun readLocalChunkPositions(): List<LocalChunkPosition> {
        checkOpen()
        return headerForRead().localChunkPositions().toList()
    }

    fun readChunkInfo(localChunkPosition: LocalChunkPosition): RegionChunkInfo? {
        checkOpen()
        return readChunkInfo(localChunkPosition, headerForRead())
    }

    fun readChunkInfos(): List<RegionChunkInfo> = withReadScope { chunkInfos.toList() }

    override fun readChunkInfo(localChunkPosition: LocalChunkPosition, regionHeader: RegionHeader): RegionChunkInfo? {
        checkOpen()
        return readRegionChunkInfo(worldFileAccess.fileSystem, directory, regionPosition, fileHandle, regionHeader, localChunkPosition)
    }

    /** Lends one complete compressed Chunk stream without retaining its payload. */
    fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        checkOpen()
        return readStoredChunk(localChunkPosition, headerForRead(), block)
    }

    override fun <R> withCompressedChunkSource(
        localChunkPosition: LocalChunkPosition,
        regionHeader: RegionHeader,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        checkOpen()
        return readStoredChunk(localChunkPosition, regionHeader, block)
    }

    fun readCompressedChunk(localChunkPosition: LocalChunkPosition): CompressedChunk? =
        withCompressedChunkSource(localChunkPosition) { regionChunkInfo, source ->
            CompressedChunk.readFromSource(source, regionChunkInfo.compression)
        }

    fun readAnvilRegion(): AnvilRegion = withReadScope {
        val chunks = linkedMapOf<LocalChunkPosition, AnvilChunkRecord>()
        chunkInfos.forEach { listedInfo ->
            withCompressedChunkSource(listedInfo.localChunkPosition) { regionChunkInfo, source ->
                chunks[regionChunkInfo.localChunkPosition] = AnvilChunkRecord(
                    compression = regionChunkInfo.compression,
                    content = CompressedChunk.readFromSource(source, regionChunkInfo.compression),
                    anvilChunkPlacement = regionChunkInfo.anvilChunkPlacement,
                    timestampEpochSeconds = regionChunkInfo.timestampEpochSeconds,
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
    fun writeCompressedChunk(localChunkPosition: LocalChunkPosition, compressedChunkInput: CompressedChunkInput) =
        writeCompressedChunk(localChunkPosition, compressedChunkInput.compression, compressedChunkInput.compressedByteCount, compressedChunkInput::writeTo)

    /**
     * Writes one already-compressed payload directly from [block].
     *
     * [compressedByteCount] must be known before [block] because Anvil stores it before the payload
     * and uses it to allocate sectors. The callback must write exactly that many bytes.
     */
    fun writeCompressedChunk(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedByteCount: Long,
        block: (KotlinxSink) -> Unit,
    ) {
        checkOpen()
        require(compressedByteCount >= 0L) { "Compressed byte count must be non-negative" }
        val regionWriterState = this@MutableRegionFile.regionWriterState
        if (shouldStoreExternally(compressedByteCount)) {
            writeExternal(localChunkPosition, compression, compressedByteCount, regionWriterState, block)
        } else {
            val inlineSectors = regionSectorsForBytes(
                REGION_CHUNK_RECORD_HEADER_BYTES.toLong() + compressedByteCount,
            )
            writeInternal(localChunkPosition, compression, compressedByteCount, inlineSectors, regionWriterState, block)
        }
    }

    fun removeChunk(localChunkPosition: LocalChunkPosition): Boolean {
        checkOpen()
        val regionWriterState = this@MutableRegionFile.regionWriterState
        val oldLocation = regionWriterState.regionHeader.location(localChunkPosition) ?: return false
        regionWriterState.regionHeader.set(
            localChunkPosition,
            regionLocation = null,
            timestamp = Clock.System.now().epochSeconds.toInt(),
        )
        writeHeader(regionWriterState)
        worldFileAccess.fileSystem.delete(externalPath(localChunkPosition), mustExist = false)
        regionWriterState.regionSectorAllocator.free(oldLocation)
        return true
    }

    /** Replaces the complete logical Chunk set through the streaming Region path. */
    fun replaceRegion(anvilRegion: AnvilRegion) {
        replaceRegion {
            anvilRegion.chunks.forEach { (localChunkPosition, anvilChunkRecord) ->
                val content = anvilChunkRecord.content ?: throw AnvilFormatException(
                    "External Chunk ${regionPosition.chunk(localChunkPosition)} has not been resolved",
                )
                writeCompressedChunk(localChunkPosition, content)
            }
        }
    }

    fun replaceRegion(chunks: Collection<RegionChunkInput>) {
        replaceRegion {
            chunks.forEach { regionChunkInput -> writeCompressedChunk(regionChunkInput.localChunkPosition, regionChunkInput.content) }
        }
    }

    /** Streams a complete Region replacement and commits its Header once after [block] returns. */
    fun replaceRegion(block: RegionReplacementScope.() -> Unit) {
        checkOpen()
        val regionWriterState = this@MutableRegionFile.regionWriterState
        val regionWriteBatch = RegionWriteBatch()
        val regionReplacementScope =
            RegionReplacementScope(regionPosition) { localChunkPosition, compression, compressedLength, writeBlock ->
            check(regionWriteBatch.failure == null) { "Region write has already failed" }
            try {
                require(compressedLength >= 0L) { "Compressed length must be non-negative" }
                check(regionWriteBatch.locals.add(localChunkPosition)) { "Chunk $localChunkPosition was written more than once" }
                regionWriteBatch.staged += stageRegionChunk(
                    localChunkPosition = localChunkPosition,
                    compression = compression,
                    compressedLength = compressedLength,
                    regionWriterState = regionWriterState,
                    block = writeBlock,
                )
            } catch (caught: Throwable) {
                regionWriteBatch.failure = caught
                throw caught
            }
        }
        try {
            try {
                regionReplacementScope.block()
                regionWriteBatch.failure?.let { throw it }
            } finally {
                regionReplacementScope.invalidate()
            }
            commitRegionBatch(regionWriteBatch, regionWriterState)
        } catch (failure: Throwable) {
            throw rollbackRegionBatch(regionWriteBatch, regionWriterState, failure)
        }
    }

    fun clear() = replaceRegion {}

    fun flush() {
        checkOpen()
        fileHandle.flushDurably(worldFileAccess.fileSystem, path)
    }

    fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        val size = try {
            fileHandle.size()
        } catch (caught: Throwable) {
            failure = caught
            -1L
        }
        if (size > 0L) {
            val remainder = size % REGION_SECTOR_BYTES
            if (remainder != 0L) {
                try {
                    fileHandle.resize(size + REGION_SECTOR_BYTES - remainder)
                } catch (resizeFailure: Throwable) {
                    failure = combineFailures(failure, resizeFailure)
                }
            }
        }
        closeAllPreserving(
            failure,
            { fileHandle.flushDurably(worldFileAccess.fileSystem, path) },
            fileHandle::close,
        )
        failure?.let { throw it }
    }

    private fun checkOpen() {
        check(!closed) { "Region file is closed: $path" }
    }

    private fun <R> readStoredChunk(
        localChunkPosition: LocalChunkPosition,
        regionHeader: RegionHeader,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R? {
        val regionChunkInfo = readChunkInfo(localChunkPosition, regionHeader) ?: return null
        return if (regionChunkInfo.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL) {
            val externalPath = externalPath(localChunkPosition)
            worldFileAccess.readFile(externalPath) { bufferedSource, size ->
                readPayload(
                    localChunkPosition,
                    regionChunkInfo.copy(compressedByteCount = size),
                    bufferedSource,
                    block,
                )
            }
        } else {
            val regionLocation = regionHeader.location(localChunkPosition)!!
            val bufferedSource = fileHandle.source(
                regionLocation.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES,
            ).limit(regionChunkInfo.compressedByteCount).buffer()
            useResource(bufferedSource, { it.close() }) {
                readPayload(localChunkPosition, regionChunkInfo, bufferedSource, block)
            }
        }
    }

    private fun writeInternal(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        allocatedSectors: Int,
        regionWriterState: RegionWriterState,
        block: (KotlinxSink) -> Unit,
    ) {
        val oldLocation = regionWriterState.regionHeader.location(localChunkPosition)
        val newLocation = regionWriterState.regionSectorAllocator.allocate(allocatedSectors)
        var committed = false
        try {
            val encodedRegionChunkRecordHeader = RegionChunkRecordHeader(
                length = compressedLength.toInt() + 1,
                compression = compression,
                external = false,
            ).encode()
            fileHandle.write(newLocation.byteOffset, encodedRegionChunkRecordHeader, 0, encodedRegionChunkRecordHeader.size)
            writePayload(
                fileHandle.sink(newLocation.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES),
                compressedLength,
                block,
            )
            if (regionWriterState.syncWrites) {
                fileHandle.flushDurably(worldFileAccess.fileSystem, path)
            }
            committed = true
            regionWriterState.regionHeader.set(localChunkPosition, newLocation, Clock.System.now().epochSeconds.toInt())
            writeHeader(regionWriterState)
            worldFileAccess.fileSystem.delete(externalPath(localChunkPosition), mustExist = false)
            regionWriterState.regionSectorAllocator.free(oldLocation)
        } catch (failure: Throwable) {
            if (!committed) regionWriterState.regionSectorAllocator.free(newLocation)
            throw failure
        }
    }

    private fun writeExternal(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        regionWriterState: RegionWriterState,
        block: (KotlinxSink) -> Unit,
    ) {
        val oldLocation = regionWriterState.regionHeader.location(localChunkPosition)
        val newLocation = regionWriterState.regionSectorAllocator.allocate(1)
        var committed = false
        val temporaryFileSink = worldFileAccess.fileSystem.openUniqueTemporarySink(
            directory = directory,
            prefix = ".mcc-",
            suffix = ".tmp",
        )
        try {
            writePayload(temporaryFileSink.sink, compressedLength, block)

            val stub = RegionChunkRecordHeader(
                length = 1,
                compression = compression,
                external = true,
            ).encode()
            fileHandle.write(
                newLocation.byteOffset,
                stub,
                0,
                stub.size,
            )
            if (regionWriterState.syncWrites) {
                fileHandle.flushDurably(worldFileAccess.fileSystem, path)
            }
            committed = true
            regionWriterState.regionHeader.set(localChunkPosition, newLocation, Clock.System.now().epochSeconds.toInt())
            writeHeader(regionWriterState)
            worldFileAccess.fileSystem.moveReplacing(
                temporaryFileSink.path,
                externalPath(localChunkPosition),
            )
            regionWriterState.regionSectorAllocator.free(oldLocation)
        } catch (failure: Throwable) {
            if (!committed) regionWriterState.regionSectorAllocator.free(newLocation)
            worldFileAccess.fileSystem.deleteIfExistsPreserving(
                temporaryFileSink.path,
                failure,
            )
            throw failure
        }
    }

    private fun commitRegionBatch(
        regionWriteBatch: RegionWriteBatch,
        regionWriterState: RegionWriterState,
    ) {
        val nextHeader = regionWriterState.regionHeader.copy()
        val timestamp = Clock.System.now().epochSeconds.toInt()
        regionWriteBatch.staged.forEach { stagedRegionChunk ->
            nextHeader.set(stagedRegionChunk.localChunkPosition, stagedRegionChunk.newLocation, timestamp)
        }
        for (index in 0 until REGION_CHUNK_COUNT) {
            val localChunkPosition = LocalChunkPosition.fromIndex(index)
            if (localChunkPosition in regionWriteBatch.locals) continue
            val oldLocation = regionWriterState.regionHeader.location(localChunkPosition) ?: continue
            regionWriteBatch.cleared += ClearedRegionChunk(localChunkPosition, oldLocation)
            nextHeader.set(localChunkPosition, regionLocation = null, timestamp = timestamp)
        }
        if (regionWriterState.syncWrites && regionWriteBatch.staged.isNotEmpty()) {
            fileHandle.flushDurably(worldFileAccess.fileSystem, path)
        }
        regionWriterState.regionHeader = nextHeader
        regionWriteBatch.headerAttempted = true
        writeHeader(regionWriterState)

        var failure: Throwable? = null
        regionWriteBatch.staged.forEach { stagedRegionChunk ->
            try {
                val externalTemporary = stagedRegionChunk.externalTemporary
                if (externalTemporary == null) {
                    worldFileAccess.fileSystem.delete(externalPath(stagedRegionChunk.localChunkPosition), mustExist = false)
                } else {
                    worldFileAccess.fileSystem.moveReplacing(externalTemporary, externalPath(stagedRegionChunk.localChunkPosition))
                }
                regionWriterState.regionSectorAllocator.free(stagedRegionChunk.oldLocation)
            } catch (caught: Throwable) {
                failure = combineFailures(failure, caught)
                stagedRegionChunk.externalTemporary?.let { externalTemporary ->
                    try {
                        worldFileAccess.fileSystem.delete(externalTemporary, mustExist = false)
                    } catch (cleanupFailure: Throwable) {
                        failure = combineFailures(failure, cleanupFailure)
                    }
                }
            }
        }
        regionWriteBatch.cleared.forEach { clearedRegionChunk ->
            try {
                worldFileAccess.fileSystem.delete(externalPath(clearedRegionChunk.localChunkPosition), mustExist = false)
                regionWriterState.regionSectorAllocator.free(clearedRegionChunk.oldLocation)
            } catch (caught: Throwable) {
                failure = combineFailures(failure, caught)
            }
        }
        failure?.let { throw it }
    }

    private fun stageRegionChunk(
        localChunkPosition: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        regionWriterState: RegionWriterState,
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
        val oldLocation = regionWriterState.regionHeader.location(localChunkPosition)
        val newLocation = regionWriterState.regionSectorAllocator.allocate(sectorCount)
        var temporaryPath: Path? = null
        try {
            if (external) {
                val temporaryFileSink = worldFileAccess.fileSystem.openUniqueTemporarySink(
                    directory = directory,
                    prefix = ".mcc-",
                    suffix = ".tmp",
                )
                temporaryPath = temporaryFileSink.path
                writePayload(temporaryFileSink.sink, compressedLength, block)
                val stub = RegionChunkRecordHeader(
                    length = 1,
                    compression = compression,
                    external = true,
                ).encode()
                fileHandle.write(newLocation.byteOffset, stub, 0, stub.size)
            } else {
                val recordHeader = RegionChunkRecordHeader(
                    length = compressedLength.toInt() + 1,
                    compression = compression,
                    external = false,
                ).encode()
                fileHandle.write(newLocation.byteOffset, recordHeader, 0, recordHeader.size)
                writePayload(
                    fileHandle.sink(newLocation.byteOffset + REGION_CHUNK_RECORD_HEADER_BYTES),
                    compressedLength,
                    block,
                )
            }
            return StagedRegionChunk(
                localChunkPosition = localChunkPosition,
                oldLocation = oldLocation,
                newLocation = newLocation,
                externalTemporary = temporaryPath,
            )
        } catch (failure: Throwable) {
            regionWriterState.regionSectorAllocator.free(newLocation)
            temporaryPath?.let { temporary ->
                worldFileAccess.fileSystem.deleteIfExistsPreserving(temporary, failure)
            }
            throw failure
        }
    }

    private fun rollbackRegionBatch(
        regionWriteBatch: RegionWriteBatch,
        regionWriterState: RegionWriterState,
        failure: Throwable,
    ): Throwable {
        var result = failure
        regionWriteBatch.staged.forEach { stagedRegionChunk ->
            if (!regionWriteBatch.headerAttempted) regionWriterState.regionSectorAllocator.free(stagedRegionChunk.newLocation)
            stagedRegionChunk.externalTemporary?.let { temporary ->
                try {
                    worldFileAccess.fileSystem.delete(temporary, mustExist = false)
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
        val fixedLengthSink = FixedLengthSink(sink, compressedLength)
        val converted = fixedLengthSink.asKotlinxIoRawSink().buffered()
        withOkioIoExceptions("Cannot write compressed Chunk payload") {
            useResource(converted, { it.close() }, block)
        }
        fixedLengthSink.requireComplete()
    }

    private fun <R> readPayload(
        localChunkPosition: LocalChunkPosition,
        regionChunkInfo: RegionChunkInfo,
        bufferedSource: BufferedSource,
        block: (RegionChunkInfo, KotlinxSource) -> R,
    ): R = withOkioIoExceptions("Cannot read Chunk $localChunkPosition payload") {
        val converted = bufferedSource.asKotlinxIoRawSource().buffered()
        val value = block(regionChunkInfo, converted)
        if (!converted.exhausted()) {
            throw WorldIOException("Chunk $localChunkPosition payload was not fully consumed")
        }
        value
    }

    private fun writeHeader(regionWriterState: RegionWriterState) {
        val byteArray = regionWriterState.regionHeader.encode()
        fileHandle.write(0L, byteArray, 0, byteArray.size)
        if (regionWriterState.syncWrites) {
            fileHandle.flushDurably(worldFileAccess.fileSystem, path)
        }
    }

    private fun headerForRead(): RegionHeader = regionWriterState.regionHeader

    private fun externalPath(localChunkPosition: LocalChunkPosition): Path {
        return externalChunkPath(directory, regionPosition, localChunkPosition)
    }

    companion object {
        internal fun open(
            regionFile: Path,
            fileSystem: FileSystem = systemFileSystem,
            syncWrites: Boolean = true,
        ): MutableRegionFile {
            val directory = regionFile.parent
                ?: throw WorldIOException("Region file has no parent directory: $regionFile")
            val regionPosition = parseRegionFileName(regionFile.name)
                ?: throw WorldIOException("Not a region file: $regionFile")
            return openMutable(
                worldFileAccess = WorldFileAccess.mutable(fileSystem),
                directory = directory,
                regionPosition = regionPosition,
                syncWrites = syncWrites,
            )
        }

        internal fun openMutable(
            worldFileAccess: WorldFileAccess,
            directory: Path,
            regionPosition: RegionPosition,
            syncWrites: Boolean = true,
        ): MutableRegionFile {
            worldFileAccess.requireWritable()
            worldFileAccess.fileSystem.createDirectories(directory)
            return openHandle(worldFileAccess, directory, regionPosition, syncWrites)
        }

        internal fun openExistingMutable(
            worldFileAccess: WorldFileAccess,
            directory: Path,
            regionPosition: RegionPosition,
            syncWrites: Boolean = true,
        ): MutableRegionFile? {
            worldFileAccess.requireWritable()
            val path = regionFilePath(directory, regionPosition)
            val fileMetadata = worldFileAccess.fileSystem.metadataOrNull(path) ?: return null
            if (!fileMetadata.isRegularFile) {
                throw WorldIOException("Path is not a regular file: $path")
            }
            return openHandle(worldFileAccess, directory, regionPosition, syncWrites)
        }

        private fun openHandle(
            worldFileAccess: WorldFileAccess,
            directory: Path,
            regionPosition: RegionPosition,
            syncWrites: Boolean,
        ): MutableRegionFile {
            val path = regionFilePath(directory, regionPosition)
            val fileHandle = worldFileAccess.openRegionHandle(path)
            var failure: Throwable? = null
            try {
                val regionHeader = readUsableHeader(fileHandle)
                val regionWriterState = RegionWriterState(
                    regionHeader = regionHeader,
                    regionSectorAllocator = allocatorFor(regionHeader),
                    syncWrites = syncWrites,
                )
                return MutableRegionFile(
                    worldFileAccess = worldFileAccess,
                    regionPosition = regionPosition,
                    directory = directory,
                    path = path,
                    fileHandle = fileHandle,
                    regionWriterState = regionWriterState,
                )
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                if (failure != null) {
                    closeAllPreserving(failure, fileHandle::close)
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
    var regionHeader: RegionHeader,
    val regionSectorAllocator: RegionSectorAllocator,
    val syncWrites: Boolean,
)

private data class StagedRegionChunk(
    val localChunkPosition: LocalChunkPosition,
    val oldLocation: RegionLocation?,
    val newLocation: RegionLocation,
    val externalTemporary: Path?,
)

private data class ClearedRegionChunk(
    val localChunkPosition: LocalChunkPosition,
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

internal fun readUsableHeader(fileHandle: FileHandle): RegionHeader {
    val headerBytes = fileHandle.readAtMost(0L, REGION_HEADER_BYTES)
    val regionHeader = RegionHeader.decode(headerBytes)
    val fileSize = fileHandle.size()
    for (index in 0 until REGION_CHUNK_COUNT) {
        val localChunkPosition = LocalChunkPosition.fromIndex(index)
        val regionLocation = regionHeader.location(localChunkPosition) ?: continue
        if (!regionLocation.isUsableAtOpen(fileSize)) {
            regionHeader.clearLocation(localChunkPosition)
        }
    }
    return regionHeader
}

private fun allocatorFor(regionHeader: RegionHeader): RegionSectorAllocator {
    val regionSectorAllocator = RegionSectorAllocator()
    for (index in 0 until REGION_CHUNK_COUNT) {
        val localChunkPosition = LocalChunkPosition.fromIndex(index)
        regionHeader.location(localChunkPosition)?.let(regionSectorAllocator::mark)
    }
    return regionSectorAllocator
}

internal fun parseRegionFileName(name: String): RegionPosition? {
    val parts = name.split('.')
    if (parts.size != 4 || parts[0] != "r" || parts[3] != "mca") {
        return null
    }
    val x = parts[1].toIntOrNull() ?: return null
    val z = parts[2].toIntOrNull() ?: return null
    val regionPosition = RegionPosition(x, z)
    if (name != "r.${regionPosition.x}.${regionPosition.z}.mca") {
        return null
    }
    return regionPosition
}

internal fun snapshotRegionPositions(fileSystem: FileSystem, directory: Path): List<RegionPosition> {
    val fileMetadata = fileSystem.metadataOrNull(directory) ?: return emptyList()
    if (!fileMetadata.isDirectory) {
        throw WorldIOException("Region path is not a directory: $directory")
    }
    return fileSystem.list(directory)
        .mapNotNull { path -> parseRegionFileName(path.name) }
        .distinct()
        .sortedWith(compareBy(RegionPosition::x, RegionPosition::z))
}

internal fun regionFilePath(directory: Path, regionPosition: RegionPosition): Path =
    directory / "r.${regionPosition.x}.${regionPosition.z}.mca"

internal fun externalChunkPath(
    directory: Path,
    regionPosition: RegionPosition,
    localChunkPosition: LocalChunkPosition,
): Path {
    val chunkPosition = regionPosition.chunk(localChunkPosition)
    return directory / "c.${chunkPosition.x}.${chunkPosition.z}.mcc"
}

internal fun readRegionChunkInfo(
    fileSystem: FileSystem,
    directory: Path,
    regionPosition: RegionPosition,
    fileHandle: FileHandle,
    regionHeader: RegionHeader,
    localChunkPosition: LocalChunkPosition,
): RegionChunkInfo? {
    val regionLocation = regionHeader.location(localChunkPosition) ?: return null
    val prefix = fileHandle.readAtMost(regionLocation.byteOffset, REGION_CHUNK_RECORD_HEADER_BYTES)
    if (prefix.size < REGION_CHUNK_RECORD_HEADER_BYTES) {
        throw AnvilFormatException("Chunk $localChunkPosition has a truncated record header")
    }
    val regionChunkRecordHeader = RegionChunkRecordHeader.decode(prefix)
    if (regionChunkRecordHeader.length == 0) {
        throw AnvilFormatException("Chunk $localChunkPosition has an allocated but missing stream")
    }
    val timestamp = regionHeader.timestamp(localChunkPosition)
    if (regionChunkRecordHeader.external) {
        if (regionChunkRecordHeader.compressedLength != 0) {
            throw AnvilFormatException("External Chunk $localChunkPosition also contains inline bytes")
        }
        val externalPath = externalChunkPath(directory, regionPosition, localChunkPosition)
        val fileMetadata = fileSystem.metadataOrNull(externalPath) ?: return null
        if (!fileMetadata.isRegularFile) return null
        val compressedByteCount = fileMetadata.size
            ?: throw WorldIOException("External Chunk file has no size: $externalPath")
        if (compressedByteCount < 0L) {
            throw WorldIOException("External Chunk file has a negative size: $externalPath")
        }
        return RegionChunkInfo(
            regionPosition = regionPosition,
            localChunkPosition = localChunkPosition,
            compression = regionChunkRecordHeader.compression,
            compressedByteCount = compressedByteCount,
            anvilChunkPlacement = AnvilChunkPlacement.EXTERNAL,
            timestampEpochSeconds = timestamp,
        )
    }
    val maximumPayload = regionLocation.allocatedBytes - REGION_CHUNK_RECORD_HEADER_BYTES
    if (regionChunkRecordHeader.compressedLength !in 0..maximumPayload) {
        throw AnvilFormatException(
            "Chunk $localChunkPosition has invalid length ${regionChunkRecordHeader.length} in ${regionLocation.sectorCount} allocated sectors",
        )
    }
    return RegionChunkInfo(
        regionPosition = regionPosition,
        localChunkPosition = localChunkPosition,
        compression = regionChunkRecordHeader.compression,
        compressedByteCount = regionChunkRecordHeader.compressedLength.toLong(),
        anvilChunkPlacement = AnvilChunkPlacement.INLINE,
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
