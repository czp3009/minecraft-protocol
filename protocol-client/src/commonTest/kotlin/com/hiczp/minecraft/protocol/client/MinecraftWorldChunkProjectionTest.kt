package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtLongArray
import com.hiczp.minecraft.protocol.datapack.toChunkDataRegistries
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.ChunkSection
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import com.hiczp.minecraft.world.format.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import com.hiczp.minecraft.world.format.BlockPosition as WorldBlockPosition

class MinecraftWorldChunkProjectionTest {
    @Test
    fun decodesPacketContentWithoutCrossValidatingSectionCounts() {
        val protocolRegistryContext = testProtocolRegistryContext()
        val airProtocolBlockState = protocolRegistryContext.blockStates[0]
        val stoneProtocolBlockState = protocolRegistryContext.blockStates[1]
        val plainsProtocolRegistryEntry = protocolRegistryContext.requireRegistryEntry(
            ProtocolRegistryContext.BIOME_REGISTRY,
            MinecraftBiomeIds.PLAINS,
        )

        val chunkLayout = ChunkLayout(minSectionY = -1, sectionCount = 2)
        val chunkCodecContext = ChunkCodecContext(chunkLayout, protocolRegistryContext.toChunkDataRegistries())
        val minecraftChunkPacketDecoder = MinecraftChunkPacketDecoder(
            protocolRegistryContext,
            chunkCodecContext,
        )
        assertEquals(airProtocolBlockState, minecraftChunkPacketDecoder.chunkDataRegistries.blockStates.defaultValue)
        val lightBytes = ByteArray(LightDataLayer.DATA_LAYER_BYTES).apply { this[0] = 7 }
        val chunkDataAndUpdateLightPacket = ChunkDataAndUpdateLightPacket(
            chunkX = -1,
            chunkZ = 2,
            chunkData = ChunkData(
                heightmaps = mapOf(HeightmapType.WORLD_SURFACE to longArrayOf(3L)),
                sections = listOf(
                    ChunkSection(
                        nonAirBlockCount = ChunkSection.BLOCK_COUNT,
                        fluidCount = 0,
                        blockStates = PalettedContainer.Single(stoneProtocolBlockState.id),
                        biomes = PalettedContainer.Single(plainsProtocolRegistryEntry.rawId),
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

        val chunk = chunkDataAndUpdateLightPacket.toChunk(minecraftChunkPacketDecoder)

        assertEquals(ChunkPosition(-1, 2), chunkDataAndUpdateLightPacket.chunkPosition)
        assertEquals(chunkDataAndUpdateLightPacket.chunkPosition, chunk.chunkPosition)
        assertEquals(stoneProtocolBlockState, chunk.block(ChunkBlockPosition(0, -16, 0)))
        assertEquals(plainsProtocolRegistryEntry, chunk.biome(0, -16, 0))
        assertEquals(7.toByte(), chunk.section(-1)?.blockLight?.get(0))
        assertNull(chunk.section(-1)?.skyLight)
        assertNull(chunk.chunkMetadata.chunkStorageMetadata)
        assertEquals(NbtLongArray(longArrayOf(3L)), chunk.chunkMetadata.heightmaps[HeightmapType.WORLD_SURFACE.name])

        val blockEntity = assertIs<BlockEntity>(chunk.blockEntity(ChunkBlockPosition(3, -1, 4)))
        assertEquals("minecraft:chest", blockEntity.type)
        assertEquals(WorldBlockPosition(-13, -1, 36), blockEntity.blockPosition)
        assertEquals(NbtInt(9), blockEntity.persistentData["custom"])
        assertEquals(stoneProtocolBlockState, chunk.section(-1)?.block(LocalBlockPosition(15, 15, 15)))
        assertEquals(airProtocolBlockState, chunk.block(ChunkBlockPosition(0, 0, 0)))
    }

    private fun testProtocolRegistryContext(): ProtocolRegistryContext {
        val air = MinecraftBlockIds.AIR
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
                    listOf(ProtocolRegistryEntry(MinecraftBiomeIds.PLAINS, 0)),
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
