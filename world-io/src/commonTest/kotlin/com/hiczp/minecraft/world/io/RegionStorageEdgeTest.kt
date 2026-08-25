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
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val position = ChunkPosition(0, 0)
        val store = edgeStore(fileSystem, directory)

        assertNull(store.readCompressedChunk(position))
        assertFalse(fileSystem.exists(path))
        store.close()

        fileSystem.writeRaw(path, byteArrayOf(1))
        val short = edgeStore(fileSystem, directory)
        assertNull(short.readCompressedChunk(position))
        short.close()
        assertEquals(
            REGION_SECTOR_BYTES.toLong(),
            fileSystem.metadata(path).size,
        )
        assertContentEquals(byteArrayOf(1), fileSystem.readFileBytes(path).copyOf(1))
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
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(
                syncWrites = false,
            ),
        )

        store.removeChunk(ChunkPosition(0, 0))

        assertTrue(fileSystem.exists(regionPath))
        assertContentEquals(originalHeader, fileSystem.readFileBytes(regionPath))
        assertContentEquals(sidecarBytes, fileSystem.readFileBytes(sidecarPath))
        store.close()
        assertContentEquals(originalHeader, fileSystem.readFileBytes(regionPath))
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

        assertNull(store.readCompressedChunk(ChunkPosition(0, 0)))
        assertNull(store.readCompressedChunk(ChunkPosition(1, 0)))
        store.close()

        val storedHeader = RegionHeader.decode(
            fileSystem.readFileBytes(path).copyOfRange(0, REGION_HEADER_BYTES),
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
                external = ExternalFileKind.MISSING,
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                external = ExternalFileKind.DIRECTORY,
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 99,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                external = ExternalFileKind.REGULAR,
            ),
        )
        assertTrue(
            existsForRecord(
                record(
                    length = 1,
                    version = RegionChunkRecordHeader.compressionId(Compression.NONE) or
                            REGION_EXTERNAL_STREAM_FLAG,
                ),
                external = ExternalFileKind.REGULAR,
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
        val chunk = readRecord(
            bytes = validRecord,
            externalPayload = externalPayload,
        )

        assertEquals(Compression.LZ4, chunk.compression)
        assertEquals(AnvilChunkPlacement.EXTERNAL, readRecordInfo(validRecord, externalPayload).placement)
        assertContentEquals(externalPayload, chunk.toByteArray())
    }

    @Test
    fun rawChunkWritesGenerateTimestampAndPlacementMetadata() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val position = ChunkPosition(-33, 65)
        val beforeWrite = Clock.System.now().epochSeconds.toInt()
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(
                syncWrites = false,
            ),
        )

        store.writeCompressedChunk(
            position,
            CompressedChunk(
                compression = Compression.NONE,
                compressedBytes = byteArrayOf(4),
            ),
        )
        val afterWrite = Clock.System.now().epochSeconds.toInt()

        val stored = checkNotNull(store.readCompressedChunk(position))
        val info = checkNotNull(store.readChunkInfo(position))
        assertEquals(AnvilChunkPlacement.INLINE, info.placement)
        assertTrue(
            info.timestampEpochSeconds in
                    minOf(beforeWrite, afterWrite)..maxOf(beforeWrite, afterWrite),
        )
        assertContentEquals(byteArrayOf(4), stored.toByteArray())
        assertFalse(fileSystem.exists(directory / "c.-33.65.mcc"))
        store.close()
    }

    @Test
    fun configuredMutableAndLiveNbtModesRoundTrip() = runTest {
        val fileSystem = FakeFileSystem()
        val document = NbtDocument(
            NbtCompound(mapOf("value" to NbtInt(42))),
        )
        val configuredChunkNbtFormat = CompressedNbtFormat(
            compressionRegistry = CompressionRegistry(
                mapOf(
                    Compression.CUSTOM to identityCustomCompressionCodec,
                ),
            ),
        )
        val store = RegionStorage(
            paths = MinecraftWorldPaths("/world".toPath()),
            fileSystem = fileSystem,
            chunkNbtFormat = configuredChunkNbtFormat,
            configuration = RegionStorageConfiguration(
                syncWrites = false,
                writeCompression = Compression.LZ4,
            ),
        )

        try {
            Compression.entries.forEachIndexed { index, compression ->
                val position = ChunkPosition(index, -index)

                if (compression == store.configuration.writeCompression) {
                    store.writeChunkNbtDocument(position, document)
                } else {
                    store.writeChunkNbtDocument(position, document, compression)
                }

                assertEquals(document, store.readChunkNbtDocument(position))
                assertEquals(compression, store.readCompressedChunk(position)?.compression)
            }
        } finally {
            store.close()
        }

        val liveConfiguration = LiveMinecraftWorldAccessConfiguration(
            chunkNbtFormat = configuredChunkNbtFormat,
        )
        val reader = LiveMinecraftWorldAccess.open(
            root = "/world".toPath(),
            fileSystem = fileSystem,
            configuration = liveConfiguration,
        )
        assertSame(liveConfiguration, reader.configuration)
        assertSame(configuredChunkNbtFormat, reader.chunkNbtFormat)
        Compression.entries.forEachIndexed { index, compression ->
            val position = ChunkPosition(index, -index)
            assertEquals(
                expected = document,
                actual = reader.openRegion(position.region).readChunkNbtDocument(position),
                message = "Live reader did not preserve $compression chunk NBT",
            )
        }
        val customPosition = ChunkPosition(Compression.CUSTOM.ordinal, -Compression.CUSTOM.ordinal)
        val liveRegionHandle = reader.openRegion(customPosition.region)
        assertSame(configuredChunkNbtFormat, liveRegionHandle.chunkNbtFormat)
        assertEquals(document, liveRegionHandle.readChunkNbtDocument(customPosition))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun regionSnapshotsPreserveEveryUnrelatedEntryAndClearIsIdempotent() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val store = edgeStore(fileSystem, directory)
        val first = ChunkPosition(0, 0)
        val second = ChunkPosition(31, 31)
        store.writeCompressedChunk(first, edgeChunk(byteArrayOf(1)))
        store.writeCompressedChunk(second, edgeChunk(byteArrayOf(2)))

        assertEquals(2, checkNotNull(store.readAnvilRegion(first.region)).chunks.size)
        store.removeChunk(first)
        store.removeChunk(first)

        assertNull(store.readCompressedChunk(first))
        assertContentEquals(
            byteArrayOf(2),
            store.readCompressedChunk(second).bytesOrNull(),
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
        store.hasChunk(ChunkPosition(0, 0))
    } finally {
        store.close()
    }
}

private suspend fun assertReadFails(bytes: ByteArray) {
    assertFailsWith<AnvilFormatException> { readRecord(bytes) }
}

private suspend fun readRecordInfo(
    bytes: ByteArray,
    externalPayload: ByteArray? = null,
): RegionChunkInfo {
    val fileSystem = FakeFileSystem()
    val directory = "/world/region".toPath()
    fileSystem.writeRaw(directory / "r.0.0.mca", singleAllocatedRecord(bytes))
    externalPayload?.let { fileSystem.writeRaw(directory / "c.0.0.mcc", it) }
    val store = edgeStore(fileSystem, directory)
    return try {
        checkNotNull(store.readChunkInfo(ChunkPosition(0, 0)))
    } finally {
        store.close()
    }
}

private suspend fun readRecord(
    bytes: ByteArray,
    externalPayload: ByteArray? = null,
): CompressedChunk {
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
        checkNotNull(store.readCompressedChunk(ChunkPosition(0, 0)))
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
): RegionStorage = RegionStorage(
    directory = directory,
    fileSystem = fileSystem,
    configuration = RegionStorageConfiguration(syncWrites = false),
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
