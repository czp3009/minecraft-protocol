package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.time.Clock

class WorldRegionStoreEdgeTest {
    @Test
    fun configurationRejectsInvalidLimits() {
        assertFailsWith<IllegalArgumentException> {
            WorldRegionStoreConfiguration(maximumCompressedChunkBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            WorldRegionStoreConfiguration(maximumOpenRegions = 0)
        }
        RegionCompression.entries
            .forEach {
                WorldRegionStoreConfiguration(writeCompression = it)
            }
    }

    @Test
    fun missingAndShortRegionsOpenAsVanillaEmptyHeaders() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val position = ChunkPosition(0, 0)
        val store = edgeStore(fileSystem, directory)

        assertNull(store.readChunk(position))
        assertTrue(fileSystem.exists(path))
        assertEquals(0L, fileSystem.metadata(path).size)
        store.close()

        fileSystem.writeRaw(path, byteArrayOf(1))
        val short = edgeStore(fileSystem, directory)
        assertNull(short.readChunk(position))
        short.close()
        assertEquals(
            REGION_SECTOR_BYTES.toLong(),
            fileSystem.metadata(path).size,
        )
        assertContentEquals(byteArrayOf(1), fileSystem.readRaw(path).copyOf(1))
    }

    @Test
    fun clearingMissingChunkLeavesOrphanSidecarAndTimestampUntouched() = runTest {
        val fileSystem = FakeFileSystem().apply {
            allowReadsWhileWriting = true
        }
        val directory = "/world/region".toPath()
        val regionPath = directory / "r.0.0.mca"
        val sidecarPath = directory / "c.0.0.mcc"
        val sidecarBytes = byteArrayOf(7, 8, 9)
        val position = LocalChunkPosition(0, 0)
        val originalHeader = RegionHeader().apply {
            set(position, location = null, timestamp = 42)
        }.encode()
        fileSystem.writeRaw(regionPath, originalHeader)
        fileSystem.writeRaw(sidecarPath, sidecarBytes)
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(
                syncWrites = false,
            ),
        )

        store.clearChunk(ChunkPosition(0, 0))

        assertTrue(fileSystem.exists(regionPath))
        assertContentEquals(originalHeader, fileSystem.readRaw(regionPath))
        assertContentEquals(sidecarBytes, fileSystem.readRaw(sidecarPath))
        store.close()
        assertContentEquals(originalHeader, fileSystem.readRaw(regionPath))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun invalidLocationsAreClearedOnlyInMemory() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(1, 0)
        val originalHeader = RegionHeader().apply {
            set(first, RegionLocation(1, 1), 1)
            set(second, RegionLocation(3, 1), 2)
        }
        fileSystem.writeRaw(path, originalHeader.encode())
        val store = edgeStore(fileSystem, directory)

        assertNull(store.readChunk(ChunkPosition(0, 0)))
        assertNull(store.readChunk(ChunkPosition(1, 0)))
        store.close()

