package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.ChunkData
import com.hiczp.minecraft.protocol.model.type.ChunkSection
import com.hiczp.minecraft.protocol.model.type.PackedLongArray
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkSerializationTest {
    @Test
    fun `wiki chunk-section example has exact bytes`() {
        val chunkSection = wikiExampleSection()
        val expected = "00000000000001022703ccffccffccffccff".hexToByteArray()

        assertContentEquals(
            expected,
            testMinecraftProtocolFormat().encodeToByteArray(chunkSection),
        )
        assertEquals(
            expected = chunkSection,
            actual = testMinecraftProtocolFormat().decodeFromByteArray<ChunkSection>(expected),
        )
    }

    @Test
    fun `chunk section list is byte length prefixed and dimension sized`() {
        val chunkData = ChunkData(
            heightmaps = emptyMap(),
            sections = listOf(wikiExampleSection()),
            blockEntities = emptyList(),
        )
        val minecraftProtocolFormat = testMinecraftProtocolFormat(chunkSectionCount = 1)
        val expected = "001200000000000001022703ccffccffccffccff00".hexToByteArray()

        assertContentEquals(
            expected,
            minecraftProtocolFormat.encodeToByteArray(chunkData),
        )
        assertEquals(
            chunkData,
            minecraftProtocolFormat.decodeFromByteArray<ChunkData>(expected),
        )

        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray<ChunkData>(expected)
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat(
                MinecraftProtocolFormatConfiguration(
                    protocolRegistryContext = testProtocolRegistryContext(chunkSectionCount = 2),
                ),
            ).encodeToByteArray(chunkData)
        }
    }

    @Test
    fun `direct palettes derive bits and packed length from registries`() {
        val blockData = PackedLongArray(LongArray(1_024))
        val biomeData = PackedLongArray(LongArray(8))
        val chunkSection = ChunkSection(
            nonAirBlockCount = 0,
            fluidCount = 0,
            blockStates = PalettedContainer.Direct(blockData),
            biomes = PalettedContainer.Direct(biomeData),
        )

        val minecraftProtocolFormat = testMinecraftProtocolFormat()
        val encoded = minecraftProtocolFormat.encodeToByteArray(chunkSection)
        assertEquals(15, encoded[4].toInt() and 0xFF)
        assertEquals(7, encoded[4 + 1 + 1_024 * Long.SIZE_BYTES].toInt() and 0xFF)
        assertEquals(
            chunkSection,
            minecraftProtocolFormat.decodeFromByteArray<ChunkSection>(encoded),
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

        val minecraftProtocolFormat = testMinecraftProtocolFormat()
        val decoded = minecraftProtocolFormat.decodeFromByteArray<ChunkSection>(raw)
        assertEquals(
            PalettedContainer.Indirect(
                bitsPerEntry = 4,
                palette = listOf(0),
                data = PackedLongArray(LongArray(256)),
            ),
            decoded.blockStates,
        )

        val canonical = minecraftProtocolFormat.encodeToByteArray(decoded)
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
            testMinecraftProtocolFormat().encodeToByteArray(invalidSize)
        }

        val invalidId = ChunkSection(
            nonAirBlockCount = 0,
            fluidCount = 0,
            blockStates = PalettedContainer.Single(
                TEST_BLOCK_STATE_REGISTRY_SIZE,
            ),
            biomes = PalettedContainer.Single(0),
        )
        assertFailsWith<MinecraftSerializationException> {
            testMinecraftProtocolFormat().encodeToByteArray(invalidId)
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
