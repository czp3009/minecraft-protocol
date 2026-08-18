package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.*
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.*

class RegionFileFormatTest {
    @Test
    fun decodesOfficialZeroByteEmptyRegion() {
        assertEquals(
            expected = RegionFile(),
            actual = RegionFileFormat.decodeFromByteArray(byteArrayOf()),
        )
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(ByteArray(1))
        }
    }

    @Test
    fun mapsNegativeChunkCoordinatesWithFloorDivision() {
        val negativeRegion = RegionPosition(-1, -1)
        val negativeChunk = ChunkPosition(-1, -1)
        assertEquals(
            negativeRegion,
            negativeChunk.region,
        )
        assertEquals(
            LocalChunkPosition(31, 31),
            negativeChunk.local,
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
            ChunkPosition(-33, 63).region,
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
            val position = ChunkPosition(x, z)
            assertEquals(
                position,
                position.region.chunk(position.local),
                "Coordinate sample $index failed",
            )
        }
    }

    @Test
    fun enumeratesEveryRegionChunkPositionInHeaderOrder() {
        val region = RegionPosition(-2, 1)
        val positions = region.chunkPositions().toList()

        assertEquals(REGION_CHUNK_COUNT, positions.size)
        positions.forEachIndexed { index, position ->
            val local = LocalChunkPosition.fromIndex(index)
            assertEquals(region.chunk(local), position)
            assertEquals(region, position.region)
            assertEquals(local, position.local)
        }
        assertEquals(ChunkPosition(-64, 32), positions.first())
        assertEquals(ChunkPosition(-33, 63), positions.last())
    }

    @Test
    fun roundTripsInlineChunksAndHeaderMetadata() {
        val firstPosition = LocalChunkPosition(0, 0)
        val lastPosition = LocalChunkPosition(31, 31)
        val region = RegionFile(
            linkedMapOf(
                firstPosition to RegionChunk(
                    compression = Compression.ZLIB,
                    payload = RegionChunkPayload.Inline(byteArrayOf(1, 2, 3)),
                    timestamp = 123,
                ),
                lastPosition to RegionChunk(
                    compression = Compression.NONE,
                    payload = RegionChunkPayload.Inline(ByteArray(5_000) { it.toByte() }),
                    timestamp = -1,
                ),
            ),
        )

        val encoded = RegionFileFormat.encodeToByteArray(region)
        val decoded = RegionFileFormat.decodeFromByteArray(encoded.bytes)

        assertTrue(encoded.externalChunks.isEmpty())
        assertEquals(region, decoded)
        assertEquals(5 * REGION_SECTOR_BYTES, encoded.bytes.size)
    }

    @Test
    fun streamMethodsConsumeAndEmitOneWholeRegion() {
        val region = RegionFile(
            mapOf(
                LocalChunkPosition(3, 4) to RegionChunk(
                    Compression.GZIP,
                    RegionChunkPayload.Inline(byteArrayOf(7, 8)),
                ),
            ),
        )
        val buffer = Buffer()

        assertTrue(RegionFileFormat.encodeToSink(region, buffer).isEmpty())
        assertEquals(region, RegionFileFormat.decodeFromSource(buffer))
        assertTrue(buffer.exhausted())
    }

    @Test
    fun externalChunksUseStubAndSeparatePayload() {
        val position = LocalChunkPosition(5, 7)
        val bytes = byteArrayOf(9, 8, 7)
        val region = RegionFile(
            mapOf(
                position to RegionChunk(
                    Compression.LZ4,
                    RegionChunkPayload.External(bytes),
                    timestamp = 42,
                ),
            ),
        )

        val encoded = RegionFileFormat.encodeToByteArray(region)
        val decoded = RegionFileFormat.decodeFromByteArray(encoded.bytes)
        val chunk = decoded[position]!!

        assertContentEquals(bytes, encoded.externalChunks.getValue(position))
        assertTrue(chunk.payload.isExternal)
        assertNull(chunk.payload.compressedBytes)
        assertEquals(Compression.LZ4, chunk.compression)
        assertEquals(42, chunk.timestamp)
        assertEquals(3 * REGION_SECTOR_BYTES, encoded.bytes.size)
    }

    @Test
    fun oversizedInlineChunkIsAutomaticallyExternalized() {
        val position = LocalChunkPosition(1, 2)
        val bytes = ByteArray(256 * REGION_SECTOR_BYTES)
        val encoded = RegionFileFormat.encodeToByteArray(
            RegionFile(
                mapOf(
                    position to RegionChunk(
                        Compression.NONE,
                        RegionChunkPayload.Inline(bytes),
                    ),
                ),
            ),
        )

        assertContentEquals(bytes, encoded.externalChunks.getValue(position))
        assertTrue(
            RegionFileFormat
                .decodeFromByteArray(encoded.bytes)[position]!!
                .payload
                .isExternal,
        )
    }

    @Test
    fun externalizationUsesTheExactVanillaSectorThreshold() {
        val inline = LocalChunkPosition(0, 0)
        val external = LocalChunkPosition(1, 0)
        val largestInlinePayload = 255 * REGION_SECTOR_BYTES - Int.SIZE_BYTES - 1
        val firstExternalPayload = largestInlinePayload + 1
        val encoded = RegionFileFormat.encodeToByteArray(
            RegionFile(
                linkedMapOf(
                    inline to RegionChunk(
                        Compression.NONE,
                        RegionChunkPayload.Inline(
                            ByteArray(largestInlinePayload),
                        ),
                    ),
                    external to RegionChunk(
                        Compression.NONE,
                        RegionChunkPayload.Inline(
                            ByteArray(firstExternalPayload),
                        ),
                    ),
                ),
            ),
        )
        val decoded = RegionFileFormat.decodeFromByteArray(encoded.bytes)

        assertFalse(decoded[inline]!!.payload.isExternal)
        assertTrue(decoded[external]!!.payload.isExternal)
        assertEquals(setOf(external), encoded.externalChunks.keys)
        assertEquals(
            firstExternalPayload,
            encoded.externalChunks.getValue(external).size,
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
            val sourceChunks = linkedMapOf<LocalChunkPosition, RegionChunk>()
            selected.forEach { position ->
                val bytes = ByteArray(random.nextInt(8_193)) {
                    random.nextInt().toByte()
                }
                val payload = if (random.nextBoolean()) {
                    RegionChunkPayload.Inline(bytes)
                } else {
                    RegionChunkPayload.External(bytes)
                }
                sourceChunks[position] = RegionChunk(
                    compression = Compression.entries[
                        random.nextInt(Compression.entries.size)
                    ],
                    payload = payload,
                    timestamp = random.nextInt(),
                )
            }

            val encoded = RegionFileFormat.encodeToByteArray(
                RegionFile(sourceChunks),
            )
            val decoded = RegionFileFormat.decodeFromByteArray(encoded.bytes)

            assertEquals(sourceChunks.keys, decoded.chunks.keys)
            sourceChunks.forEach { (position, source) ->
                val actual = decoded[position]!!
                assertEquals(source.compression, actual.compression)
                assertEquals(source.timestamp, actual.timestamp)
                if (source.payload.isExternal) {
                    assertTrue(actual.payload.isExternal)
                    assertNull(actual.payload.compressedBytes)
                    assertContentEquals(
                        source.payload.compressedBytes,
                        encoded.externalChunks.getValue(position),
                    )
                } else {
                    assertFalse(actual.payload.isExternal)
                    assertContentEquals(
                        source.payload.compressedBytes,
                        actual.payload.compressedBytes,
                        "Random region sample $sample at $position failed",
                    )
                }
            }
        }
    }

    @Test
    fun rejectsUnresolvedExternalPayloadWhenEncoding() {
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.encodeToByteArray(
                RegionFile(
                    mapOf(
                        LocalChunkPosition(0, 0) to RegionChunk(
                            Compression.ZLIB,
                            RegionChunkPayload.External(),
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
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(beforeHeader)
        }

        val zeroAllocation = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(zeroAllocation, 0, 2 shl 8)
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(zeroAllocation)
        }

        val outsideFile = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(outsideFile, 0, (3 shl 8) or 1)
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(outsideFile)
        }

        val overlap = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(overlap, 0, (2 shl 8) or 1)
        writeInt(overlap, 4, (2 shl 8) or 1)
        writeInt(overlap, 2 * REGION_SECTOR_BYTES, 1)
        overlap[2 * REGION_SECTOR_BYTES + 4] = RegionChunkRecordHeader.compressionId(Compression.NONE).toByte()
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(overlap)
        }

        val excessiveLength = ByteArray(3 * REGION_SECTOR_BYTES)
        writeInt(excessiveLength, 0, (2 shl 8) or 1)
        writeInt(excessiveLength, 2 * REGION_SECTOR_BYTES, REGION_SECTOR_BYTES)
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(excessiveLength)
        }

        for (length in listOf(0, -1)) {
            assertFailsWith<RegionFormatException> {
                RegionFileFormat.decodeFromByteArray(
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
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(truncatedRecord)
        }
    }

    @Test
    fun rejectsUnknownCompressionAndExternalInlinePayload() {
        val unknown = singleRecord(length = 1, version = 5)
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(unknown)
        }

        val mixed = singleRecord(
            length = 2,
            version = RegionChunkRecordHeader.compressionId(Compression.ZLIB) or 0x80,
        )
        assertFailsWith<RegionFormatException> {
            RegionFileFormat.decodeFromByteArray(mixed)
        }
    }

    @Test
    fun allVanillaCompressionModesRoundTripNbt() = runTest {
        val document = NbtDocument(
            NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(4_000),
                    "message" to NbtString("hello\u0000world"),
                ),
            ),
        )
        val format = RegionChunkNbtFormat()
        for (compression in listOf(
            Compression.GZIP,
            Compression.ZLIB,
            Compression.NONE,
            Compression.LZ4,
        )) {
            val chunk = format.encode(document, compression)
            assertEquals(document, format.decode(chunk))

            val stream = Buffer()
            format.encodeToSink(document, compression, stream)
            assertEquals(
                document,
                format.decodeFromSource(stream, compression),
            )
            assertTrue(stream.exhausted())
        }
    }

    @Test
    fun typedChunkNbtUsesTheSameUnnamedRootAsDocuments() = runTest {
        val expected = TypedChunkNbt(4_000, "ready")
        val format = RegionChunkNbtFormat()

        for (compression in listOf(Compression.GZIP, Compression.ZLIB, Compression.NONE, Compression.LZ4)) {
            val typedChunk = format.encode(TypedChunkNbt.serializer(), expected, compression)
            val document = format.decode(typedChunk)
            assertEquals(expected, format.decode(TypedChunkNbt.serializer(), typedChunk))

            val documentChunk = format.encode(document, compression)
            assertEquals(expected, format.decode(TypedChunkNbt.serializer(), documentChunk))

            val stream = Buffer()
            format.encodeToSink(TypedChunkNbt.serializer(), expected, compression, stream)
            assertEquals(document, format.decodeFromSource(stream, compression))
            assertTrue(stream.exhausted())
        }

        assertFailsWith<IllegalArgumentException> {
            RegionChunkNbtFormat(NbtFormat)
        }
    }

    @Test
    fun loadsChunkNbtFromRegionBytesAndStreamsWithoutFilesystem() = runTest {
        val position = LocalChunkPosition(7, 11)
        val document = NbtDocument(
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
        val chunkNbt = RegionChunkNbtFormat()
        val encodedRegion = RegionFileFormat.encodeToByteArray(
            RegionFile(
                mapOf(
                    position to chunkNbt.encode(
                        document = document,
                        compression = Compression.ZLIB,
                        timestamp = 1_234_567,
                    ),
                ),
            ),
        )

        assertTrue(encodedRegion.externalChunks.isEmpty())
        val arrayLoaded = RegionFileFormat.decodeFromByteArray(encodedRegion.bytes)
        assertEquals(1_234_567, arrayLoaded[position]?.timestamp)
        assertEquals(document, chunkNbt.decode(arrayLoaded[position]!!))

        val stream = Buffer().also { it.write(encodedRegion.bytes) }
        val streamLoaded = RegionFileFormat.decodeFromSource(stream)
        assertTrue(stream.exhausted())
        assertEquals(document, chunkNbt.decode(streamLoaded[position]!!))
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
                hexBytes(
                    "7801010b00f4ff68656c6c6f20776f726c641a0b045d",
                ),
            ),
            Triple(
                Compression.ZLIB,
                dynamic,
                hexBytes(
                    "789cedca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f1dc84f97",
                ),
            ),
            Triple(
                Compression.GZIP,
                hello,
                hexBytes(
                    "1f8b08000000000000ffcb48cdc9c95728cf2fca49010085114a0d0b000000",
                ),
            ),
            Triple(
                Compression.GZIP,
                dynamic,
                hexBytes(
                    "1f8b08000000000000ffedca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f38398b9b94110000",
                ),
            ),
        )

        samples.forEach { (compression, expected, encoded) ->
            assertContentEquals(
                expected,
                CompressionCodecs.decompress(
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
            val encoded = CompressionCodecs.compress(compression, input)
            encoded[encoded.lastIndex / 2] =
                (encoded[encoded.lastIndex / 2].toInt() xor 1).toByte()
            val expectedFailure = if (compression == Compression.LZ4) {
                RegionFormatException::class
            } else {
                IOException::class
            }
            assertFailsWith(expectedFailure) {
                CompressionCodecs.decompress(
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
                val encoded = CompressionCodecs.compress(
                    compression,
                    input,
                )
                assertContentEquals(
                    input,
                    CompressionCodecs.decompress(
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
                CompressionCodecs.compress(
                    Compression.LZ4,
                    rawInput,
                ),
            ),
        )
        assertEquals(
            listOf(0x20),
            lz4BlockMethods(
                CompressionCodecs.compress(
                    Compression.LZ4,
                    compressedInput,
                ),
            ),
        )
        val multipleBlocks = CompressionCodecs.compress(
            Compression.LZ4,
            multipleBlocksInput,
        )
        assertEquals(2, lz4BlockMethods(multipleBlocks).size)
        assertContentEquals(
            multipleBlocksInput,
            CompressionCodecs.decompress(
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
            val compressor = CompressionCodecs
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
            val decompressor = CompressionCodecs
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
        val codecs = CompressionCodecs(
            mapOf(Compression.CUSTOM to reversingCodec),
        )
        val input = byteArrayOf(1, 2, 3)

        val encoded = codecs.compress(Compression.CUSTOM, input)

        assertContentEquals(byteArrayOf(3, 2, 1), encoded)
        assertContentEquals(
            input,
            codecs.decompress(Compression.CUSTOM, encoded),
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
        val codecs = CompressionCodecs(
            mapOf(Compression.CUSTOM to cancellingCodec),
        )

        assertSame(
            compressionCancellation,
            assertFailsWith<CancellationException> {
                codecs.compress(Compression.CUSTOM, byteArrayOf(1))
            },
        )
        assertSame(
            decompressionCancellation,
            assertFailsWith<CancellationException> {
                codecs.decompress(
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
        val encoded = RegionFileFormat.encodeToByteArray(
            RegionFile(
                mapOf(
                    firstPosition to RegionChunk(
                        Compression.NONE,
                        RegionChunkPayload.Inline(firstPayload),
                    ),
                    secondPosition to RegionChunk(
                        Compression.ZLIB,
                        RegionChunkPayload.Inline(secondPayload),
                    ),
                ),
            ),
        )
        val observed = linkedMapOf<LocalChunkPosition, ByteArray>()

        RegionFileFormat.readChunksFromSource(Buffer().apply { write(encoded.bytes) }) { info, source ->
            observed[info.position] = source.readByteArray()
            assertEquals(observed.getValue(info.position).size, info.compressedLength)
        }

        assertContentEquals(firstPayload, observed.getValue(firstPosition))
        assertContentEquals(secondPayload, observed.getValue(secondPosition))
    }

    @Test
    fun streamingRegionPayloadMustBeFullyConsumedDespiteBufferReadAhead() {
        val position = LocalChunkPosition(0, 0)
        val encoded = RegionFileFormat.encodeToByteArray(
            RegionFile(
                mapOf(
                    position to RegionChunk(
                        Compression.NONE,
                        RegionChunkPayload.Inline(ByteArray(32) { it.toByte() }),
                    ),
                ),
            ),
        )

        assertFailsWith<RegionFormatException> {
            RegionFileFormat.readChunksFromSource(Buffer().apply { write(encoded.bytes) }) { _, payload ->
                payload.readByte()
            }
        }
    }

    @Test
    fun rejectsMissingCustomCodecAndKeepsByteArrayAdaptersIsolated() = runTest {
        assertFailsWith<RegionFormatException> {
            CompressionCodecs.compress(
                Compression.CUSTOM,
                byteArrayOf(),
            )
        }
        assertFailsWith<RegionFormatException> {
            CompressionCodecs.decompress(
                Compression.CUSTOM,
                byteArrayOf(),
            )
        }
        val original = byteArrayOf(1, 2, 3)
        val compressed = CompressionCodecs.compress(
            Compression.NONE,
            original,
        )
        compressed[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), original)
        val decoded = CompressionCodecs.decompress(
            Compression.NONE,
            original,
        )
        decoded[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), original)
    }

    @Test
    fun rejectsMalformedZlibGzipAndLz4HeadersAndTrailers() = runTest {
        val input = "compression-probe".encodeToByteArray()
        val zlib = CompressionCodecs.compress(
            Compression.ZLIB,
            input,
        )
        val gzip = CompressionCodecs.compress(
            Compression.GZIP,
            input,
        )
        val lz4 = CompressionCodecs.compress(
            Compression.LZ4,
            input,
        )

        fun rejectsFormat(
            compression: Compression,
            bytes: ByteArray,
        ) {
            assertFailsWith<RegionFormatException> {
                CompressionCodecs.decompress(
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
                CompressionCodecs.decompress(
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
            CompressionCodecs
                .compress(Compression.LZ4, byteArrayOf())
                .also { writeIntLittleEndian(it, 17, 1) },
        )
    }

    @Test
    fun gzipDecoderAcceptsEveryOptionalHeaderField() = runTest {
        val input = "optional-gzip-header\u0000".encodeToByteArray()
        val ordinary = CompressionCodecs.compress(
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
            CompressionCodecs.decompress(
                Compression.GZIP,
                withOptionalHeader,
            ),
        )
    }

    @Test
    fun contentBackedRegionValuesUseContentEquality() {
        val inlineA = RegionChunkPayload.Inline(byteArrayOf(1, 2))
        val inlineB = RegionChunkPayload.Inline(byteArrayOf(1, 2))
        val externalA = RegionChunkPayload.External(byteArrayOf(3, 4))
        val externalB = RegionChunkPayload.External(byteArrayOf(3, 4))
        assertEquals(inlineA, inlineB)
        assertEquals(inlineA.hashCode(), inlineB.hashCode())
        assertEquals(externalA, externalB)
        assertEquals(externalA.hashCode(), externalB.hashCode())
        assertNotEquals(externalA, RegionChunkPayload.External())

        val position = LocalChunkPosition(1, 2)
        val first = EncodedRegionFile(
            bytes = byteArrayOf(5, 6),
            externalChunks = mapOf(position to byteArrayOf(7, 8)),
        )
        val second = EncodedRegionFile(
            bytes = byteArrayOf(5, 6),
            externalChunks = mapOf(position to byteArrayOf(7, 8)),
        )
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, second.copy(bytes = byteArrayOf(5, 9)))
    }

    @Test
    fun chunkNbtFormatRejectsUnresolvedAndRoundTripsPayloads() = runTest {
        val format = RegionChunkNbtFormat()
        assertFailsWith<RegionFormatException> {
            format.decode(
                RegionChunk(
                    Compression.ZLIB,
                    RegionChunkPayload.External(),
                ),
            )
        }
        val document = NbtDocument(
            NbtCompound(mapOf("value" to NbtInt(1))),
        )

        assertEquals(document, format.decode(format.encode(document)))
    }

    private fun nbtBinaryFormatBytes(): ByteArray =
        NbtFormat.encodeDocumentToByteArray(
            NbtDocument(
                NbtCompound(mapOf("value" to NbtInt(1))),
            ),
        )

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2)
                .toInt(16)
                .toByte()
        }
    }

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