        val storedHeader = RegionHeader.decode(
            fileSystem.readRaw(path).copyOfRange(0, REGION_HEADER_BYTES),
        )
        assertEquals(RegionLocation(1, 1), storedHeader.location(first))
        assertEquals(RegionLocation(3, 1), storedHeader.location(second))
    }

    @Test
    fun existenceProbeCoversEveryRecordHeaderBranch() = runTest {
        assertFalse(existsForRecord(ByteArray(4)))
        assertFalse(existsForRecord(record(length = 1, version = 5)))
        assertTrue(
            existsForRecord(
                record(
                    length = 1,
                    version = RegionCompression.CUSTOM.id,
                ),
            ),
        )
        assertFalse(
            existsForRecord(
                record(length = 0, version = RegionCompression.NONE.id),
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = REGION_SECTOR_BYTES,
                    version = RegionCompression.NONE.id,
                ),
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = REGION_SECTOR_BYTES + 1,
                    version = RegionCompression.NONE.id,
                ),
            ),
        )
        assertFalse(
            existsForRecord(
                record(
                    length = REGION_SECTOR_BYTES + 2,
                    version = RegionCompression.NONE.id,
                ),
            ),
        )
        assertTrue(
            existsForRecord(
                record(length = 1, version = RegionCompression.NONE.id),
            ),
        )
        assertFalse(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionCompression.NONE.id or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                external = ExternalFileKind.MISSING,
            ),
        )
        assertFalse(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionCompression.NONE.id or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                external = ExternalFileKind.DIRECTORY,
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionCompression.NONE.id or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                external = ExternalFileKind.REGULAR,
            ),
        )
    }

    @Test
    fun internalReadsRejectMalformedLengthCompressionAndTruncation() = runTest {
        assertReadFails(record(length = 0, version = RegionCompression.NONE.id))
        assertReadFails(record(length = REGION_SECTOR_BYTES, version = RegionCompression.NONE.id))
        assertReadFails(record(length = 1, version = 5))
        assertReadFails(ByteArray(4))

        val empty = readRecord(
            record(length = 1, version = RegionCompression.NONE.id),
        )
        assertContentEquals(ByteArray(0), empty.payload.compressedBytes)
        assertEquals(37, empty.timestamp)
    }

    @Test
    fun externalReadIgnoresStubLengthAndAnyInlineSuffix() = runTest {
        val externalPayload = byteArrayOf(9, 8, 7)
        assertFailsWith<RegionFormatException> {
            readRecord(
                bytes = record(
                    length = 0,
                    version = RegionCompression.LZ4.id or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                externalPayload = externalPayload,
            )
        }
        val chunk = readRecord(
            bytes = record(
                length = Int.MIN_VALUE,
                version = RegionCompression.LZ4.id or
                        REGION_EXTERNAL_STREAM_FLAG,
                suffix = byteArrayOf(1, 2, 3),
            ),
            externalPayload = externalPayload,
        )

        assertEquals(RegionCompression.LZ4, chunk.compression)
        assertTrue(chunk.payload.isExternal)
        assertContentEquals(externalPayload, chunk.payload.compressedBytes)
    }

    @Test
    fun rawChunkWritesIgnoreCallerTimestampAndExternalMarker() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val position = ChunkPosition(-33, 65)
        val beforeWrite = Clock.System.now().epochSeconds.toInt()
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(
                syncWrites = false,
            ),
        )

        store.writeChunk(
            position,
            RegionChunk(
                compression = RegionCompression.NONE,
                payload = RegionChunkPayload.External(byteArrayOf(4)),
                timestamp = 999,
            ),
        )
        val afterWrite = Clock.System.now().epochSeconds.toInt()

        val stored = checkNotNull(store.readChunk(position))
        assertFalse(stored.payload.isExternal)
        assertTrue(
            stored.timestamp in
                    minOf(beforeWrite, afterWrite)..maxOf(beforeWrite, afterWrite),
        )
        assertNotEquals(999, stored.timestamp)
        assertContentEquals(byteArrayOf(4), stored.payload.compressedBytes)
        assertFalse(fileSystem.exists(directory / "c.-33.65.mcc"))
        store.close()
    }

    @Test
    fun configuredNbtWriteModesRoundTrip() = runTest {
        val document = edgeRegionDocument()
        listOf(
            RegionCompression.GZIP,
            RegionCompression.ZLIB,
            RegionCompression.NONE,
            RegionCompression.LZ4,
            RegionCompression.CUSTOM,
        ).forEachIndexed { index, compression ->
            val fileSystem = FakeFileSystem()
            val compressionCodecs = if (
                compression == RegionCompression.CUSTOM
            ) {
                RegionCompressionCodecs(
                    mapOf(
                        RegionCompression.CUSTOM to
                                identityCustomCompressionCodec,
                    ),
                )
            } else {
                RegionCompressionCodecs
            }
            val store = WorldRegionStore(
                directory = "/world-$index/region".toPath(),
                fileSystem = fileSystem,
                chunkNbtFormat = RegionChunkNbtFormat(
                    compressionCodecs = compressionCodecs,
                ),
                configuration = WorldRegionStoreConfiguration(
                    syncWrites = false,
                    writeCompression = compression,
                ),
            )
            val position = ChunkPosition(index, -index)

            store.writeChunkNbt(position, document)

            assertEquals(document, store.readChunkNbt(position))
            assertEquals(compression, store.readChunk(position)?.compression)
            store.close()
            fileSystem.checkNoOpenFiles()
        }
    }

    @Test
    fun regionSnapshotsPreserveEveryUnrelatedEntryAndClearIsIdempotent() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val store = edgeStore(fileSystem, directory)
        val first = ChunkPosition(0, 0)
        val second = ChunkPosition(31, 31)
        store.writeChunk(first, edgeChunk(byteArrayOf(1)))
        store.writeChunk(second, edgeChunk(byteArrayOf(2)))

        assertEquals(2, store.readRegion(first.region).chunks.size)
        store.clearChunk(first)
        store.clearChunk(first)

        assertNull(store.readChunk(first))
        assertContentEquals(
            byteArrayOf(2),
            store.readChunk(second)?.payload?.compressedBytes,
        )
        store.close()
    }
}

