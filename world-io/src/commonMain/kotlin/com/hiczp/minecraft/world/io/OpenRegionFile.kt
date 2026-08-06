package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.Path

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
    private val mutex = Mutex()
    private var closed = false

    suspend fun read(position: LocalChunkPosition): RegionChunk? =
        mutex.withLock {
            checkOpen()
            readUnlocked(position)
        }

    suspend fun readAll(): RegionFile = mutex.withLock {
        checkOpen()
        val chunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
        for (index in 0 until REGION_CHUNK_COUNT) {
            val position = LocalChunkPosition.fromIndex(index)
            readUnlocked(position)?.let { chunks[position] = it }
        }
        RegionFile(chunks)
    }

    suspend fun exists(position: LocalChunkPosition): Boolean =
        mutex.withLock {
            checkOpen()
            val location = header.location(position) ?: return@withLock false
            val prefix = handle.readAtMost(
                location.byteOffset,
                REGION_CHUNK_RECORD_HEADER_BYTES,
            )
            if (prefix.size < REGION_CHUNK_RECORD_HEADER_BYTES) {
                return@withLock false
            }
            val record = try {
                RegionChunkRecordHeader.decode(prefix)
            } catch (_: RegionFormatException) {
                return@withLock false
            }
            if (record.external) {
                return@withLock fileSystem.metadataOrNull(
                    externalPath(regionPosition.chunk(position)),
                )?.isRegularFile == true
            }
            record.length != 0 &&
                    record.compressedLength >= 0 &&
                    record.compressedLength <=
                    location.allocatedBytes
        }

    suspend fun write(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        chunk: RegionChunk,
        currentEpochSeconds: () -> Int,
    ) = mutex.withLock {
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
            writeExternal(
                position,
                chunkPosition,
                record,
                currentEpochSeconds,
            )
        } else {
            writeInternal(
                position,
                chunkPosition,
                record,
                currentEpochSeconds,
            )
        }
    }

    suspend fun clear(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        currentEpochSeconds: () -> Int,
    ) = mutex.withLock {
        checkOpen()
        val oldLocation = header.location(position) ?: return@withLock
        header.set(
            position,
            location = null,
            timestamp = currentEpochSeconds(),
        )
        writeHeader()
        fileSystem.deleteIfExists(externalPath(chunkPosition))
        allocator.free(oldLocation)
    }

    suspend fun flush() = mutex.withLock {
        checkOpen()
        handle.flushDurably(fileSystem, path)
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
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

    private fun readUnlocked(position: LocalChunkPosition): RegionChunk? {
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
        currentEpochSeconds: () -> Int,
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
        header.set(position, newLocation, currentEpochSeconds())
        writeHeader()
        fileSystem.deleteIfExists(externalPath(chunkPosition))
        allocator.free(oldLocation)
    }

    private fun writeExternal(
        position: LocalChunkPosition,
        chunkPosition: ChunkPosition,
        record: EncodedRegionChunkRecord,
        currentEpochSeconds: () -> Int,
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
            var writeFailure: Throwable? = null
            try {
                temporary.sink.write(buffer, payload.size.toLong())
            } catch (caught: Throwable) {
                writeFailure = caught
                throw caught
            } finally {
                closeAllPreserving(writeFailure, temporary.sink::close)
            }

            handle.write(
                newLocation.byteOffset,
                record.bytes,
                0,
                record.bytes.size,
            )
            if (syncWrites) handle.flushDurably(fileSystem, path)
            header.set(position, newLocation, currentEpochSeconds())
            writeHeader()
            fileSystem.atomicMove(temporary.path, externalPath(chunkPosition))
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

private fun FileHandle.readAtMost(offset: Long, byteCount: Int): ByteArray {
    require(offset >= 0)
    require(byteCount >= 0)
    if (byteCount == 0) return ByteArray(0)
    val result = ByteArray(byteCount)
    var total = 0
    while (total < byteCount) {
        val read = read(
            fileOffset = offset + total,
            array = result,
            arrayOffset = total,
            byteCount = byteCount - total,
        )
        if (read < 0) break
        if (read == 0) {
            throw WorldIOException("File handle made no progress while reading")
        }
        total += read
    }
    return if (total == result.size) result else result.copyOf(total)
}
