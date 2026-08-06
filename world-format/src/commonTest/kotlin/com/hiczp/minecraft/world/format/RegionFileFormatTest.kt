package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.coroutines.test.runTest
import kotlinx.io.*
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
        assertEquals(
            RegionPosition(-1, -1),
            ChunkPosition(-1, -1).region,
        )
        assertEquals(
            LocalChunkPosition(31, 31),
            ChunkPosition(-1, -1).local,
        )
        assertEquals(
            ChunkPosition(-1, -1),
            RegionPosition(-1, -1).chunk(LocalChunkPosition(31, 31)),
        )
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
    fun roundTripsInlineChunksAndHeaderMetadata() {
        val firstPosition = LocalChunkPosition(0, 0)
        val lastPosition = LocalChunkPosition(31, 31)
        val region = RegionFile(
            linkedMapOf(
                firstPosition to RegionChunk(
                    compression = RegionCompression.ZLIB,
                    payload = RegionChunkPayload.Inline(byteArrayOf(1, 2, 3)),
                    timestamp = 123,
                ),
                lastPosition to RegionChunk(
                    compression = RegionCompression.NONE,
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
                    RegionCompression.GZIP,
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
                    RegionCompression.LZ4,
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
        assertEquals(RegionCompression.LZ4, chunk.compression)
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
                        RegionCompression.NONE,
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
        val largestInlinePayload =
            255 * REGION_SECTOR_BYTES - Int.SIZE_BYTES - 1
        val firstExternalPayload = largestInlinePayload + 1
        val encoded = RegionFileFormat.encodeToByteArray(
            RegionFile(
                linkedMapOf(
                    inline to RegionChunk(
                        RegionCompression.NONE,
                        RegionChunkPayload.Inline(
                            ByteArray(largestInlinePayload),
                        ),
                    ),
                    external to RegionChunk(
                        RegionCompression.NONE,
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
                    compression = RegionCompression.entries[
                        random.nextInt(RegionCompression.entries.size)
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
                            RegionCompression.ZLIB,
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
        overlap[2 * REGION_SECTOR_BYTES + 4] = RegionCompression.NONE.id.toByte()
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
                        version = RegionCompression.NONE.id,
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
            version = RegionCompression.ZLIB.id or 0x80,
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
            RegionCompression.GZIP,
            RegionCompression.ZLIB,
            RegionCompression.NONE,
            RegionCompression.LZ4,
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
                        compression = RegionCompression.ZLIB,
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
                RegionCompression.ZLIB,
                hello,
                hexBytes(
                    "7801010b00f4ff68656c6c6f20776f726c641a0b045d",
                ),
            ),
            Triple(
                RegionCompression.ZLIB,
                dynamic,
                hexBytes(
                    "789cedca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f1dc84f97",
                ),
            ),
            Triple(
                RegionCompression.GZIP,
                hello,
                hexBytes(
                    "1f8b08000000000000ffcb48cdc9c95728cf2fca49010085114a0d0b000000",
                ),
            ),
            Triple(
                RegionCompression.GZIP,
                dynamic,
                hexBytes(
                    "1f8b08000000000000ffedca470180301045412b5f016a628092d0d910084d3d88e0f8ce33aef35a735f8faa929d8b825d1af21c37d9e193f68fa7f2b9d5585bc891c96432994c2693c96432994c2693ffc82f38398b9b94110000",
                ),
            ),
        )

        samples.forEach { (compression, expected, encoded) ->
            assertContentEquals(
                expected,
                RegionCompressionCodecs.decompress(
                    compression,
                    encoded,
                    expected.size,
                ),
            )
        }
    }

    @Test
    fun compressionRejectsCorruptionAndOutputLimit() = runTest {
        val input = ByteArray(10_000) { (it * 31).toByte() }
        for (compression in listOf(
            RegionCompression.GZIP,
            RegionCompression.ZLIB,
            RegionCompression.LZ4,
        )) {
            val encoded = RegionCompressionCodecs.compress(compression, input)
            encoded[encoded.lastIndex / 2] =
                (encoded[encoded.lastIndex / 2].toInt() xor 1).toByte()
            assertFailsWith<IOException> {
                RegionCompressionCodecs.decompress(
                    compression,
                    encoded,
                    input.size,
                )
            }
        }

        assertFailsWith<IOException> {
            RegionCompressionCodecs.decompress(
                RegionCompression.NONE,
                input,
                input.size - 1,
            )
        }
    }

    @Test
    fun everyCompressionModeRoundTripsSizeAndLimitBoundaries() = runTest {
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
            RegionCompression.GZIP,
            RegionCompression.ZLIB,
            RegionCompression.NONE,
            RegionCompression.LZ4,
        )) {
            samples.forEachIndexed { index, input ->
                val encoded = RegionCompressionCodecs.compress(
                    compression,
                    input,
                )
                assertContentEquals(
                    input,
                    RegionCompressionCodecs.decompress(
                        compression,
                        encoded,
                        input.size,
                    ),
                    "$compression sample $index failed",
                )
                if (input.isNotEmpty()) {
                    assertFailsWith<IOException>(
                        "$compression accepted output above its limit",
                    ) {
                        RegionCompressionCodecs.decompress(
                            compression,
                            encoded,
                            input.size - 1,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun compressionDecoratorsProduceAndConsumeIncrementally() {
        val input = ByteArray(192 * 1_024) { index ->
            (index * 37 + index / 11).toByte()
        }
        for (compression in listOf(
            RegionCompression.NONE,
            RegionCompression.GZIP,
            RegionCompression.ZLIB,
            RegionCompression.LZ4,
        )) {
            val encoded = Buffer()
            val compressor = RegionCompressionCodecs
                .compressingSink(compression, encoded)
                .buffered()
            val first = Buffer().apply {
                write(input, endIndex = input.size / 2)
            }
            compressor.write(first, first.size)
            compressor.flush()
            assertTrue(encoded.size > 0, "$compression did not stream output")
            val second = Buffer().apply {
                write(input, startIndex = input.size / 2)
            }
            compressor.write(second, second.size)
            compressor.close()

            val encodedBytes = encoded.readByteArray()
            val encodedSource = Buffer().apply { write(encodedBytes) }
            val decompressor = RegionCompressionCodecs
                .decompressingSource(
                    compression,
                    encodedSource,
                    input.size,
                )
                .buffered()
            val prefix = decompressor.readByteArray(32)

            assertContentEquals(input.copyOf(32), prefix)
            assertFalse(
                encodedSource.exhausted(),
                "$compression consumed the full input for a short prefix",
            )
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
        val reversingCodec = object : RegionCompressionCodec {
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
                        sink.write(bytes.readByteArray().reversedArray())
                    }
                }

            override fun decompressingSource(
                source: Source,
                maximumOutputBytes: Int,
            ): RawSource {
                val decoded = source.readByteArray().reversedArray()
                if (decoded.size > maximumOutputBytes) {
                    throw RegionFormatException("Custom output too large")
                }
                return Buffer().apply { write(decoded) }
            }
        }
        val codecs = RegionCompressionCodecs(
            mapOf(RegionCompression.CUSTOM to reversingCodec),
        )
        val input = byteArrayOf(1, 2, 3)

        val encoded = codecs.compress(RegionCompression.CUSTOM, input)

        assertContentEquals(byteArrayOf(3, 2, 1), encoded)
        assertContentEquals(
            input,
            codecs.decompress(RegionCompression.CUSTOM, encoded, 3),
        )
        assertFailsWith<RegionFormatException> {
            codecs.decompress(RegionCompression.CUSTOM, encoded, 2)
        }
    }

    @Test
    fun validatesConfigurationsCoordinatesAndCompressionIds() {
        assertFailsWith<IllegalArgumentException> {
            RegionFileFormatConfiguration(
                maximumRegionBytes = REGION_HEADER_BYTES - 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RegionFileFormatConfiguration(maximumCompressedChunkBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionChunkNbtFormatConfiguration(
                maximumDecompressedChunkBytes = -1,
            )
        }
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

        RegionCompression.entries.forEach {
            assertEquals(it, RegionCompression.fromId(it.id))
        }
        assertNull(RegionCompression.fromId(0))
        assertNull(RegionCompression.fromId(126))
    }

    @Test
    fun appliesRegionAndCompressedChunkLimitsToStreamsAndArrays() {
        val oneHeader = ByteArray(REGION_HEADER_BYTES)
        val strictRegion = RegionFileFormat(
            RegionFileFormatConfiguration(
                maximumRegionBytes = REGION_HEADER_BYTES,
                maximumCompressedChunkBytes = 0,
            ),
        )
        assertEquals(RegionFile(), strictRegion.decodeFromByteArray(oneHeader))
        assertFailsWith<RegionFormatException> {
            strictRegion.decodeFromByteArray(oneHeader + byteArrayOf(0))
        }
        assertFailsWith<RegionFormatException> {
            strictRegion.decodeFromSource(
                Buffer().also { it.write(oneHeader + byteArrayOf(0)) },
            )
        }
        assertFailsWith<RegionFormatException> {
            strictRegion.encodeToByteArray(
                RegionFile(
                    mapOf(
                        LocalChunkPosition(0, 0) to RegionChunk(
                            RegionCompression.NONE,
                            RegionChunkPayload.Inline(byteArrayOf(1)),
                        ),
                    ),
                ),
            )
        }

        val encoded = RegionFileFormat.encodeToByteArray(
            RegionFile(
                mapOf(
                    LocalChunkPosition(0, 0) to RegionChunk(
                        RegionCompression.NONE,
                        RegionChunkPayload.Inline(byteArrayOf(1)),
                    ),
                ),
            ),
        )
        assertFailsWith<RegionFormatException> {
            strictRegion.decodeFromByteArray(encoded.bytes)
        }
    }

    @Test
    fun rejectsMissingCustomCodecAndInvalidOutputLimits() = runTest {
        assertFailsWith<RegionFormatException> {
            RegionCompressionCodecs.compress(
                RegionCompression.CUSTOM,
                byteArrayOf(),
            )
        }
        assertFailsWith<RegionFormatException> {
            RegionCompressionCodecs.decompress(
                RegionCompression.CUSTOM,
                byteArrayOf(),
                0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RegionCompressionCodecs.decompress(
                RegionCompression.NONE,
                byteArrayOf(),
                -1,
            )
        }
        val original = byteArrayOf(1, 2, 3)
        val compressed = RegionCompressionCodecs.compress(
            RegionCompression.NONE,
            original,
        )
        compressed[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), original)
        val decoded = RegionCompressionCodecs.decompress(
            RegionCompression.NONE,
            original,
            original.size,
        )
        decoded[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), original)
    }

    @Test
    fun rejectsMalformedZlibGzipAndLz4HeadersAndTrailers() = runTest {
        val input = "compression-probe".encodeToByteArray()
        val zlib = RegionCompressionCodecs.compress(
            RegionCompression.ZLIB,
            input,
        )
        val gzip = RegionCompressionCodecs.compress(
            RegionCompression.GZIP,
            input,
        )
        val lz4 = RegionCompressionCodecs.compress(
            RegionCompression.LZ4,
            input,
        )

        suspend fun rejects(
            compression: RegionCompression,
            bytes: ByteArray,
        ) {
            assertFailsWith<IOException> {
                RegionCompressionCodecs.decompress(
                    compression,
                    bytes,
                    1_024,
                )
            }
        }

        rejects(RegionCompression.ZLIB, ByteArray(5))
        rejects(
            RegionCompression.ZLIB,
            zlib.copyOf().also { it[0] = 0x70 },
        )
        rejects(
            RegionCompression.ZLIB,
            zlib.copyOf().also { it[0] = 0x88.toByte() },
        )
        rejects(
            RegionCompression.ZLIB,
            zlib.copyOf().also { it[1] = 0 },
        )
        val dictionaryFlag = (0..255).first {
            it and 0x20 != 0 && ((0x78 shl 8) or it) % 31 == 0
        }
        rejects(
            RegionCompression.ZLIB,
            zlib.copyOf().also { it[1] = dictionaryFlag.toByte() },
        )
        rejects(
            RegionCompression.ZLIB,
            zlib.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            },
        )

        rejects(RegionCompression.GZIP, ByteArray(17))
        rejects(
            RegionCompression.GZIP,
            gzip.copyOf().also { it[0] = 0 },
        )
        rejects(
            RegionCompression.GZIP,
            gzip.copyOf().also { it[2] = 0 },
        )
        rejects(
            RegionCompression.GZIP,
            gzip.copyOf().also { it[3] = 0xE0.toByte() },
        )
        rejects(
            RegionCompression.GZIP,
            ByteArray(18).also {
                it[0] = 0x1F
                it[1] = 0x8B.toByte()
                it[2] = 8
                it[3] = 4
                it[10] = 100
            },
        )
        rejects(
            RegionCompression.GZIP,
            gzip.copyOf().also { it[3] = 2 },
        )
        rejects(
            RegionCompression.GZIP,
            gzip.copyOf().also {
                it[it.lastIndex - 7] =
                    (it[it.lastIndex - 7].toInt() xor 1).toByte()
            },
        )
        rejects(
            RegionCompression.GZIP,
            gzip.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            },
        )
        rejects(
            RegionCompression.GZIP,
            ByteArray(19) { 1 }.also {
                gzip.copyInto(it, endIndex = 10)
                it[3] = 0x08
            },
        )
        rejects(
            RegionCompression.GZIP,
            ByteArray(19) { 1 }.also {
                gzip.copyInto(it, endIndex = 10)
                it[3] = 0x10
            },
        )

        rejects(
            RegionCompression.LZ4,
            lz4.copyOf().also { it[0] = 0 },
        )
        rejects(
            RegionCompression.LZ4,
            lz4.copyOf().also { it[8] = 0 },
        )
        rejects(
            RegionCompression.LZ4,
            lz4.copyOf().also {
                it[17] = (it[17].toInt() xor 1).toByte()
            },
        )
        rejects(
            RegionCompression.LZ4,
            lz4 + byteArrayOf(0),
        )

        fun malformedLz4(
            compressedLength: Int,
            originalLength: Int,
        ): ByteArray = lz4.copyOf().also {
            writeIntLittleEndian(it, 9, compressedLength)
            writeIntLittleEndian(it, 13, originalLength)
        }

        rejects(RegionCompression.LZ4, malformedLz4(-1, input.size))
        rejects(RegionCompression.LZ4, malformedLz4(input.size, -1))
        rejects(RegionCompression.LZ4, malformedLz4(input.size, 65_537))
        rejects(RegionCompression.LZ4, malformedLz4(0, input.size))
        rejects(
            RegionCompression.LZ4,
            malformedLz4(input.size - 1, input.size),
        )
        rejects(
            RegionCompression.LZ4,
            RegionCompressionCodecs
                .compress(RegionCompression.LZ4, byteArrayOf())
                .also { writeIntLittleEndian(it, 17, 1) },
        )
    }

    @Test
    fun gzipDecoderAcceptsEveryOptionalHeaderField() = runTest {
        val input = "optional-gzip-header\u0000".encodeToByteArray()
        val ordinary = RegionCompressionCodecs.compress(
            RegionCompression.GZIP,
            input,
        )
        val header = ordinary.copyOfRange(0, 10).also {
            it[3] = 0x1E
        }
        val optionalFields =
            byteArrayOf(3, 0, 1, 2, 3) +
                    "region.mca".encodeToByteArray() +
                    byteArrayOf(0) +
                    "generated by test".encodeToByteArray() +
                    byteArrayOf(0)
        val headerWithoutCrc = header + optionalFields
        val headerCrc = crc32(headerWithoutCrc) and 0xFFFF
        val withOptionalHeader =
            headerWithoutCrc +
                    byteArrayOf(
                        headerCrc.toByte(),
                        (headerCrc ushr 8).toByte(),
                    ) +
                    ordinary.copyOfRange(10, ordinary.size)

        assertContentEquals(
            input,
            RegionCompressionCodecs.decompress(
                RegionCompression.GZIP,
                withOptionalHeader,
                input.size,
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
    fun chunkNbtFormatRejectsUnresolvedAndOversizedPayloads() = runTest {
        val strict = RegionChunkNbtFormat(
            configuration = RegionChunkNbtFormatConfiguration(
                maximumDecompressedChunkBytes = 1,
            ),
        )
        assertFailsWith<RegionFormatException> {
            strict.decode(
                RegionChunk(
                    RegionCompression.ZLIB,
                    RegionChunkPayload.External(),
                ),
            )
        }
        assertFailsWith<RegionFormatException> {
            strict.encode(
                NbtDocument(
                    NbtCompound(mapOf("value" to NbtInt(1))),
                ),
            )
        }
        val compressed = RegionCompressionCodecs.compress(
            RegionCompression.ZLIB,
            nbtBinaryFormatBytes(),
        )
        assertFailsWith<IOException> {
            strict.decode(
                RegionChunk(
                    RegionCompression.ZLIB,
                    RegionChunkPayload.Inline(compressed),
                ),
            )
        }
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

    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        bytes.forEach { byte ->
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor 0xEDB88320.toInt()
                } else {
                    crc ushr 1
                }
            }
        }
        return crc.inv()
    }
}
