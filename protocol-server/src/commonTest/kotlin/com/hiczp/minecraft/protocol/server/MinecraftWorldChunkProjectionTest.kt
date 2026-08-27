package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtLongArray
import com.hiczp.minecraft.protocol.client.MinecraftChunkPacketDecoder
import com.hiczp.minecraft.protocol.client.toChunk
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.world.format.PalettedContainer
import kotlinx.serialization.encodeToByteArray
import kotlin.test.*
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as NetworkPalettedContainer

class MinecraftWorldChunkProjectionTest {
    @Test
    fun projectsAStrongChunkToTheWireAndBackWithoutDensePaletteCopies() {
        val protocolRegistryContext = testProtocolRegistryContext()
        val minecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
            protocolRegistryContext = protocolRegistryContext,
            isAir = { protocolBlockState -> protocolBlockState.block == Identifier("air") },
            hasFluid = { protocolBlockState -> protocolBlockState.block == Identifier("water") },
            hasSkyLight = true,
        )
        val air = minecraftChunkPacketEncoder.chunkDataRegistries.blockStates.defaultValue
        val stone = assertNotNull(minecraftChunkPacketEncoder.chunkDataRegistries.blockStates.resolve(block("stone")))
        val water = assertNotNull(minecraftChunkPacketEncoder.chunkDataRegistries.blockStates.resolve(block("water")))
        val plains = minecraftChunkPacketEncoder.chunkDataRegistries.biomes.defaultValue
        val blockStates = PalettedContainer(com.hiczp.minecraft.world.format.SECTION_BLOCK_COUNT, air).apply {
            this[LocalBlockPosition(0, 0, 0).index] = stone
            this[LocalBlockPosition(1, 0, 0).index] = water
        }
        val blockLight = ByteArray(com.hiczp.minecraft.world.format.SECTION_LIGHT_BYTE_COUNT).apply { this[0] = 4 }
        val skyLight = ByteArray(com.hiczp.minecraft.world.format.SECTION_LIGHT_BYTE_COUNT).apply { this[1] = -1 }
        val chunkPosition = ChunkPosition(-1, 2)
        val chunkMetadata = ChunkMetadata(
            dataVersion = 1,
            status = "full",
            heightmaps = NbtCompound(mapOf("WORLD_SURFACE" to NbtLongArray(longArrayOf(11L)))),
            lightOnlySections = mapOf(1 to SectionLighting(skyLight = NbtByteArray(skyLight))),
        )
        val blockEntity = BlockEntity(
            type = "minecraft:chest",
            blockPosition = chunkPosition.block(ChunkBlockPosition(3, -1, 4)),
            persistentData = NbtCompound(mapOf("custom" to NbtInt(7))),
        )
        val chunkLayout = ChunkLayout(minSectionY = -1, sectionCount = 2)
        val chunk = Chunk(
            chunkPosition = chunkPosition,
            chunkMetadata = chunkMetadata,
            chunkLayout = chunkLayout,
            sections = listOf(
                ChunkSection(
                    sectionY = -1,
                    blockStates = blockStates,
                    biomes = PalettedContainer(com.hiczp.minecraft.world.format.SECTION_BIOME_COUNT, plains),
                    blockLight = NbtByteArray(blockLight),
                ),
            ),
            blockEntities = listOf(blockEntity),
            defaultBlockState = air,
            defaultBiome = plains,
        )

        val chunkDataAndUpdateLightPacket = chunk.toChunkDataAndUpdateLightPacket(minecraftChunkPacketEncoder)
        val minecraftChunkSnapshot = chunk.toMinecraftChunkSnapshot(minecraftChunkPacketEncoder)

        assertEquals(chunkPosition.x, minecraftChunkSnapshot.chunkX)
        assertEquals(chunkPosition.z, minecraftChunkSnapshot.chunkZ)
        assertEquals(2, chunkDataAndUpdateLightPacket.chunkData.sections.first().nonAirBlockCount)
        assertEquals(1, chunkDataAndUpdateLightPacket.chunkData.sections.first().fluidCount)
        assertIs<NetworkPalettedContainer.Indirect>(
            chunkDataAndUpdateLightPacket.chunkData.sections.first().blockStates,
        )
        assertEquals(1, chunkDataAndUpdateLightPacket.chunkData.blockEntities.size)
        assertEquals(0x34.toByte(), chunkDataAndUpdateLightPacket.chunkData.blockEntities.single().packedXZ)
        assertEquals(NbtInt(7), chunkDataAndUpdateLightPacket.chunkData.blockEntities.single().tag?.get("custom"))
        assertEquals(1, chunkDataAndUpdateLightPacket.lightData.blockYMask.words.countOneBits())
        assertEquals(1, chunkDataAndUpdateLightPacket.lightData.skyYMask.words.countOneBits())

        val minecraftChunkPacketDecoder =
            MinecraftChunkPacketDecoder(protocolRegistryContext, chunkLayout, ChunkMetadata(1, status = "full"))
        val decoded = chunkDataAndUpdateLightPacket.toChunk(minecraftChunkPacketDecoder)
        assertEquals(stone, decoded.block(ChunkBlockPosition(0, -16, 0)))
        assertEquals(water, decoded.block(ChunkBlockPosition(1, -16, 0)))
        assertEquals(air, decoded.block(ChunkBlockPosition(2, -16, 0)))
        assertEquals(plains, decoded.biome(0, -16, 0))
        assertEquals(NbtByteArray(blockLight), decoded.section(-1)?.blockLight)
        assertEquals(NbtByteArray(skyLight), decoded.chunkMetadata.lightOnlySections[1]?.skyLight)
        assertEquals(chunkMetadata.heightmaps, decoded.chunkMetadata.heightmaps)
        assertEquals(blockEntity.type, decoded.blockEntity(blockEntity.blockPosition)?.type)
        assertEquals(blockEntity.persistentData, decoded.blockEntity(blockEntity.blockPosition)?.persistentData)

