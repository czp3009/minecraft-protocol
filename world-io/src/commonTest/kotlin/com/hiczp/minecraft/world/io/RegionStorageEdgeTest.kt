package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.RawSink as KotlinxRawSink
import kotlinx.io.RawSource as KotlinxRawSource
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

class RegionStorageEdgeTest {
    @Test
    fun configurationAcceptsEveryWriteCompression() {
        Compression.entries
            .forEach {
                RegionStorageConfiguration(writeCompression = it)
            }
    }

    @Test
    fun missingReadsDoNotCreateFilesAndShortRegionsUseVanillaEmptyHeaders() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val chunkPosition = ChunkPosition(0, 0)
        val regionStorage = edgeStore(fakeFileSystem, directory)

        assertNull(regionStorage.readCompressedChunk(chunkPosition))
        assertFalse(fakeFileSystem.exists(path))
        regionStorage.close()

        fakeFileSystem.writeRaw(path, byteArrayOf(1))
        val short = edgeStore(fakeFileSystem, directory)
        assertNull(short.readCompressedChunk(chunkPosition))
        short.close()
        assertEquals(
            REGION_SECTOR_BYTES.toLong(),
            fakeFileSystem.metadata(path).size,
        )
        assertContentEquals(byteArrayOf(1), fakeFileSystem.readFileBytes(path).copyOf(1))
    }

    @Test
    fun clearingMissingChunkLeavesOrphanSidecarAndTimestampUntouched() = runTest {
        val fakeFileSystem = FakeFileSystem().apply {
            allowReadsWhileWriting = true
        }
        val directory = "/world/region".toPath()
        val regionPath = directory / "r.0.0.mca"
        val sidecarPath = directory / "c.0.0.mcc"
        val sidecarBytes = byteArrayOf(7, 8, 9)
        val localChunkPosition = LocalChunkPosition(0, 0)
        val originalHeader = RegionHeader().apply {
            set(localChunkPosition, regionLocation = null, timestamp = 42)
        }.encode()
        fakeFileSystem.writeRaw(regionPath, originalHeader)
        fakeFileSystem.writeRaw(sidecarPath, sidecarBytes)
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(
                syncWrites = false,
            ),
        )

        regionStorage.removeChunk(ChunkPosition(0, 0))

        assertTrue(fakeFileSystem.exists(regionPath))
        assertContentEquals(originalHeader, fakeFileSystem.readFileBytes(regionPath))
        assertContentEquals(sidecarBytes, fakeFileSystem.readFileBytes(sidecarPath))
        regionStorage.close()
        assertContentEquals(originalHeader, fakeFileSystem.readFileBytes(regionPath))
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun invalidLocationsAreClearedOnlyInMemory() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(1, 0)
        val originalHeader = RegionHeader().apply {
            set(first, RegionLocation(1, 1), 1)
            set(second, RegionLocation(3, 1), 2)
        }
        fakeFileSystem.writeRaw(path, originalHeader.encode())
        val regionStorage = edgeStore(fakeFileSystem, directory)

        assertNull(regionStorage.readCompressedChunk(ChunkPosition(0, 0)))
        assertNull(regionStorage.readCompressedChunk(ChunkPosition(1, 0)))
        regionStorage.close()

        val storedHeader = RegionHeader.decode(
            fakeFileSystem.readFileBytes(path).copyOfRange(0, REGION_HEADER_BYTES),
        )
        assertEquals(RegionLocation(1, 1), storedHeader.location(first))
        assertEquals(RegionLocation(3, 1), storedHeader.location(second))
    }

    @Test
    fun existenceProbeDoesNotInspectChunkRecordHeadersOrExternalPayloads() = runTest {
        assertTrue(existsForRecord(ByteArray(4)))
        assertTrue(existsForRecord(record(length = 1, version = 5)))
        assertTrue(
            existsForRecord(
                record(
                    length = 1,
                    version = RegionChunkRecordHeader.compressionId(Compression.CUSTOM),
                ),
            ),
        )
        assertTrue(
            existsForRecord(
                record(length = 0, version = RegionChunkRecordHeader.compressionId(Compression.NONE)),
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = REGION_SECTOR_BYTES - Int.SIZE_BYTES,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE),
                ),
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = REGION_SECTOR_BYTES - Int.SIZE_BYTES + 1,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE),
                ),
            ),
        )
        assertTrue(
            existsForRecord(
                record(length = 1, version = RegionChunkRecordHeader.compressionId(Compression.NONE)),
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                externalFileKind = ExternalFileKind.MISSING,
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                externalFileKind = ExternalFileKind.DIRECTORY,
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                externalFileKind = ExternalFileKind.REGULAR,
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 1,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                externalFileKind = ExternalFileKind.REGULAR,
            ),
        )
    }

    @Test
    fun internalReadsRejectMalformedLengthCompressionAndTruncation() = runTest {
        assertReadFails(record(length = 0, version = RegionChunkRecordHeader.compressionId(Compression.NONE)))
        assertReadFails(
            record(
                length = REGION_SECTOR_BYTES,
                version = RegionChunkRecordHeader.compressionId(Compression.NONE)
            )
        )
        assertReadFails(record(length = 1, version = 5))
        assertReadFails(ByteArray(4))

        val empty = readRecord(
            record(length = 1, version = RegionChunkRecordHeader.compressionId(Compression.NONE)),
        )
        assertContentEquals(ByteArray(0), empty.toByteArray())
        assertEquals(
            37,
            readRecordInfo(
                record(length = 1, version = RegionChunkRecordHeader.compressionId(Compression.NONE)),
            ).timestampEpochSeconds,
        )
    }

    @Test
    fun externalReadIgnoresStubLengthAndAnyInlineSuffix() = runTest {
        val externalPayload = byteArrayOf(9, 8, 7)
        assertFailsWith<AnvilFormatException> {
            readRecord(
                bytes = record(
                    length = 0,
                    version = RegionChunkRecordHeader.compressionId(Compression.LZ4) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                externalPayload = externalPayload,
            )
        }
        assertFailsWith<AnvilFormatException> {
            readRecord(
                bytes = record(
                    length = Int.MIN_VALUE,
                    version = RegionChunkRecordHeader.compressionId(Compression.LZ4) or
                            REGION_EXTERNAL_STREAM_FLAG,
                    suffix = byteArrayOf(1, 2, 3),
                ),
                externalPayload = externalPayload,
            )
        }
        val validRecord = record(
            length = 1,
            version = RegionChunkRecordHeader.compressionId(Compression.LZ4) or REGION_EXTERNAL_STREAM_FLAG,
        )
        val compressedChunk = readRecord(
            bytes = validRecord,
            externalPayload = externalPayload,
        )

        assertEquals(Compression.LZ4, compressedChunk.compression)
        assertEquals(AnvilChunkPlacement.EXTERNAL, readRecordInfo(validRecord, externalPayload).anvilChunkPlacement)
        assertContentEquals(externalPayload, compressedChunk.toByteArray())
    }

    @Test
    fun rawChunkWritesGenerateTimestampAndPlacementMetadata() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(-33, 65)
        val beforeWrite = Clock.System.now().epochSeconds.toInt()
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(
                syncWrites = false,
            ),
        )

        regionStorage.writeCompressedChunk(
            chunkPosition,
            CompressedChunk(
                compression = Compression.NONE,
                compressedBytes = byteArrayOf(4),
            ),
        )
        val afterWrite = Clock.System.now().epochSeconds.toInt()

        val stored = checkNotNull(regionStorage.readCompressedChunk(chunkPosition))
        val regionChunkInfo = checkNotNull(regionStorage.readChunkInfo(chunkPosition))
        assertEquals(AnvilChunkPlacement.INLINE, regionChunkInfo.anvilChunkPlacement)
        assertTrue(
            regionChunkInfo.timestampEpochSeconds in
                    minOf(beforeWrite, afterWrite)..maxOf(beforeWrite, afterWrite),
        )
        assertContentEquals(byteArrayOf(4), stored.toByteArray())
        assertFalse(fakeFileSystem.exists(directory / "c.-33.65.mcc"))
        regionStorage.close()
    }

    @Test
    fun configuredMutableAndLiveNbtModesRoundTrip() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val nbtDocument = NbtDocument(
            NbtCompound(mapOf("value" to NbtInt(42))),
        )
        val configuredChunkNbtFormat = CompressedNbtFormat(
            compressionRegistry = CompressionRegistry(
                mapOf(
                    Compression.CUSTOM to identityCustomCompressionCodec,
                ),
            ),
        )
        val regionStorage = RegionStorage(
            minecraftWorldPaths = MinecraftWorldPaths("/world".toPath()),
            fileSystem = fakeFileSystem,
            chunkNbtFormat = configuredChunkNbtFormat,
            regionStorageConfiguration = RegionStorageConfiguration(
                syncWrites = false,
                writeCompression = Compression.LZ4,
            ),
        )

        try {
            Compression.entries.forEachIndexed { index, compression ->
                val chunkPosition = ChunkPosition(index, -index)

                if (compression == regionStorage.regionStorageConfiguration.writeCompression) {
                    regionStorage.writeChunkNbtDocument(chunkPosition, nbtDocument)
                } else {
                    regionStorage.writeChunkNbtDocument(chunkPosition, nbtDocument, compression)
                }

                assertEquals(nbtDocument, regionStorage.readChunkNbtDocument(chunkPosition))
                assertEquals(compression, regionStorage.readCompressedChunk(chunkPosition)?.compression)
            }
        } finally {
            regionStorage.close()
        }

        val liveMinecraftWorldAccessConfiguration = LiveMinecraftWorldAccessConfiguration(
            chunkNbtFormat = configuredChunkNbtFormat,
        )
        val reader = LiveMinecraftWorldAccess.open(
            root = "/world".toPath(),
            fileSystem = fakeFileSystem,
            liveMinecraftWorldAccessConfiguration = liveMinecraftWorldAccessConfiguration,
        )
        assertSame(liveMinecraftWorldAccessConfiguration, reader.liveMinecraftWorldAccessConfiguration)
        Compression.entries.forEachIndexed { index, compression ->
            val chunkPosition = ChunkPosition(index, -index)
            assertEquals(
                expected = nbtDocument,
                actual = reader.openRegion(chunkPosition.regionPosition).readChunkNbtDocument(chunkPosition),
                message = "Live reader did not preserve $compression chunk NBT",
            )
        }
        val customPosition = ChunkPosition(Compression.CUSTOM.ordinal, -Compression.CUSTOM.ordinal)
        val liveRegionHandle = reader.openRegion(customPosition.regionPosition)
        assertSame(configuredChunkNbtFormat, liveRegionHandle.chunkNbtFormat)
        assertEquals(nbtDocument, liveRegionHandle.readChunkNbtDocument(customPosition))
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun regionSnapshotsPreserveEveryUnrelatedEntryAndClearIsIdempotent() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val regionStorage = edgeStore(fakeFileSystem, directory)
        val first = ChunkPosition(0, 0)
        val second = ChunkPosition(31, 31)
        regionStorage.writeCompressedChunk(first, edgeChunk(byteArrayOf(1)))
        regionStorage.writeCompressedChunk(second, edgeChunk(byteArrayOf(2)))

        assertEquals(2, checkNotNull(regionStorage.readAnvilRegion(first.regionPosition)).chunks.size)
        regionStorage.removeChunk(first)
        regionStorage.removeChunk(first)

        assertNull(regionStorage.readCompressedChunk(first))
        assertContentEquals(
            byteArrayOf(2),
            regionStorage.readCompressedChunk(second).bytesOrNull(),
        )
        regionStorage.close()
    }
}

