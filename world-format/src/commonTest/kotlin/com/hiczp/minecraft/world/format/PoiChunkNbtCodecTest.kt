package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import kotlin.test.*

class PoiChunkNbtCodecTest {
    private val poiChunkNbtCodec = PoiChunkNbtCodec()

    @Test
    fun semanticPoiChunkRoundTripsSectionsRecordsAndAbsolutePosition() {
        val chunkPosition = ChunkPosition(19, 4)
        val home = PoiRecord("minecraft:home", BlockPosition(316, -15, 69), freeTickets = 1)
        val poiChunk = PoiChunk(
            chunkPosition = chunkPosition,
            dataVersion = Int.MIN_VALUE,
            sections = listOf(PoiSection(sectionY = -1, valid = true, records = listOf(home))),
        )

        val nbtDocument = poiChunkNbtCodec.encodeDocument(poiChunk)
        val decoded = poiChunkNbtCodec.decodeDocument(nbtDocument, chunkPosition)

        assertEquals(Int.MIN_VALUE, decoded.dataVersion)
        assertEquals(chunkPosition, decoded.chunkPosition)
        assertEquals(1, decoded.recordCount)
        val decodedHome = assertNotNull(decoded.record(home.blockPosition))
        assertEquals(home.type, decodedHome.type)
        assertEquals(home.freeTickets, decodedHome.freeTickets)
        assertTrue(decodedHome.hasSpace)
        assertTrue(assertNotNull(decoded.section(-1)).valid)

        val sectionTag = assertNotNull(assertIsCompound(nbtDocument.root["Sections"])["-1"])
        assertTrue("Valid" in assertIsCompound(sectionTag).value)
        val compressedChunk = decoded.toCompressedChunk(poiChunkNbtCodec, Compression.NONE)
        assertEquals(1, compressedChunk.toPoiChunk(chunkPosition, poiChunkNbtCodec).recordCount)
    }

    @Test
    fun codecUsesOfficialDefaultsAndRejectsUnmodeledOrMispositionedData() {
        val chunkPosition = ChunkPosition(0, 0)
        val document = NbtDocument(
            NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(4_903),
                    "Sections" to NbtCompound(
                        mapOf(
                            "0" to NbtCompound(
                                mapOf(
                                    "Records" to NbtList(
                                        listOf(
                                            NbtCompound(
                                                mapOf(
                                                    "pos" to NbtIntArray(intArrayOf(1, 2, 3)),
                                                    "type" to NbtString("minecraft:home"),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val decoded = poiChunkNbtCodec.decodeDocument(document, chunkPosition)
        val poiSection = assertNotNull(decoded.section(0))
        assertFalse(poiSection.valid)
        assertEquals(0, poiSection.records.single().freeTickets)

        assertFailsWith<PoiChunkNbtFormatException> {
            poiChunkNbtCodec.decodeDocument(document, ChunkPosition(1, 0))
        }
        assertFailsWith<PoiChunkNbtFormatException> {
            poiChunkNbtCodec.decodeDocument(
                NbtDocument(
                    NbtCompound(document.root.value + ("future" to NbtInt(1))),
                ),
                chunkPosition,
            )
        }
    }

    @Test
    fun semanticMutationMaintainsSectionAndChunkOwnership() {
        val poiChunk = PoiChunk(ChunkPosition(0, 0), 4_903)
        val poiRecord = PoiRecord("minecraft:home", BlockPosition(1, 65, 2), 1)

        poiChunk.addRecord(poiRecord, sectionValid = true)
        val poiSection = assertNotNull(poiChunk.section(poiRecord.blockPosition))
        assertEquals(poiRecord, poiChunk.record(poiRecord.blockPosition))
        assertTrue(poiChunk.hasSection(poiRecord.blockPosition))
        assertTrue(poiChunk.hasRecord(poiRecord.blockPosition))
        assertTrue(poiSection.valid)
        assertFailsWith<IllegalArgumentException> { poiChunk.addRecord(poiRecord.snapshot()) }
        assertFailsWith<IllegalArgumentException> {
            poiChunk.addRecord(PoiRecord("minecraft:home", BlockPosition(17, 65, 2), 1))
        }
        assertEquals(poiRecord, poiChunk.removeRecord(poiRecord.blockPosition))
        assertTrue(poiSection.isEmpty)
    }
}

private fun assertIsCompound(value: NbtTag?): NbtCompound =
    value as? NbtCompound ?: error("Expected TAG_Compound, got ${value?.let { it::class.simpleName }}")
