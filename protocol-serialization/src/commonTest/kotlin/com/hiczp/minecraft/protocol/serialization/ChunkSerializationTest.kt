package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.ClientboundLevelChunkPacketData
import com.hiczp.minecraft.protocol.model.packet.ClientboundLevelChunkWithLightPacket
import com.hiczp.minecraft.protocol.model.type.BitSet
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.ClientboundLightUpdatePacketData
import com.hiczp.minecraft.protocol.model.type.LevelChunkSectionData
import com.hiczp.minecraft.protocol.model.type.PackedLongArray
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

class ChunkSerializationTest {
    @Test
    fun `wiki chunk-section example has exact bytes`() {
        val chunkSection = wikiExampleSection()
        val expected = "00000000000001022703ccffccffccffccff".hexToByteArray()

        assertContentEquals(
            expected,
            testMinecraftPacketPayloadFormat().encodeToByteArray(chunkSection),
        )
        assertEquals(
            expected = chunkSection,
            actual = testMinecraftPacketPayloadFormat().decodeFromByteArray<LevelChunkSectionData>(expected),
        )
    }

    @Test
    fun `chunk packet bytes do not require a dimension or registries`() {
        val sectionFormat = sectionFormat(1)
        val chunkData = ClientboundLevelChunkPacketData(
            heightmaps = emptyMap(),
            buffer = sectionFormat.encode(listOf(wikiExampleSection())),
            blockEntitiesData = emptyList(),
        )
        val expected = "001200000000000001022703ccffccffccffccff00".hexToByteArray()

        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(chunkData),
        )
        assertEquals(
            chunkData,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundLevelChunkPacketData>(expected),
        )
        assertEquals(listOf(wikiExampleSection()), sectionFormat.decode(chunkData.buffer))

        assertFailsWith<MinecraftSerializationException> {
            sectionFormat(2).decode(chunkData.buffer)
        }
        assertFailsWith<IllegalArgumentException> {
            sectionFormat(2).encode(listOf(wikiExampleSection()))
        }

        val opaqueData = chunkData.copy(buffer = ByteString(byteArrayOf(127)))
        val mask = BitSet(longArrayOf())
        val packet = ClientboundLevelChunkWithLightPacket(
            -12, 9, opaqueData, ClientboundLightUpdatePacketData(mask, mask, mask, mask, emptyList(), emptyList()),
        )
        val bytes = MinecraftPacketPayloadFormat.encodeToByteArray(packet)
        assertEquals(
            packet,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundLevelChunkWithLightPacket>(bytes)
        )
        assertFailsWith<MinecraftSerializationException> { sectionFormat.decode(opaqueData.buffer) }
    }

    @Test
    fun `raw section length and section payload bounds have separate owners`() {
        val sectionFormat = sectionFormat(1)
        val bytes = sectionFormat.encode(listOf(wikiExampleSection())).toByteArray()
        assertFailsWith<MinecraftSerializationException> { sectionFormat.decode(ByteString(bytes.copyOf(bytes.size - 1))) }
        assertFailsWith<MinecraftSerializationException> { sectionFormat.decode(ByteString(bytes.copyOf(bytes.size + 1))) }
        assertFailsWith<IllegalArgumentException> { sectionFormat(-1) }
        val oversized = ClientboundLevelChunkPacketData(
            emptyMap(), ByteString(ByteArray(ClientboundLevelChunkPacketData.TWO_MEGABYTES + 1)), emptyList(),
        )
        assertFailsWith<MinecraftSerializationException> { MinecraftPacketPayloadFormat.encodeToByteArray(oversized) }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundLevelChunkPacketData>("0081808001".hexToByteArray())
        }
    }

    @Test
    fun `direct palettes derive bits and packed length from registries`() {
        val blockData = PackedLongArray(LongArray(1_024))
        val biomeData = PackedLongArray(LongArray(8))
        val chunkSection = LevelChunkSectionData(
            nonEmptyBlockCount = 0,
            fluidCount = 0,
            states = PalettedContainer.Direct(blockData),
            biomes = PalettedContainer.Direct(biomeData),
        )

        val minecraftPacketPayloadFormat = testMinecraftPacketPayloadFormat()
        val encoded = minecraftPacketPayloadFormat.encodeToByteArray(chunkSection)
        assertEquals(15, encoded[4].toInt() and 0xFF)
        assertEquals(7, encoded[4 + 1 + 1_024 * Long.SIZE_BYTES].toInt() and 0xFF)
        assertEquals(
            chunkSection,
            minecraftPacketPayloadFormat.decodeFromByteArray<LevelChunkSectionData>(encoded),
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

        val minecraftPacketPayloadFormat = testMinecraftPacketPayloadFormat()
        val decoded = minecraftPacketPayloadFormat.decodeFromByteArray<LevelChunkSectionData>(raw)
        assertEquals(
            PalettedContainer.Indirect(
                bitsPerEntry = 4,
                palette = listOf(0),
                data = PackedLongArray(LongArray(256)),
            ),
            decoded.states,
        )

        val canonical = minecraftPacketPayloadFormat.encodeToByteArray(decoded)
        assertEquals(4, canonical[4].toInt() and 0xFF)
    }

    @Test
    fun `palettes reject wrong packed sizes and unknown registry IDs`() {
        val invalidSize = LevelChunkSectionData(
            nonEmptyBlockCount = 0,
            fluidCount = 0,
            states = PalettedContainer.Indirect(
                bitsPerEntry = 4,
                palette = listOf(0),
                data = PackedLongArray(LongArray(255)),
            ),
            biomes = PalettedContainer.Single(0),
        )
        assertFailsWith<MinecraftSerializationException> {
            testMinecraftPacketPayloadFormat().encodeToByteArray(invalidSize)
        }

        val invalidId = LevelChunkSectionData(
            nonEmptyBlockCount = 0,
            fluidCount = 0,
            states = PalettedContainer.Single(
                TEST_BLOCK_STATE_REGISTRY_SIZE,
            ),
            biomes = PalettedContainer.Single(0),
        )
        assertFailsWith<MinecraftSerializationException> {
            testMinecraftPacketPayloadFormat().encodeToByteArray(invalidId)
        }
    }

    private fun wikiExampleSection(): LevelChunkSectionData = LevelChunkSectionData(
        nonEmptyBlockCount = 0,
        fluidCount = 0,
        states = PalettedContainer.Single(0),
        biomes = PalettedContainer.Indirect(
            bitsPerEntry = 1,
            palette = listOf(39, 3),
            data = PackedLongArray(
                longArrayOf(0xCCFFCCFFCCFFCCFFuL.toLong()),
            ),
        ),
    )

    private fun sectionFormat(sectionCount: Int) = MinecraftChunkSectionPayloadFormat(
        MinecraftChunkSectionPayloadFormatConfiguration(testPacketCodecContext(), sectionCount),
    )
}