private suspend fun existsForRecord(
    bytes: ByteArray,
    externalFileKind: ExternalFileKind? = null,
): Boolean {
    val fakeFileSystem = FakeFileSystem()
    val directory = "/world/region".toPath()
    val path = directory / "r.0.0.mca"
    fakeFileSystem.writeRaw(path, singleAllocatedRecord(bytes))
    when (externalFileKind) {
        ExternalFileKind.MISSING, null -> Unit
        ExternalFileKind.DIRECTORY ->
            fakeFileSystem.createDirectories(directory / "c.0.0.mcc")

        ExternalFileKind.REGULAR ->
            fakeFileSystem.writeRaw(directory / "c.0.0.mcc", byteArrayOf(1))
    }
    val regionStorage = edgeStore(fakeFileSystem, directory)
    return try {
        regionStorage.hasChunk(ChunkPosition(0, 0))
    } finally {
        regionStorage.close()
    }
}

private suspend fun assertReadFails(bytes: ByteArray) {
    assertFailsWith<AnvilFormatException> { readRecord(bytes) }
}

private suspend fun readRecordInfo(
    bytes: ByteArray,
    externalPayload: ByteArray? = null,
): RegionChunkInfo {
    val fakeFileSystem = FakeFileSystem()
    val directory = "/world/region".toPath()
    fakeFileSystem.writeRaw(directory / "r.0.0.mca", singleAllocatedRecord(bytes))
    externalPayload?.let { fakeFileSystem.writeRaw(directory / "c.0.0.mcc", it) }
    val regionStorage = edgeStore(fakeFileSystem, directory)
    return try {
        checkNotNull(regionStorage.readChunkInfo(ChunkPosition(0, 0)))
    } finally {
        regionStorage.close()
    }
}