private suspend fun existsForRecord(
    bytes: ByteArray,
    external: ExternalFileKind? = null,
): Boolean {
    val fileSystem = FakeFileSystem()
    val directory = "/world/region".toPath()
    val path = directory / "r.0.0.mca"
    fileSystem.writeRaw(path, singleAllocatedRecord(bytes))
    when (external) {
        ExternalFileKind.MISSING, null -> Unit
        ExternalFileKind.DIRECTORY ->
            fileSystem.createDirectories(directory / "c.0.0.mcc")

        ExternalFileKind.REGULAR ->
            fileSystem.writeRaw(directory / "c.0.0.mcc", byteArrayOf(1))
    }
    val store = edgeStore(fileSystem, directory)
    return try {
        store.doesChunkExist(ChunkPosition(0, 0))
    } finally {
        store.close()
    }
}

private suspend fun assertReadFails(bytes: ByteArray) {
    assertFailsWith<RegionFormatException> { readRecord(bytes) }
}

private suspend fun readRecord(
    bytes: ByteArray,
    externalPayload: ByteArray? = null,
): RegionChunk {
    val fileSystem = FakeFileSystem()
    val directory = "/world/region".toPath()
    fileSystem.writeRaw(
        directory / "r.0.0.mca",
        singleAllocatedRecord(bytes),
    )
    externalPayload?.let {
        fileSystem.writeRaw(directory / "c.0.0.mcc", it)
    }
    val store = edgeStore(fileSystem, directory)
    return try {
        checkNotNull(store.readChunk(ChunkPosition(0, 0)))
    } finally {
        store.close()
    }
}

private fun singleAllocatedRecord(record: ByteArray): ByteArray {
    val position = LocalChunkPosition(0, 0)
    val header = RegionHeader().apply {
        set(position, RegionLocation(2, 1), timestamp = 37)
    }
    val bytes = ByteArray(REGION_HEADER_BYTES + record.size)
    header.encode().copyInto(bytes)
    record.copyInto(bytes, destinationOffset = REGION_HEADER_BYTES)
    return bytes
}

private fun record(
    length: Int,
    version: Int,
    suffix: ByteArray = ByteArray(0),
): ByteArray = ByteArray(REGION_CHUNK_RECORD_HEADER_BYTES + suffix.size).also {
    writeEdgeInt(it, 0, length)
    it[Int.SIZE_BYTES] = version.toByte()
    suffix.copyInto(it, destinationOffset = REGION_CHUNK_RECORD_HEADER_BYTES)
}

private fun writeEdgeInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private fun edgeStore(
    fileSystem: FileSystem,
    directory: Path,
): WorldRegionStore = WorldRegionStore(
    directory = directory,
    fileSystem = fileSystem,
    configuration = WorldRegionStoreConfiguration(syncWrites = false),
)

private fun edgeChunk(bytes: ByteArray): RegionChunk = RegionChunk(
    compression = RegionCompression.NONE,
    payload = RegionChunkPayload.Inline(bytes),
)

private fun edgeRegionDocument(): NbtDocument = NbtDocument(
    NbtCompound(mapOf("value" to NbtInt(42))),
)

// A CUSTOM codec owns only its transformation, so this identity test codec keeps the public registry's caller-owned
// stream contract while proving world-io does not impose a vanilla-only compression whitelist.
private val identityCustomCompressionCodec =
    object : RegionCompressionCodec {
        override fun compressingSink(sink: Sink): Sink =
            object : Sink by sink {
                override fun close() = sink.flush()
            }

        override fun decompressingSource(
            source: Source,
            maximumOutputBytes: Int,
        ): Source {
            require(maximumOutputBytes >= 0)
            return object : Source by source {
                override fun close() = Unit
            }
        }
    }

private fun FileSystem.writeRaw(path: Path, bytes: ByteArray) {
    path.parent?.let(::createDirectories)
    val sink = sink(path)
    val buffer = Buffer().apply { write(bytes) }
    try {
        sink.write(buffer, bytes.size.toLong())
    } finally {
        sink.close()
    }
}

private fun FileSystem.readRaw(path: Path): ByteArray =
    readFileWithinLimit(path, Int.MAX_VALUE)

private enum class ExternalFileKind {
    MISSING,
    DIRECTORY,
    REGULAR,
}