        val minecraftProtocolFormat = MinecraftProtocolFormat(
            MinecraftProtocolFormat.minecraftProtocolFormatConfiguration.copy(protocolRegistryContext = protocolRegistryContext),
        )
        val originalBytes = minecraftProtocolFormat.encodeToByteArray(chunkDataAndUpdateLightPacket)
        val roundTripBytes = minecraftProtocolFormat.encodeToByteArray(
            decoded.toChunkDataAndUpdateLightPacket(minecraftChunkPacketEncoder),
        )
        assertContentEquals(originalBytes, roundTripBytes)

        chunkDataAndUpdateLightPacket.chunkData.heightmaps.getValue(HeightmapType.WORLD_SURFACE)[0] = 99L
        assertEquals(NbtLongArray(longArrayOf(11L)), chunkMetadata.heightmaps["WORLD_SURFACE"])
        minecraftChunkSnapshot.chunkData.heightmaps.getValue(HeightmapType.WORLD_SURFACE)[0] = 100L
        assertEquals(NbtLongArray(longArrayOf(11L)), chunkMetadata.heightmaps["WORLD_SURFACE"])
    }

    @Test
    fun usesGlobalRegistryIdsWhenAPaletteExceedsTheIndirectLimit() {
        val blockStates = List(257) { id ->
            val block = if (id == 0) Identifier("air") else Identifier("test:block_$id")
            ProtocolBlockState(id, block, emptyMap(), isDefault = true)
        }
        val biomeEntries = List(9) { rawId ->
            val biome = if (rawId == 0) Identifier("plains") else Identifier("test:biome_$rawId")
            ProtocolRegistryEntry(biome, rawId)
        }
        val protocolRegistryContext = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    StaticRegistrySchema.BLOCK_REGISTRY,
                    blockStates.map { protocolBlockState -> ProtocolRegistryEntry(protocolBlockState.block, protocolBlockState.id) },
                ),
                ProtocolRegistry(ProtocolRegistryContext.BIOME_REGISTRY, biomeEntries),
            ),
            blockStates = blockStates,
            chunkSectionCount = 1,
        )
        val minecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
            protocolRegistryContext = protocolRegistryContext,
            isAir = { protocolBlockState -> protocolBlockState.id == 0 },
            hasFluid = { false },
            hasSkyLight = false,
        )
        val chunkLayout = ChunkLayout(minSectionY = 0, sectionCount = 1)
        val chunkSection = ChunkSection(
            sectionY = 0,
            blockStates = PalettedContainer(
                List(com.hiczp.minecraft.world.format.SECTION_BLOCK_COUNT) { index ->
                    blockStates[index % blockStates.size]
                },
            ),
            biomes = PalettedContainer(
                List(com.hiczp.minecraft.world.format.SECTION_BIOME_COUNT) { index ->
                    biomeEntries[index % biomeEntries.size]
                },
            ),
        )
        val chunk = Chunk(
            chunkPosition = ChunkPosition(0, 0),
            chunkMetadata = ChunkMetadata(dataVersion = 1, status = "full"),
            chunkLayout = chunkLayout,
            sections = listOf(chunkSection),
            defaultBlockState = blockStates.first(),
            defaultBiome = biomeEntries.first(),
        )

        val chunkDataAndUpdateLightPacket = minecraftChunkPacketEncoder.encodePacket(chunk)

        assertIs<NetworkPalettedContainer.Direct>(chunkDataAndUpdateLightPacket.chunkData.sections.single().blockStates)
        assertIs<NetworkPalettedContainer.Direct>(chunkDataAndUpdateLightPacket.chunkData.sections.single().biomes)
        val minecraftChunkPacketDecoder =
            MinecraftChunkPacketDecoder(protocolRegistryContext, chunkLayout, ChunkMetadata(1, status = "full"))
        val decoded = minecraftChunkPacketDecoder.decode(chunkDataAndUpdateLightPacket)
        assertEquals(blockStates[256], decoded.section(0)?.blockStates?.get(256))
        assertEquals(biomeEntries[8], decoded.section(0)?.biomes?.get(8))
    }

    private fun testProtocolRegistryContext(): ProtocolRegistryContext {
        val air = Identifier("air")
        val stone = Identifier("stone")
        val water = Identifier("water")
        return ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    StaticRegistrySchema.BLOCK_REGISTRY,
                    listOf(
                        ProtocolRegistryEntry(air, 0),
                        ProtocolRegistryEntry(stone, 1),
                        ProtocolRegistryEntry(water, 2),
                    ),
                ),
                ProtocolRegistry(
                    ProtocolRegistryContext.BIOME_REGISTRY,
                    listOf(ProtocolRegistryEntry(Identifier("plains"), 0)),
                ),
                ProtocolRegistry(
                    MinecraftChunkPacketEncoder.BLOCK_ENTITY_TYPE_REGISTRY,
                    listOf(ProtocolRegistryEntry(Identifier("chest"), 0)),
                ),
            ),
            blockStates = listOf(
                ProtocolBlockState(0, air, emptyMap(), isDefault = true),
                ProtocolBlockState(1, stone, emptyMap(), isDefault = true),
                ProtocolBlockState(2, water, emptyMap(), isDefault = true),
            ),
            chunkSectionCount = 2,
        )
    }

    private fun block(name: String) = com.hiczp.minecraft.world.format.BlockStateDescriptor("minecraft:$name")

    private fun LongArray.countOneBits(): Int = sumOf { value -> value.countOneBits() }
}
