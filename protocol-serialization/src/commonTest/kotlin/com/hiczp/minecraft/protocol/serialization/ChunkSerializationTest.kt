package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.ChunkData
import com.hiczp.minecraft.protocol.model.type.ChunkSection
import com.hiczp.minecraft.protocol.model.type.PackedLongArray
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkSerializationTest {
    @Test
    fun `wiki chunk-section example has exact bytes`() {
        val section = wikiExampleSection()
        val expected = "00000000000001022703ccffccffccffccff".hexToByteArray()

        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(ChunkSection.serializer(), section),
        )
        assertEquals(
            expected = section,
            actual = MinecraftFormat.decodeFromByteArray(ChunkSection.serializer(), expected),
        )
    }

    @Test
    fun `chunk section list is byte length prefixed and dimension sized`() {
        val chunk = ChunkData(
            heightmaps = emptyMap(),
            sections = listOf(wikiExampleSection()),
            blockEntities = emptyList(),
        )
        val format = MinecraftFormat(
            MinecraftFormatConfiguration(chunkSectionCount = 1),
        )
        val expected = (
                "0012" +
                        "00000000000001022703ccffccffccffccff" +
                        "00"
                ).hexToByteArray()

        assertContentEquals(
            expected,
            format.encodeToByteArray(ChunkData.serializer(), chunk),
        )
        assertEquals(
            chunk,
            format.decodeFromByteArray(ChunkData.serializer(), expected),
        )

        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.decodeFromByteArray(ChunkData.serializer(), expected)
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat(
                MinecraftFormatConfiguration(chunkSectionCount = 2),
            ).encodeToByteArray(ChunkData.serializer(), chunk)
        }
    }

    @Test
    fun `direct palettes derive bits and packed length from registries`() {
        val blockData = PackedLongArray(LongArray(1_024))
        val biomeData = PackedLongArray(LongArray(8))
        val section = ChunkSection(
            nonAirBlockCount = 0,
            fluidCount = 0,
            blockStates = PalettedContainer.Direct(blockData),
            biomes = PalettedContainer.Direct(biomeData),
        )

        val encoded = MinecraftFormat.encodeToByteArray(ChunkSection.serializer(), section)
        assertEquals(15, encoded[4].toInt() and 0xFF)
        assertEquals(7, encoded[4 + 1 + 1_024 * Long.SIZE_BYTES].toInt() and 0xFF)
        assertEquals(
            section,
            MinecraftFormat.decodeFromByteArray(ChunkSection.serializer(), encoded),
        )
    }

    @Test
    fun `vanilla normalizes low block-state BPE to four`() {
        val raw = ByteArray(4 + 1 + 1 + 1 + 256 * Long.SIZE_BYTES + 1 + 1)
        var index = 4
        raw[index++] = 1
        raw[index++] = 1
        raw[index++] = 0
        index += 256 * Long.SIZE_BYTES
        raw[index++] = 0
        raw[index] = 0

        val decoded = MinecraftFormat.decodeFromByteArray(ChunkSection.serializer(), raw)
        assertEquals(
            PalettedContainer.Indirect(
                bitsPerEntry = 4,
                palette = listOf(0),
                data = PackedLongArray(LongArray(256)),
            ),
            decoded.blockStates,
        )

        val canonical = MinecraftFormat.encodeToByteArray(ChunkSection.serializer(), decoded)
        assertEquals(4, canonical[4].toInt() and 0xFF)
    }

    @Test
    fun `palettes reject wrong packed sizes and unknown registry IDs`() {
        val invalidSize = ChunkSection(
            nonAirBlockCount = 0,
            fluidCount = 0,
            blockStates = PalettedContainer.Indirect(
                bitsPerEntry = 4,
                palette = listOf(0),
                data = PackedLongArray(LongArray(255)),
            ),
            biomes = PalettedContainer.Single(0),
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.encodeToByteArray(ChunkSection.serializer(), invalidSize)
        }

        val invalidId = ChunkSection(
            nonAirBlockCount = 0,
            fluidCount = 0,
            blockStates = PalettedContainer.Single(
                MinecraftFormatConfiguration.DEFAULT_BLOCK_STATE_REGISTRY_SIZE,
            ),
            biomes = PalettedContainer.Single(0),
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.encodeToByteArray(ChunkSection.serializer(), invalidId)
        }
    }

    private fun wikiExampleSection(): ChunkSection = ChunkSection(
        nonAirBlockCount = 0,
        fluidCount = 0,
        blockStates = PalettedContainer.Single(0),
        biomes = PalettedContainer.Indirect(
            bitsPerEntry = 1,
            palette = listOf(39, 3),
            data = PackedLongArray(
                longArrayOf(0xCCFFCCFFCCFFCCFFuL.toLong()),
            ),
        ),
    )
}
