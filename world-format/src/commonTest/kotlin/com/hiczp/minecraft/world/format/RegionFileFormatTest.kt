package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.protocol.model.type.NbtCompound
import com.hiczp.minecraft.protocol.model.type.NbtInt
import com.hiczp.minecraft.protocol.model.type.NbtString
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.*

class RegionFileFormatTest {
    @Test
    fun decodesOfficialZeroByteEmptyRegion() {
        assertEquals(RegionFile(), RegionFileFormat.decodeFromByteArray(byteArrayOf()))
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

        assertTrue(RegionFileFormat.encode(buffer, region).isEmpty())
        assertEquals(region, RegionFileFormat.decode(buffer))
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
            assertFailsWith<RegionFormatException> {
                RegionCompressionCodecs.decompress(
                    compression,
                    encoded,
                    input.size,
                )
            }
        }

        assertFailsWith<RegionFormatException> {
            RegionCompressionCodecs.decompress(
                RegionCompression.NONE,
                input,
                input.size - 1,
            )
        }
    }

    @Test
    fun customCompressionIsInjectable() = runTest {
        val reversingCodec = object : RegionCompressionCodec {
            override suspend fun compress(input: ByteArray): ByteArray =
                input.reversedArray()

            override suspend fun decompress(
                input: ByteArray,
                maximumOutputBytes: Int,
            ): ByteArray = input.reversedArray()
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
            strictRegion.decode(
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
            assertFailsWith<RegionFormatException> {
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
        assertFailsWith<RegionFormatException> {
            strict.decode(
                RegionChunk(
                    RegionCompression.ZLIB,
                    RegionChunkPayload.Inline(compressed),
                ),
            )
        }
    }

    private fun nbtBinaryFormatBytes(): ByteArray =
        com.hiczp.minecraft.nbt.NbtBinaryFormat.encodeDocumentToByteArray(
            NbtDocument(
                NbtCompound(mapOf("value" to NbtInt(1))),
            ),
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
}