private suspend fun readRecord(
    bytes: ByteArray,
    externalPayload: ByteArray? = null,
): CompressedChunk {
    val fakeFileSystem = FakeFileSystem()
    val directory = "/world/region".toPath()
    fakeFileSystem.writeRaw(
        directory / "r.0.0.mca",
        singleAllocatedRecord(bytes),
    )
    externalPayload?.let {
        fakeFileSystem.writeRaw(directory / "c.0.0.mcc", it)
    }
    val regionStorage = edgeStore(fakeFileSystem, directory)
    return try {
        checkNotNull(regionStorage.readCompressedChunk(ChunkPosition(0, 0)))
    } finally {
        regionStorage.close()
    }
}

private fun singleAllocatedRecord(record: ByteArray): ByteArray {
    val localChunkPosition = LocalChunkPosition(0, 0)
    val regionHeader = RegionHeader().apply {
        set(localChunkPosition, RegionLocation(2, 1), timestamp = 37)
    }
    val byteArray = ByteArray(REGION_HEADER_BYTES + record.size)
    regionHeader.encode().copyInto(byteArray)
    record.copyInto(byteArray, destinationOffset = REGION_HEADER_BYTES)
    return byteArray
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
): RegionStorage = RegionStorage(
    directory = directory,
    fileSystem = fileSystem,
    regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
)

private fun edgeChunk(bytes: ByteArray): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = bytes,
)

// A CUSTOM codec owns only its transformation, so this identity test codec keeps the public registry's caller-owned
// stream contract while proving world-io does not impose a vanilla-only compression whitelist.
private val identityCustomCompressionCodec =
    object : CompressionCodec {
        override fun compressingSink(sink: KotlinxSink): KotlinxRawSink =
            object : KotlinxRawSink {
                override fun write(
                    source: KotlinxBuffer,
                    byteCount: Long,
                ) = sink.write(source, byteCount)

                override fun flush() = sink.flush()

                override fun close() = sink.flush()
            }

        override fun decompressingSource(source: KotlinxSource): KotlinxRawSource =
            object : KotlinxRawSource {
                override fun readAtMostTo(
                    sink: KotlinxBuffer,
                    byteCount: Long,
                ): Long = source.readAtMostTo(sink, byteCount)

                override fun close() = Unit
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


private enum class ExternalFileKind {
    MISSING,
    DIRECTORY,
    REGULAR,
}
