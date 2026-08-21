package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtLongArray
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.ChunkSection
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import com.hiczp.minecraft.world.format.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MinecraftWorldChunkProjectionTest {
    @Test
    fun convertsInstalledRegistriesForChunkNbtAndDecodesAChunkPacket() {
        val registries = testRegistries()
        val chunkDataRegistries = registries.toChunkDataRegistries()
        val air = registries.blockStates[0]
        val stone = registries.blockStates[1]
        val plains = registries.requireRegistryEntry(ProtocolRegistryContext.BIOME_REGISTRY, Identifier("plains"))

        assertEquals(air, chunkDataRegistries.blockStates.defaultValue)
        assertEquals(
            stone,
            chunkDataRegistries.blockStates.resolve(BlockStateDescriptor("minecraft:stone")),
        )
        assertEquals(
            BlockStateDescriptor("minecraft:stone"),
            chunkDataRegistries.blockStates.describe(stone),
        )
        assertEquals(plains, chunkDataRegistries.biomes.resolve("minecraft:plains"))
        assertEquals("minecraft:plains", chunkDataRegistries.biomes.name(plains))

        val chunkLayout = ChunkLayout(minSectionY = -1, sectionCount = 1)
        val chunkMetadata = ChunkMetadata(dataVersion = 1, status = "full")
        val chunkPacketDecoder = MinecraftChunkPacketDecoder(registries, chunkLayout, chunkMetadata)
        val lightBytes = ByteArray(LightDataLayer.DATA_LAYER_BYTES).apply { this[0] = 7 }
        val packet = ChunkDataAndUpdateLightPacket(
            chunkX = -1,
            chunkZ = 2,
            chunkData = ChunkData(
                heightmaps = mapOf(HeightmapType.WORLD_SURFACE to longArrayOf(3L)),
                sections = listOf(
                    ChunkSection(
                        nonAirBlockCount = ChunkSection.BLOCK_COUNT,
                        fluidCount = 0,
                        blockStates = PalettedContainer.Single(stone.id),
                        biomes = PalettedContainer.Single(plains.rawId),
                    ),
                ),
                blockEntities = listOf(
                    BlockEntityInfo(
                        packedXZ = 0x34,
                        y = (-1).toShort(),
                        typeId = 0,
                        tag = NbtCompound(mapOf("custom" to NbtInt(9))),
                    ),
                ),
            ),
            lightData = LightUpdateData(
                skyYMask = BitSet(LongArray(0)),
                blockYMask = BitSet(longArrayOf(1L shl 1)),
                emptySkyYMask = BitSet(LongArray(0)),
                emptyBlockYMask = BitSet(longArrayOf((1L shl 0) or (1L shl 2))),
                skyUpdates = emptyList(),
                blockUpdates = listOf(LightDataLayer(ByteString(lightBytes))),
            ),
        )

        val chunk = packet.toChunk(chunkPacketDecoder)

        assertEquals(ChunkPosition(-1, 2), packet.chunkPosition)
        assertEquals(stone, chunk.block(ChunkBlockPosition(0, -16, 0)))
        assertEquals(plains, chunk.biome(0, -16, 0))
        assertEquals(7.toByte(), chunk.section(-1)?.blockLight?.get(0))
        assertNull(chunk.section(-1)?.skyLight)
        assertEquals(NbtLongArray(longArrayOf(3L)), chunk.metadata.heightmaps[HeightmapType.WORLD_SURFACE.name])

        val blockEntity = assertIs<NbtCompound>(chunk.metadata.blockEntities[0])
        assertEquals(NbtString("minecraft:chest"), blockEntity["id"])
        assertEquals(NbtInt(-13), blockEntity["x"])
        assertEquals(NbtInt(-1), blockEntity["y"])
        assertEquals(NbtInt(36), blockEntity["z"])
        assertEquals(NbtInt(9), blockEntity["custom"])
        assertEquals(stone, chunk.section(-1)?.block(LocalBlockPosition(15, 15, 15)))
    }

    private fun testRegistries(): ProtocolRegistryContext {
        val air = Identifier("air")
        val stone = Identifier("stone")
        return ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    StaticRegistrySchema.BLOCK_REGISTRY,
                    listOf(
                        ProtocolRegistryEntry(air, 0),
                        ProtocolRegistryEntry(stone, 1),
                    ),
                ),
                ProtocolRegistry(
                    ProtocolRegistryContext.BIOME_REGISTRY,
                    listOf(ProtocolRegistryEntry(Identifier("plains"), 0)),
                ),
                ProtocolRegistry(
                    MinecraftChunkPacketDecoder.BLOCK_ENTITY_TYPE_REGISTRY,
                    listOf(ProtocolRegistryEntry(Identifier("chest"), 0)),
                ),
            ),
            blockStates = listOf(
                ProtocolBlockState(0, air, emptyMap(), isDefault = true),
                ProtocolBlockState(1, stone, emptyMap(), isDefault = true),
            ),
            chunkSectionCount = 1,
        )
    }
}
