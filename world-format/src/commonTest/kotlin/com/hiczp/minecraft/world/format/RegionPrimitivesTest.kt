package com.hiczp.minecraft.world.format

import kotlin.test.*

class RegionPrimitivesTest {
    @Test
    fun locationsPackOffsetsAndCountsAcrossTheirFullWireRanges() {
        val maximum = RegionLocation(
            REGION_MAX_SECTOR_OFFSET,
            REGION_MAX_SECTOR_COUNT,
        )

        assertEquals(-1, maximum.packed)
        assertEquals(maximum, RegionLocation.fromPacked(maximum.packed))
        assertEquals(
            REGION_MAX_SECTOR_OFFSET.toLong() * REGION_SECTOR_BYTES,
            maximum.byteOffset,
        )
        assertEquals(
            REGION_MAX_SECTOR_COUNT * REGION_SECTOR_BYTES,
            maximum.allocatedBytes,
        )
        assertNull(RegionLocation.fromPacked(0))
        assertFailsWith<IllegalArgumentException> {
            RegionLocation(-1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionLocation(REGION_MAX_SECTOR_OFFSET + 1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionLocation(2, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionLocation(2, REGION_MAX_SECTOR_COUNT + 1)
        }
    }

    @Test
    fun headerRoundTripsEveryEntryAndUsesBigEndianIntegers() {
        val regionHeader = RegionHeader()
        for (index in 0 until REGION_CHUNK_COUNT) {
            val localChunkPosition = LocalChunkPosition.fromIndex(index)
            regionHeader.set(
                localChunkPosition = localChunkPosition,
                regionLocation = RegionLocation(index + 2, index % 255 + 1),
                timestamp = index * 31,
            )
        }

        val byteArray = regionHeader.encode()
        assertEquals(REGION_HEADER_BYTES, byteArray.size)
        assertContentEquals(
            byteArrayOf(0, 0, 2, 1),
            byteArray.copyOfRange(0, Int.SIZE_BYTES),
        )
        assertEquals(regionHeader, RegionHeader.decode(byteArray))
    }

    @Test
    fun shortHeaderTreatsItsMissingSuffixAsZero() {
        val regionHeader = RegionHeader.decode(byteArrayOf(0, 0, 2, 1))

        assertEquals(
            RegionLocation(sectorOffset = 2, sectorCount = 1),
            regionHeader.location(LocalChunkPosition(0, 0)),
        )
        assertNull(regionHeader.location(LocalChunkPosition(1, 0)))
        assertEquals(0, regionHeader.timestamp(LocalChunkPosition(0, 0)))
        assertFailsWith<IllegalArgumentException> {
            RegionHeader.decode(ByteArray(REGION_HEADER_BYTES + 1))
        }
    }

    @Test
    fun headerCopiesAreIndependentAndClearingPreservesTimestamp() {
        val localChunkPosition = LocalChunkPosition(31, 31)
        val firstPosition = LocalChunkPosition(0, 0)
        val original = RegionHeader().apply {
            set(firstPosition, RegionLocation(2, 1), timestamp = 1)
            set(localChunkPosition, RegionLocation(9, 2), timestamp = -1)
        }
        val copy = original.copy()

        copy.clearLocation(localChunkPosition)

        assertEquals(2, original.chunkCount)
        assertTrue(original.hasChunk(firstPosition))
        assertTrue(original.hasChunk(localChunkPosition))
        assertEquals(listOf(firstPosition, localChunkPosition), original.localChunkPositions().toList())
        assertEquals(1, copy.chunkCount)
        assertFalse(copy.hasChunk(localChunkPosition))
        assertEquals(listOf(firstPosition), copy.localChunkPositions().toList())
        assertEquals(RegionLocation(9, 2), original.location(localChunkPosition))
        assertNull(copy.location(localChunkPosition))
        assertEquals(-1, copy.timestamp(localChunkPosition))
        assertFalse(original == Any())
        assertEquals(original.hashCode(), original.copy().hashCode())
    }

    @Test
    fun openCompatibilityChecksMatchVanillaBoundaries() {
        assertFalse(RegionLocation(1, 1).isUsableAtOpen(REGION_HEADER_BYTES.toLong()))
        assertFalse(RegionLocation(2, 0).isUsableAtOpen(REGION_HEADER_BYTES.toLong()))
        assertTrue(RegionLocation(2, 1).isUsableAtOpen(REGION_HEADER_BYTES.toLong()))
        assertFalse(RegionLocation(3, 1).isUsableAtOpen(REGION_HEADER_BYTES.toLong()))
    }

    @Test
    fun sectorAllocatorUsesFirstFitAndAllowsOverlappingMarks() {
        val regionSectorAllocator = RegionSectorAllocator()
        regionSectorAllocator.mark(RegionLocation(2, 2))
        regionSectorAllocator.mark(RegionLocation(3, 2))

        assertEquals(RegionLocation(5, 2), regionSectorAllocator.allocate(2))
        regionSectorAllocator.free(RegionLocation(2, 2))
        assertEquals(RegionLocation(2, 1), regionSectorAllocator.allocate(1))
        assertTrue(regionSectorAllocator.isUsed(0))
        assertTrue(regionSectorAllocator.isUsed(1))
        assertFalse(regionSectorAllocator.isUsed(-1))
        assertFalse(regionSectorAllocator.isUsed(100))
        regionSectorAllocator.free(null)
        regionSectorAllocator.free(RegionLocation(0, 0))
        assertFailsWith<IllegalArgumentException> {
            regionSectorAllocator.mark(RegionLocation(2, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            regionSectorAllocator.allocate(0)
        }
        assertFailsWith<IllegalArgumentException> {
            regionSectorAllocator.allocate(REGION_MAX_SECTOR_COUNT + 1)
        }
    }

    @Test
    fun recordEncodingOwnsTheExactExternalThresholdAndCompressionFlags() {
        val lastInlinePayload = ByteArray(
            REGION_MAX_SECTOR_COUNT * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        )
        val firstExternalPayload = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        )

        Compression.entries.forEach { compression ->
            val inline = EncodedRegionChunkRecord.encode(
                compression,
                lastInlinePayload,
            )
            assertFalse(inline.external)
            assertEquals(REGION_MAX_SECTOR_COUNT, inline.allocatedSectors)
            assertEquals(
                RegionChunkRecordHeader(
                    length = lastInlinePayload.size + 1,
                    compression = compression,
                    external = false,
                ),
                RegionChunkRecordHeader.decode(inline.bytes),
            )

            val external = EncodedRegionChunkRecord.encode(
                compression,
                firstExternalPayload,
            )
            assertTrue(external.external)
            assertEquals(1, external.allocatedSectors)
            assertEquals(
                RegionChunkRecordHeader(
                    length = 1,
                    compression = compression,
                    external = true,
                ),
                RegionChunkRecordHeader.decode(external.bytes),
            )
            assertContentEquals(firstExternalPayload, external.externalPayload)
        }
    }

    @Test
    fun recordHelpersCoverEmptyForcedAndMalformedRecords() {
        val empty = EncodedRegionChunkRecord.encode(
            Compression.NONE,
            ByteArray(0),
        )
        assertFalse(empty.external)
        assertEquals(1, empty.allocatedSectors)
        assertEquals(1, RegionChunkRecordHeader.decode(empty.bytes).length)

        val forced = EncodedRegionChunkRecord.encode(
            Compression.LZ4,
            byteArrayOf(1, 2, 3),
            forceExternal = true,
        )
        assertTrue(forced.external)
        assertEquals(1, forced.allocatedSectors)
        assertContentEquals(byteArrayOf(1, 2, 3), forced.externalPayload)

        assertFailsWith<AnvilFormatException> {
            RegionChunkRecordHeader.decode(ByteArray(4))
        }
        assertFailsWith<AnvilFormatException> {
            RegionChunkRecordHeader.decode(
                byteArrayOf(0, 0, 0, 1, 5),
            )
        }

        assertFailsWith<IllegalArgumentException> { regionSectorsForBytes(-1) }
        assertEquals(0, regionSectorsForBytes(0))
        assertEquals(1, regionSectorsForBytes(1))
        assertEquals(1, regionSectorsForBytes(REGION_SECTOR_BYTES.toLong()))
        assertEquals(
            2,
            regionSectorsForBytes(REGION_SECTOR_BYTES.toLong() + 1L),
        )
        val largestRepresentableBytes = Int.MAX_VALUE.toLong() * REGION_SECTOR_BYTES
        assertEquals(
            Int.MAX_VALUE,
            regionSectorsForBytes(largestRepresentableBytes),
        )
        assertFailsWith<AnvilFormatException> {
            regionSectorsForBytes(largestRepresentableBytes + 1L)
        }
        assertFailsWith<AnvilFormatException> {
            regionSectorsForBytes(Long.MAX_VALUE)
        }
    }
}
