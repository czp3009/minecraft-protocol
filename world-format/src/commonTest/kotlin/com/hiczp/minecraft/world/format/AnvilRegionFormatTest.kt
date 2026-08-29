package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.*
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.*

class AnvilRegionFormatTest {
    @Test
    fun decodesOfficialZeroByteEmptyRegion() {
        assertEquals(
            expected = AnvilRegion(),
            actual = AnvilRegionFormat.decodeFromByteArray(byteArrayOf()),
        )
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(ByteArray(1))
        }
    }

    @Test
    fun mapsNegativeChunkCoordinatesWithFloorDivision() {
        val negativeRegion = RegionPosition(-1, -1)
        val negativeChunk = ChunkPosition(-1, -1)
        assertEquals(
            negativeRegion,
            negativeChunk.regionPosition,
        )
        assertEquals(
            LocalChunkPosition(31, 31),
            negativeChunk.localChunkPosition,
        )
        assertEquals(
            negativeChunk,
            negativeRegion.chunk(LocalChunkPosition(31, 31)),
        )
        assertTrue(negativeChunk in negativeRegion)
        assertEquals(LocalChunkPosition(31, 31), negativeRegion.local(negativeChunk))
        assertFalse(ChunkPosition(0, 0) in negativeRegion)
        assertFailsWith<IllegalArgumentException> {
            negativeRegion.local(ChunkPosition(0, 0))
        }
        assertEquals(
            RegionPosition(-2, 1),
            ChunkPosition(-33, 63).regionPosition,
        )
    }

    @Test
    fun everyLocalIndexAndRandomAbsoluteCoordinateRoundTrips() {
        for (index in 0 until REGION_CHUNK_COUNT) {
            assertEquals(index, LocalChunkPosition.fromIndex(index).index)
        }

        val random = Random(0x52454749)
        val boundaries = listOf(
            Int.MIN_VALUE,
            Int.MIN_VALUE + 1,
            -REGION_SIDE - 1,
            -REGION_SIDE,
            -1,
            0,
            1,
            REGION_SIDE - 1,
            REGION_SIDE,
            REGION_SIDE + 1,
            Int.MAX_VALUE - 1,
            Int.MAX_VALUE,
        )
        val coordinates = boundaries + List(10_000) { random.nextInt() }
        coordinates.forEachIndexed { index, x ->
            val z = coordinates[coordinates.lastIndex - index]
            val chunkPosition = ChunkPosition(x, z)
            assertEquals(
                chunkPosition,
                chunkPosition.regionPosition.chunk(chunkPosition.localChunkPosition),
                "Coordinate sample $index failed",
            )
        }
    }

    @Test
    fun enumeratesEveryRegionChunkPositionInHeaderOrder() {
        val regionPosition = RegionPosition(-2, 1)
        val positions = regionPosition.chunkPositions().toList()

        assertEquals(REGION_CHUNK_COUNT, positions.size)
        positions.forEachIndexed { index, chunkPosition ->
            val localChunkPosition = LocalChunkPosition.fromIndex(index)
            assertEquals(regionPosition.chunk(localChunkPosition), chunkPosition)
            assertEquals(regionPosition, chunkPosition.regionPosition)
            assertEquals(localChunkPosition, chunkPosition.localChunkPosition)
        }
        assertEquals(ChunkPosition(-64, 32), positions.first())
        assertEquals(ChunkPosition(-33, 63), positions.last())
    }

    @Test
    fun detachedRegionChecksChunkRecordPresenceWithoutInspectingContent() {
        val storedPosition = LocalChunkPosition(5, 7)
        val absentPosition = LocalChunkPosition(6, 7)
        val anvilRegion = AnvilRegion(
            mapOf(
                storedPosition to testRecord(
                    compression = Compression.ZLIB,
                    testRecordContent = externalContent(),
                ),
            ),
        )

        assertTrue(anvilRegion.hasChunk(storedPosition))
        assertFalse(anvilRegion.hasChunk(absentPosition))
        assertNull(anvilRegion[storedPosition]?.content)
    }

    @Test
    fun roundTripsInlineChunksAndHeaderMetadata() {
        val firstPosition = LocalChunkPosition(0, 0)
        val lastPosition = LocalChunkPosition(31, 31)
        val anvilRegion = AnvilRegion(
            linkedMapOf(
                firstPosition to testRecord(
                    compression = Compression.ZLIB,
                    testRecordContent = inlineContent(byteArrayOf(1, 2, 3)),
                    timestampEpochSeconds = 123,
                ),
                lastPosition to testRecord(
                    compression = Compression.NONE,
                    testRecordContent = inlineContent(ByteArray(5_000) { it.toByte() }),
                    timestampEpochSeconds = -1,
                ),
            ),
        )

        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(anvilRegion)
        val decoded = AnvilRegionFormat.decodeFromByteArray(encodedAnvilRegion.bytes)

        assertTrue(encodedAnvilRegion.externalChunks.isEmpty())
        assertEquals(anvilRegion, decoded)
        assertEquals(5 * REGION_SECTOR_BYTES, encodedAnvilRegion.bytes.size)
    }

    @Test
    fun streamMethodsConsumeAndEmitOneWholeRegion() {
        val anvilRegion = AnvilRegion(
            mapOf(
                LocalChunkPosition(3, 4) to testRecord(
                    Compression.GZIP,
                    inlineContent(byteArrayOf(7, 8)),
                ),
            ),
        )
        val buffer = Buffer()

        assertTrue(AnvilRegionFormat.encodeRecordsToSink(anvilRegion, buffer).isEmpty())
        assertEquals(anvilRegion, AnvilRegionFormat.decodeFromSource(buffer))
        assertTrue(buffer.exhausted())
    }

    @Test
    fun externalChunksUseStubAndSeparatePayload() {
        val localChunkPosition = LocalChunkPosition(5, 7)
        val byteArray = byteArrayOf(9, 8, 7)
        val content = CompressedChunk(Compression.LZ4, byteArray)
        val anvilRegion = AnvilRegion(
            mapOf(
                localChunkPosition to AnvilChunkRecord(
                    compression = Compression.LZ4,
                    content = content,
                    anvilChunkPlacement = AnvilChunkPlacement.EXTERNAL,
                    timestampEpochSeconds = 42,
                ),
            ),
        )

        val sidecars = AnvilRegionFormat.encodeRecordsToSink(anvilRegion, Buffer())
        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(anvilRegion)
        val decoded = AnvilRegionFormat.decodeFromByteArray(encodedAnvilRegion.bytes)
        val anvilChunkRecord = decoded[localChunkPosition]!!

        assertSame(content, sidecars.getValue(localChunkPosition))
        assertContentEquals(byteArray, encodedAnvilRegion.externalChunks.getValue(localChunkPosition))
        assertEquals(AnvilChunkPlacement.EXTERNAL, anvilChunkRecord.anvilChunkPlacement)
        assertNull(anvilChunkRecord.content)
        assertEquals(Compression.LZ4, anvilChunkRecord.compression)
        assertEquals(42, anvilChunkRecord.timestampEpochSeconds)
        assertEquals(3 * REGION_SECTOR_BYTES, encodedAnvilRegion.bytes.size)
    }

    @Test
    fun oversizedInlineChunkIsAutomaticallyExternalized() {
        val localChunkPosition = LocalChunkPosition(1, 2)
        val byteArray = ByteArray(256 * REGION_SECTOR_BYTES)
        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(
            AnvilRegion(
                mapOf(
                    localChunkPosition to testRecord(
                        Compression.NONE,
                        inlineContent(byteArray),
                    ),
                ),
            ),
        )

        assertContentEquals(byteArray, encodedAnvilRegion.externalChunks.getValue(localChunkPosition))
        assertTrue(
            AnvilRegionFormat
                .decodeFromByteArray(encodedAnvilRegion.bytes)[localChunkPosition]!!
                .anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL,
        )
    }

    @Test
    fun externalizationUsesTheExactVanillaSectorThreshold() {
        val inline = LocalChunkPosition(0, 0)
        val external = LocalChunkPosition(1, 0)
        val largestInlinePayload = 255 * REGION_SECTOR_BYTES - Int.SIZE_BYTES - 1
        val firstExternalPayload = largestInlinePayload + 1
        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(
            AnvilRegion(
                linkedMapOf(
                    inline to testRecord(
                        Compression.NONE,
                        inlineContent(
                            ByteArray(largestInlinePayload),
                        ),
                    ),
                    external to testRecord(
                        Compression.NONE,
                        inlineContent(
                            ByteArray(firstExternalPayload),
                        ),
                    ),
                ),
            ),
        )
        val decoded = AnvilRegionFormat.decodeFromByteArray(encodedAnvilRegion.bytes)

        assertEquals(AnvilChunkPlacement.INLINE, decoded[inline]!!.anvilChunkPlacement)
        assertEquals(AnvilChunkPlacement.EXTERNAL, decoded[external]!!.anvilChunkPlacement)
        assertEquals(setOf(external), encodedAnvilRegion.externalChunks.keys)
        assertEquals(
            firstExternalPayload,
            encodedAnvilRegion.externalChunks.getValue(external).size,
        )
    }

    @Test
    fun deterministicallyRoundTripsRandomRegionStructures() {
        val random = Random(0x4D434152)
        val positions = (0 until REGION_CHUNK_COUNT)
            .map(LocalChunkPosition::fromIndex)

        repeat(100) { sample ->
            val selected = positions
                .shuffled(random)
                .take(random.nextInt(33))
                .sortedBy(LocalChunkPosition::index)
            val sourceChunks = linkedMapOf<LocalChunkPosition, AnvilChunkRecord>()
            selected.forEach { localChunkPosition ->
                val byteArray = ByteArray(random.nextInt(8_193)) {
                    random.nextInt().toByte()
                }
                val testRecordContent = if (random.nextBoolean()) {
                    inlineContent(byteArray)
                } else {
                    externalContent(byteArray)
                }
                sourceChunks[localChunkPosition] = testRecord(
                    compression = Compression.entries[
                        random.nextInt(Compression.entries.size)
                    ],
                    testRecordContent = testRecordContent,
                    timestampEpochSeconds = random.nextInt(),
                )
            }

            val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(
                AnvilRegion(sourceChunks),
            )
            val decoded = AnvilRegionFormat.decodeFromByteArray(encodedAnvilRegion.bytes)

            assertEquals(sourceChunks.keys, decoded.chunks.keys)
            sourceChunks.forEach { (localChunkPosition, source) ->
                val actual = decoded[localChunkPosition]!!
                assertEquals(source.compression, actual.compression)
                assertEquals(source.timestampEpochSeconds, actual.timestampEpochSeconds)
                if (source.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL) {
                    assertEquals(AnvilChunkPlacement.EXTERNAL, actual.anvilChunkPlacement)
                    assertNull(actual.content)
                    assertContentEquals(
                        source.content!!.toByteArray(),
                        encodedAnvilRegion.externalChunks.getValue(localChunkPosition),
                    )
                } else {
                    assertEquals(AnvilChunkPlacement.INLINE, actual.anvilChunkPlacement)
                    assertContentEquals(
                        source.content!!.toByteArray(),
                        actual.content!!.toByteArray(),
                        "Random region sample $sample at $localChunkPosition failed",
                    )
                }
            }
        }
    }

    @Test
    fun rejectsUnresolvedExternalPayloadWhenEncoding() {
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.encodeToByteArray(
                AnvilRegion(
                    mapOf(
                        LocalChunkPosition(0, 0) to testRecord(
                            Compression.ZLIB,
                            externalContent(),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsInvalidLocationsOverlapsAndRecordLengths() {
        val beforeHeader = ByteArray(REGION_HEADER_BYTES)
        writeInt(beforeHeader, 0, (1 shl 8) or 1)
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(beforeHeader)
        }

        val zeroAllocation = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(zeroAllocation, 0, 2 shl 8)
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(zeroAllocation)
        }

        val outsideFile = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(outsideFile, 0, (3 shl 8) or 1)
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(outsideFile)
        }

        val overlap = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(overlap, 0, (2 shl 8) or 1)
        writeInt(overlap, 4, (2 shl 8) or 1)
        writeInt(overlap, 2 * REGION_SECTOR_BYTES, 1)
        overlap[2 * REGION_SECTOR_BYTES + 4] = RegionChunkRecordHeader.compressionId(Compression.NONE).toByte()
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(overlap)
        }

        val excessiveLength = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(excessiveLength, 0, (2 shl 8) or 1)
        writeInt(excessiveLength, 2 * REGION_SECTOR_BYTES, REGION_SECTOR_BYTES)
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(excessiveLength)
        }

        for (length in listOf(0, -1)) {
            assertFailsWith<AnvilFormatException> {
                AnvilRegionFormat.decodeFromByteArray(
                    singleRecord(
                        length = length,
                        version = RegionChunkRecordHeader.compressionId(Compression.NONE),
                    ),
                )
            }
        }

        val truncatedRecord = ByteArray(REGION_HEADER_BYTES + Int.SIZE_BYTES)
        writeInt(truncatedRecord, 0, (2 shl 8) or 1)
        writeInt(truncatedRecord, REGION_HEADER_BYTES, 1)
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(truncatedRecord)
        }
    }

    @Test
    fun rejectsUnknownCompressionAndExternalInlinePayload() {
        val unknown = singleRecord(length = 1, version = 5)
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(unknown)
        }

        val mixed = singleRecord(
            length = 2,
            version = RegionChunkRecordHeader.compressionId(Compression.ZLIB) or 0x80,
        )
        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeFromByteArray(mixed)
        }
    }

    @Test
    fun allVanillaCompressionModesRoundTripNbt() = runTest {
        val nbtDocument = NbtDocument(
            NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(4_000),
                    "message" to NbtString("hello\u0000world"),
                ),
            ),
        )
        val compressedNbtFormat = CompressedNbtFormat()
        for (compression in listOf(
            Compression.GZIP,
            Compression.ZLIB,
            Compression.NONE,
            Compression.LZ4,
        )) {
            val compressedChunk = compressedNbtFormat.encodeDocument(nbtDocument, compression)
            assertEquals(nbtDocument, compressedNbtFormat.decodeDocument(compressedChunk))

            val stream = Buffer()
            compressedNbtFormat.encodeDocumentToSink(nbtDocument, compression, stream)
            assertEquals(
                nbtDocument,
                compressedNbtFormat.decodeDocumentFromSource(stream, compression),
            )
            assertTrue(stream.exhausted())
        }
    }

    @Test
    fun typedChunkNbtUsesTheSameUnnamedRootAsDocuments() = runTest {
        val expected = TypedChunkNbt(4_000, "ready")
        val compressedNbtFormat = CompressedNbtFormat()

        for (compression in listOf(Compression.GZIP, Compression.ZLIB, Compression.NONE, Compression.LZ4)) {
            val typedChunk = compressedNbtFormat.encode(expected, compression)
            val nbtDocument = compressedNbtFormat.decodeDocument(typedChunk)
            assertEquals(expected, compressedNbtFormat.decode<TypedChunkNbt>(typedChunk))

            val documentChunk = compressedNbtFormat.encodeDocument(nbtDocument, compression)
            assertEquals(expected, compressedNbtFormat.decode<TypedChunkNbt>(documentChunk))

            val stream = Buffer()
            compressedNbtFormat.encodeToSink(expected, compression, stream)
            assertEquals(nbtDocument, compressedNbtFormat.decodeDocumentFromSource(stream, compression))
            assertTrue(stream.exhausted())
        }

        assertFailsWith<IllegalArgumentException> {
            CompressedNbtFormat(NbtFormat)
        }
    }

    @Test
    fun loadsChunkNbtFromRegionBytesAndStreamsWithoutFilesystem() = runTest {
        val localChunkPosition = LocalChunkPosition(7, 11)
        val nbtDocument = NbtDocument(
            root = NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(4_000),
                    "xPos" to NbtInt(-25),
                    "zPos" to NbtInt(43),
                    "Status" to NbtString("minecraft:full"),
                    "sections" to NbtList(
                        listOf(
                            NbtCompound(
                                mapOf(
                                    "Y" to NbtInt(-4),
                                    "block_states" to NbtCompound(emptyMap()),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val compressedNbtFormat = CompressedNbtFormat()
        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(
            AnvilRegion(
                mapOf(
                    localChunkPosition to AnvilChunkRecord(
                        compression = Compression.ZLIB,
                        content = compressedNbtFormat.encodeDocument(nbtDocument, Compression.ZLIB),
                        anvilChunkPlacement = AnvilChunkPlacement.INLINE,
                        timestampEpochSeconds = 1_234_567,
                    ),
                ),
            ),
        )

        assertTrue(encodedAnvilRegion.externalChunks.isEmpty())
        val arrayLoaded = AnvilRegionFormat.decodeFromByteArray(encodedAnvilRegion.bytes)
        assertEquals(1_234_567, arrayLoaded[localChunkPosition]?.timestampEpochSeconds)
        assertEquals(nbtDocument, compressedNbtFormat.decodeDocument(arrayLoaded[localChunkPosition]!!.content!!))

        val stream = Buffer().also { it.write(encodedAnvilRegion.bytes) }
        val streamLoaded = AnvilRegionFormat.decodeFromSource(stream)
        assertTrue(stream.exhausted())
        assertEquals(nbtDocument, compressedNbtFormat.decodeDocument(streamLoaded[localChunkPosition]!!.content!!))
    }

    @Test
    fun decodesIndependentStoredFixedAndDynamicDeflateWrappers() = runTest {
        val hello = "hello world".encodeToByteArray()
        val dynamic =
            "The quick brown fox jumps over the lazy dog. "
                .repeat(100)
                .encodeToByteArray()
        val samples = listOf(
            Triple(
                Compression.ZLIB,
                hello,
                "7801010b00f4ff68656c6c6f20776f726c641a0b045d".hexToByteArray(),
            ),
            Triple(
                Compression.ZLIB,
                dynamic,
                "789cedca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f1dc84f97".hexToByteArray(),
            ),
            Triple(
                Compression.GZIP,
                hello,
                "1f8b08000000000000ffcb48cdc9c95728cf2fca49010085114a0d0b000000".hexToByteArray(),
            ),
            Triple(
                Compression.GZIP,
                dynamic,
                "1f8b08000000000000ffedca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f38398b9b94110000".hexToByteArray(),
            ),
        )

        samples.forEach { (compression, expected, encoded) ->
            assertContentEquals(
                expected,
                CompressionRegistry.decompress(
                    compression,
                    encoded,
                ),
            )
        }
    }

    @Test
    fun compressionRejectsCorruption() = runTest {
        val input = ByteArray(10_000) { (it * 31).toByte() }
        for (compression in listOf(
            Compression.GZIP,
            Compression.ZLIB,
            Compression.LZ4,
        )) {
            val encoded = CompressionRegistry.compress(compression, input)
            encoded[encoded.lastIndex / 2] =
                (encoded[encoded.lastIndex / 2].toInt() xor 1).toByte()
            val expectedFailure = if (compression == Compression.LZ4) {
                CompressionFormatException::class
            } else {
                IOException::class
            }
            assertFailsWith(expectedFailure) {
                CompressionRegistry.decompress(
                    compression,
                    encoded,
                )
            }
        }
    }

    @Test
    fun everyCompressionModeRoundTripsBlockBoundaries() = runTest {
        val random = Random(0x434F4D50)
        val sizes = listOf(
            0,
            1,
            15,
            16,
            255,
            256,
            8_191,
            8_192,
            65_535,
            65_536,
            65_537,
            131_089,
        )
        val samples = sizes.map { size ->
            ByteArray(size) { random.nextInt().toByte() }
        } + List(12) {
            ByteArray(random.nextInt(32_769)) {
                random.nextInt().toByte()
            }
        }

        for (compression in listOf(
            Compression.GZIP,
            Compression.ZLIB,
            Compression.NONE,
            Compression.LZ4,
        )) {
            samples.forEachIndexed { index, input ->
                val encoded = CompressionRegistry.compress(
                    compression,
                    input,
                )
                assertContentEquals(
                    input,
                    CompressionRegistry.decompress(
                        compression,
                        encoded,
                    ),
                    "$compression sample $index failed",
                )
            }
        }
    }

    @Test
    fun lz4BlockSelectsRawAndCompressedMethodsAndSplitsLargeInput() {
        val rawInput = ByteArray(256) { it.toByte() }
        val compressedInput = ByteArray(65_536) { 0x5A }
        val multipleBlocksInput = ByteArray(65_537) { 0x5A }

        assertEquals(
            listOf(0x10),
            lz4BlockMethods(
                CompressionRegistry.compress(
                    Compression.LZ4,
                    rawInput,
                ),
            ),
        )
        assertEquals(
            listOf(0x20),
            lz4BlockMethods(
                CompressionRegistry.compress(
                    Compression.LZ4,
                    compressedInput,
                ),
            ),
        )
        val multipleBlocks = CompressionRegistry.compress(
            Compression.LZ4,
            multipleBlocksInput,
        )
        assertEquals(2, lz4BlockMethods(multipleBlocks).size)
        assertContentEquals(
            multipleBlocksInput,
            CompressionRegistry.decompress(
                Compression.LZ4,
                multipleBlocks,
            ),
        )
    }

    @Test
    fun compressionDecoratorsProduceAndConsumeIncrementally() {
        val input = ByteArray(192 * 1_024) { index ->
            (index * 37 + index / 11).toByte()
        }
        for (compression in listOf(
            Compression.NONE,
            Compression.GZIP,
            Compression.ZLIB,
            Compression.LZ4,
        )) {
            val encoded = Buffer()
            val compressor = CompressionRegistry
                .compressingSink(compression, encoded)
                .buffered()
            val first = Buffer().apply {
                write(input, 0, input.size / 2)
            }
            compressor.write(first, first.size)
            compressor.flush()
            assertTrue(encoded.size > 0, "$compression did not stream output")
            val second = Buffer().apply {
                write(input, input.size / 2, input.size)
            }
            compressor.write(second, second.size)
            compressor.close()

            val encodedBytes = encoded.readByteArray()
            val encodedSource = Buffer().apply { write(encodedBytes) }
            val decompressor = CompressionRegistry
                .decompressingSource(
                    compression,
                    encodedSource,
                )
                .buffered()
            val prefix = decompressor.readByteArray(32)

            assertContentEquals(input.copyOf(32), prefix)
            val decoded = Buffer().apply {
                write(prefix)
                decompressor.transferTo(this)
            }
            assertContentEquals(input, decoded.readByteArray())
            decompressor.close()
        }
    }

    @Test
    fun customCompressionIsInjectable() = runTest {
        val reversingCodec = object : CompressionCodec {
            override fun compressingSink(sink: Sink): RawSink =
                object : RawSink {
                    private val bytes = Buffer()

                    override fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        bytes.write(source, byteCount)
                    }

                    override fun flush() = Unit

                    override fun close() {
                        val reversed = Buffer().apply {
                            write(bytes.readByteArray().reversedArray())
                        }
                        sink.write(reversed, reversed.size)
                    }
                }

            override fun decompressingSource(source: Source): RawSource =
                Buffer().apply { write(source.readByteArray().reversedArray()) }
        }
        val compressionRegistry = CompressionRegistry(
            mapOf(Compression.CUSTOM to reversingCodec),
        )
        val input = byteArrayOf(1, 2, 3)

        val encoded = compressionRegistry.compress(Compression.CUSTOM, input)

        assertContentEquals(byteArrayOf(3, 2, 1), encoded)
        assertContentEquals(
            input,
            compressionRegistry.decompress(Compression.CUSTOM, encoded),
        )
    }

    @Test
    fun customCompressionDoesNotInterceptCoroutineCancellation() {
        val compressionCancellation = CancellationException("compression cancelled")
        val decompressionCancellation = CancellationException("decompression cancelled")
        val cancellingCodec = object : CompressionCodec {
            override fun compressingSink(sink: Sink): RawSink =
                object : RawSink {
                    override fun write(source: Buffer, byteCount: Long) {
                        throw compressionCancellation
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                }

            override fun decompressingSource(source: Source): RawSource = object : RawSource {
                override fun readAtMostTo(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = throw decompressionCancellation

                override fun close() = Unit
            }
        }
        val compressionRegistry = CompressionRegistry(
            mapOf(Compression.CUSTOM to cancellingCodec),
        )

        assertSame(
            compressionCancellation,
            assertFailsWith<CancellationException> {
                compressionRegistry.compress(Compression.CUSTOM, byteArrayOf(1))
            },
        )
        assertSame(
            decompressionCancellation,
            assertFailsWith<CancellationException> {
                compressionRegistry.decompress(
                    Compression.CUSTOM,
                    byteArrayOf(1),
                )
            },
        )
    }

    @Test
    fun validatesCoordinatesAndCompressionIds() {
        assertFailsWith<IllegalArgumentException> {
            LocalChunkPosition(-1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalChunkPosition(0, REGION_SIDE)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalChunkPosition.fromIndex(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalChunkPosition.fromIndex(REGION_CHUNK_COUNT)
        }

        Compression.entries.forEach {
            assertEquals(it, RegionChunkRecordHeader.compressionFromId(RegionChunkRecordHeader.compressionId(it)))
        }
        assertNull(RegionChunkRecordHeader.compressionFromId(0))
        assertNull(RegionChunkRecordHeader.compressionFromId(126))
    }

    @Test
    fun streamsCompressedRegionPayloadsWithoutBuildingARegionTree() {
        val firstPosition = LocalChunkPosition(0, 0)
        val secondPosition = LocalChunkPosition(1, 0)
        val firstPayload = ByteArray(32 * 1_024) { it.toByte() }
        val secondPayload = ByteArray(24 * 1_024) { (it * 3).toByte() }
        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(
            AnvilRegion(
                mapOf(
                    firstPosition to testRecord(
                        Compression.NONE,
                        inlineContent(firstPayload),
                    ),
                    secondPosition to testRecord(
                        Compression.ZLIB,
                        inlineContent(secondPayload),
                    ),
                ),
            ),
        )
        val observed = linkedMapOf<LocalChunkPosition, ByteArray>()

        AnvilRegionFormat.decodeRecordsFromSource(Buffer().apply { write(encodedAnvilRegion.bytes) }) { anvilChunkRecordInfo, source ->
            observed[anvilChunkRecordInfo.localChunkPosition] = source.readByteArray()
            assertEquals(
                observed.getValue(anvilChunkRecordInfo.localChunkPosition).size.toLong(),
                anvilChunkRecordInfo.compressedByteCount
            )
        }

        assertContentEquals(firstPayload, observed.getValue(firstPosition))
        assertContentEquals(secondPayload, observed.getValue(secondPosition))
    }

    @Test
    fun streamingRegionPayloadMustBeFullyConsumedDespiteBufferReadAhead() {
        val localChunkPosition = LocalChunkPosition(0, 0)
        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(
            AnvilRegion(
                mapOf(
                    localChunkPosition to testRecord(
                        Compression.NONE,
                        inlineContent(ByteArray(32) { it.toByte() }),
                    ),
                ),
            ),
        )

        assertFailsWith<AnvilFormatException> {
            AnvilRegionFormat.decodeRecordsFromSource(Buffer().apply { write(encodedAnvilRegion.bytes) }) { _, payload ->
                payload.readByte()
            }
        }
    }

    @Test
    fun rejectsMissingCustomCodecAndKeepsByteArrayAdaptersIsolated() = runTest {
        assertFailsWith<CompressionFormatException> {
            CompressionRegistry.compress(
                Compression.CUSTOM,
                byteArrayOf(),
            )
        }
        assertFailsWith<CompressionFormatException> {
            CompressionRegistry.decompress(
                Compression.CUSTOM,
                byteArrayOf(),
            )
        }
        val original = byteArrayOf(1, 2, 3)
        val compressed = CompressionRegistry.compress(
            Compression.NONE,
            original,
        )
        compressed[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), original)
        val decoded = CompressionRegistry.decompress(
            Compression.NONE,
            original,
        )
        decoded[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), original)
    }

    @Test
    fun rejectsMalformedZlibGzipAndLz4HeadersAndTrailers() = runTest {
        val input = "compression-probe".encodeToByteArray()
        val zlib = CompressionRegistry.compress(
            Compression.ZLIB,
            input,
        )
        val gzip = CompressionRegistry.compress(
            Compression.GZIP,
            input,
        )
        val lz4 = CompressionRegistry.compress(
            Compression.LZ4,
            input,
        )

        fun rejectsFormat(
            compression: Compression,
            bytes: ByteArray,
        ) {
            assertFailsWith<CompressionFormatException> {
                CompressionRegistry.decompress(
                    compression,
                    bytes,
                )
            }
        }

        fun rejectsIo(
            compression: Compression,
            bytes: ByteArray,
        ) {
            assertFailsWith<IOException> {
                CompressionRegistry.decompress(
                    compression,
                    bytes,
                )
            }
        }

        rejectsIo(Compression.ZLIB, ByteArray(5))
        rejectsIo(
            Compression.ZLIB,
            zlib.copyOf().also { it[0] = 0x70 },
        )
        rejectsIo(
            Compression.ZLIB,
            zlib.copyOf().also { it[0] = 0x88.toByte() },
        )
        rejectsIo(
            Compression.ZLIB,
            zlib.copyOf().also { it[1] = 0 },
        )
        val dictionaryFlag = (0..255).first {
            it and 0x20 != 0 && ((0x78 shl 8) or it) % 31 == 0
        }
        rejectsIo(
            Compression.ZLIB,
            zlib.copyOf().also { it[1] = dictionaryFlag.toByte() },
        )
        rejectsIo(
            Compression.ZLIB,
            zlib.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            },
        )

        rejectsFormat(Compression.GZIP, ByteArray(17))
        rejectsFormat(
            Compression.GZIP,
            gzip.copyOf().also { it[0] = 0 },
        )
        rejectsFormat(
            Compression.GZIP,
            gzip.copyOf().also { it[2] = 0 },
        )
        rejectsFormat(
            Compression.GZIP,
            gzip.copyOf().also { it[3] = 0xE0.toByte() },
        )
        rejectsIo(
            Compression.GZIP,
            ByteArray(18).also {
                it[0] = 0x1F
                it[1] = 0x8B.toByte()
                it[2] = 8
                it[3] = 4
                it[10] = 100
            },
        )
        rejectsIo(
            Compression.GZIP,
            gzip.copyOf().also { it[3] = 2 },
        )
        rejectsIo(
            Compression.GZIP,
            gzip.copyOf().also {
                it[it.lastIndex - 7] =
                    (it[it.lastIndex - 7].toInt() xor 1).toByte()
            },
        )
        rejectsIo(
            Compression.GZIP,
            gzip.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            },
        )
        rejectsIo(
            Compression.GZIP,
            ByteArray(19) { 1 }.also {
                gzip.copyInto(it, endIndex = 10)
                it[3] = 0x08
            },
        )
        rejectsIo(
            Compression.GZIP,
            ByteArray(19) { 1 }.also {
                gzip.copyInto(it, endIndex = 10)
                it[3] = 0x10
            },
        )

        rejectsFormat(
            Compression.LZ4,
            lz4.copyOf().also { it[0] = 0 },
        )
        rejectsFormat(
            Compression.LZ4,
            lz4.copyOf().also { it[8] = 0 },
        )
        rejectsFormat(
            Compression.LZ4,
            lz4.copyOf().also {
                it[17] = (it[17].toInt() xor 1).toByte()
            },
        )
        rejectsFormat(
            Compression.LZ4,
            lz4 + byteArrayOf(0),
        )
        rejectsFormat(
            Compression.LZ4,
            lz4.copyOf(lz4.size - 1),
        )

        fun malformedLz4(
            compressedLength: Int,
            originalLength: Int,
        ): ByteArray = lz4.copyOf().also {
            writeIntLittleEndian(it, 9, compressedLength)
            writeIntLittleEndian(it, 13, originalLength)
        }

        rejectsFormat(Compression.LZ4, malformedLz4(-1, input.size))
        rejectsFormat(Compression.LZ4, malformedLz4(input.size, -1))
        rejectsFormat(Compression.LZ4, malformedLz4(input.size, 65_537))
        rejectsFormat(Compression.LZ4, malformedLz4(0, input.size))
        rejectsFormat(
            Compression.LZ4,
            malformedLz4(input.size - 1, input.size),
        )
        rejectsFormat(
            Compression.LZ4,
            CompressionRegistry
                .compress(Compression.LZ4, byteArrayOf())
                .also { writeIntLittleEndian(it, 17, 1) },
        )
    }

    @Test
    fun gzipDecoderAcceptsEveryOptionalHeaderField() = runTest {
        val input = "optional-gzip-header\u0000".encodeToByteArray()
        val ordinary = CompressionRegistry.compress(
            Compression.GZIP,
            input,
        )
        val header = byteArrayOf(
            0x1F,
            0x8B.toByte(),
            8,
            0x1E,
            0,
            0,
            0,
            0,
            0,
            0xFF.toByte(),
        )
        val optionalFields =
            byteArrayOf(3, 0, 1, 2, 3) +
                    "region.mca".encodeToByteArray() +
                    byteArrayOf(0) +
                    "generated by test".encodeToByteArray() +
                    byteArrayOf(0)
        val headerWithoutCrc = header + optionalFields
        val withOptionalHeader =
            headerWithoutCrc +
                    byteArrayOf(0x9E.toByte(), 0xAA.toByte()) +
                    ordinary.copyOfRange(10, ordinary.size)

        assertContentEquals(
            input,
            CompressionRegistry.decompress(
                Compression.GZIP,
                withOptionalHeader,
            ),
        )
    }

    @Test
    fun contentBackedRegionValuesUseContentEquality() {
        val firstChunk = CompressedChunk(Compression.ZLIB, byteArrayOf(1, 2))
        val secondChunk = CompressedChunk(Compression.ZLIB, byteArrayOf(1, 2))
        assertEquals(firstChunk, secondChunk)
        assertEquals(firstChunk.hashCode(), secondChunk.hashCode())
        assertNotEquals(firstChunk, CompressedChunk(Compression.GZIP, byteArrayOf(1, 2)))

        val localChunkPosition = LocalChunkPosition(1, 2)
        val first = EncodedAnvilRegion(
            bytes = byteArrayOf(5, 6),
            externalChunks = mapOf(localChunkPosition to byteArrayOf(7, 8)),
        )
        val second = EncodedAnvilRegion(
            bytes = byteArrayOf(5, 6),
            externalChunks = mapOf(localChunkPosition to byteArrayOf(7, 8)),
        )
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(
            first,
            EncodedAnvilRegion(
                bytes = byteArrayOf(5, 9),
                externalChunks = second.externalChunks,
            ),
        )
    }

    @Test
    fun encodedRegionOwnsItsArraysAndStreamsWithoutExposingThem() {
        val localChunkPosition = LocalChunkPosition(1, 2)
        val regionBytes = byteArrayOf(1, 2, 3)
        val externalBytes = byteArrayOf(4, 5, 6)
        val encodedAnvilRegion = EncodedAnvilRegion(regionBytes, mapOf(localChunkPosition to externalBytes))
        regionBytes[0] = 9
        externalBytes[0] = 9
        encodedAnvilRegion.bytes[1] = 9
        encodedAnvilRegion.externalChunks.getValue(localChunkPosition)[1] = 9

        val regionSink = Buffer()
        val externalSink = Buffer()
        encodedAnvilRegion.writeTo(regionSink)

        assertTrue(encodedAnvilRegion.writeExternalChunkTo(localChunkPosition, externalSink))
        assertFalse(encodedAnvilRegion.writeExternalChunkTo(LocalChunkPosition(0, 0), Buffer()))
        assertEquals(3L, encodedAnvilRegion.byteCount)
        assertEquals(setOf(localChunkPosition), encodedAnvilRegion.externalChunkPositions)
        assertContentEquals(byteArrayOf(1, 2, 3), regionSink.readByteArray())
        assertContentEquals(byteArrayOf(4, 5, 6), externalSink.readByteArray())
    }

    @Test
    fun recordCallbackFailuresAreNotReclassifiedAsContainerTruncation() {
        val localChunkPosition = LocalChunkPosition(0, 0)
        val encodedAnvilRegion = AnvilRegionFormat.encodeToByteArray(
            AnvilRegion(
                mapOf(
                    localChunkPosition to AnvilChunkRecord(
                        compression = Compression.NONE,
                        content = CompressedChunk(Compression.NONE, byteArrayOf(1)),
                        anvilChunkPlacement = AnvilChunkPlacement.INLINE,
                    ),
                ),
            ),
        )
        val expected = EOFException("consumer failure")

        val actual = assertFailsWith<EOFException> {
            AnvilRegionFormat.decodeRecordsFromSource(Buffer().apply { encodedAnvilRegion.writeTo(this) }) { _, _ ->
                throw expected
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun chunkNbtFormatRoundTripsCompleteCompressedContent() = runTest {
        val compressedNbtFormat = CompressedNbtFormat()
        val nbtDocument = NbtDocument(
            NbtCompound(mapOf("value" to NbtInt(1))),
        )

        assertEquals(nbtDocument, compressedNbtFormat.decodeDocument(compressedNbtFormat.encodeDocument(nbtDocument)))
    }

    private data class TestRecordContent(
        val bytes: ByteArray?,
        val anvilChunkPlacement: AnvilChunkPlacement,
    )

    private fun inlineContent(bytes: ByteArray): TestRecordContent =
        TestRecordContent(bytes, AnvilChunkPlacement.INLINE)

    private fun externalContent(bytes: ByteArray? = null): TestRecordContent =
        TestRecordContent(bytes, AnvilChunkPlacement.EXTERNAL)

    private fun testRecord(
        compression: Compression,
        testRecordContent: TestRecordContent,
        timestampEpochSeconds: Int = 0,
    ): AnvilChunkRecord = AnvilChunkRecord(
        compression = compression,
        content = testRecordContent.bytes?.let { CompressedChunk(compression, it) },
        anvilChunkPlacement = testRecordContent.anvilChunkPlacement,
        timestampEpochSeconds = timestampEpochSeconds,
    )

    private fun singleRecord(length: Int, version: Int): ByteArray =
        ByteArray(3 * REGION_SECTOR_BYTES).also {
            writeInt(it, 0, (2 shl 8) or 1)
            writeInt(it, 2 * REGION_SECTOR_BYTES, length)
            it[2 * REGION_SECTOR_BYTES + 4] = version.toByte()
        }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeIntLittleEndian(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun lz4BlockMethods(bytes: ByteArray): List<Int> {
        val source = Buffer().apply { write(bytes) }
        val methods = mutableListOf<Int>()
        while (true) {
            assertContentEquals(
                "LZ4Block".encodeToByteArray(),
                source.readByteArray(8),
            )
            val token = source.readByte().toInt() and 0xFF
            val compressedLength = source.readIntLe()
            val originalLength = source.readIntLe()
            source.readIntLe()
            if (originalLength == 0) {
                assertEquals(0, compressedLength)
                assertTrue(source.exhausted())
                return methods
            }
            methods += token and 0xF0
            source.skip(compressedLength.toLong())
        }
    }

    @Serializable
    private data class TypedChunkNbt(
        val dataVersion: Int,
        val status: String,
    )

}
