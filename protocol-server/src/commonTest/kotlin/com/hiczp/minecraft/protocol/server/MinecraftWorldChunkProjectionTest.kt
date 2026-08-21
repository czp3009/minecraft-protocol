package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.protocol.client.MinecraftChunkPacketDecoder
import com.hiczp.minecraft.protocol.client.toChunk
import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.world.format.PalettedContainer
import kotlin.test.*
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as NetworkPalettedContainer

class MinecraftWorldChunkProjectionTest {
    @Test
    fun convertsDimensionBlockBoundsToAChunkLayout() {
        val minecraftDimensionLayout = MinecraftDimensionLayout(
            id = Identifier("overworld"),
            registryId = 0,
            minY = -64,
            height = 384,
            hasSkyLight = true,
        )

        val chunkLayout = minecraftDimensionLayout.toChunkLayout()

        assertEquals(-4, chunkLayout.minSectionY)
        assertEquals(24, chunkLayout.sectionCount)
        assertEquals(-64..319, chunkLayout.blockYRange)
    }

    @Test
    fun projectsAStrongChunkToTheWireAndBackWithoutDensePaletteCopies() {
        val registries = testRegistries()
        val encoder = MinecraftChunkPacketEncoder(
            registries = registries,
            isAir = { blockState -> blockState.block == Identifier("air") },
            hasFluid = { blockState -> blockState.block == Identifier("water") },
            hasSkyLight = true,
        )
        val air = encoder.chunkDataRegistries.blockStates.defaultValue
        val stone = assertNotNull(encoder.chunkDataRegistries.blockStates.resolve(block("stone")))
        val water = assertNotNull(encoder.chunkDataRegistries.blockStates.resolve(block("water")))
        val plains = encoder.chunkDataRegistries.biomes.defaultValue
        val blockStates = PalettedContainer(com.hiczp.minecraft.world.format.SECTION_BLOCK_COUNT, air).apply {
            this[LocalBlockPosition(0, 0, 0).index] = stone
            this[LocalBlockPosition(1, 0, 0).index] = water
        }
        val blockLight = ByteArray(com.hiczp.minecraft.world.format.SECTION_LIGHT_BYTE_COUNT).apply { this[0] = 4 }
        val skyLight = ByteArray(com.hiczp.minecraft.world.format.SECTION_LIGHT_BYTE_COUNT).apply { this[1] = -1 }
        val chunkPosition = ChunkPosition(-1, 2)
        val blockEntityPosition = chunkPosition.block(ChunkBlockPosition(3, -1, 4))
        val metadata = ChunkMetadata(
            dataVersion = 1,
            status = "full",
            heightmaps = NbtCompound(mapOf("WORLD_SURFACE" to NbtLongArray(longArrayOf(11L)))),
            blockEntities = NbtList(
                listOf(
                    NbtCompound(
                        mapOf(
                            "id" to NbtString("minecraft:chest"),
                            "x" to NbtInt(blockEntityPosition.x),
                            "y" to NbtInt(blockEntityPosition.y),
                            "z" to NbtInt(blockEntityPosition.z),
                            "custom" to NbtInt(7),
                        ),
                    ),
                ),
            ),
            lightOnlySections = mapOf(1 to SectionLighting(skyLight = NbtByteArray(skyLight))),
        )
        val chunkLayout = ChunkLayout(minSectionY = -1, sectionCount = 2)
        val chunk = Chunk(
            metadata = metadata,
            layout = chunkLayout,
            sections = listOf(
                ChunkSection(
                    sectionY = -1,
                    blockStates = blockStates,
                    biomes = PalettedContainer(com.hiczp.minecraft.world.format.SECTION_BIOME_COUNT, plains),
                    blockLight = NbtByteArray(blockLight),
                ),
            ),
            defaultBlockState = air,
            defaultBiome = plains,
        )

        val packet = chunk.toChunkDataAndUpdateLightPacket(chunkPosition, encoder)
        val snapshot = chunk.toMinecraftChunkSnapshot(chunkPosition, encoder)

        assertEquals(chunkPosition.x, snapshot.chunkX)
        assertEquals(chunkPosition.z, snapshot.chunkZ)
        assertEquals(2, packet.chunkData.sections.first().nonAirBlockCount)
        assertEquals(1, packet.chunkData.sections.first().fluidCount)
        assertIs<NetworkPalettedContainer.Indirect>(packet.chunkData.sections.first().blockStates)
        assertEquals(1, packet.chunkData.blockEntities.size)
        assertEquals(0x34.toByte(), packet.chunkData.blockEntities.single().packedXZ)
        assertEquals(NbtInt(7), packet.chunkData.blockEntities.single().tag?.get("custom"))
        assertEquals(1, packet.lightData.blockYMask.words.countOneBits())
        assertEquals(1, packet.lightData.skyYMask.words.countOneBits())

        val decoder = MinecraftChunkPacketDecoder(registries, chunkLayout, ChunkMetadata(1, status = "full"))
        val decoded = packet.toChunk(decoder)
        assertEquals(stone, decoded.block(ChunkBlockPosition(0, -16, 0)))
        assertEquals(water, decoded.block(ChunkBlockPosition(1, -16, 0)))
        assertEquals(air, decoded.block(ChunkBlockPosition(2, -16, 0)))
        assertEquals(plains, decoded.biome(0, -16, 0))
        assertEquals(NbtByteArray(blockLight), decoded.section(-1)?.blockLight)
        assertEquals(NbtByteArray(skyLight), decoded.metadata.lightOnlySections[1]?.skyLight)
        assertEquals(metadata.heightmaps, decoded.metadata.heightmaps)
        assertEquals(metadata.blockEntities, decoded.metadata.blockEntities)

        val format = MinecraftProtocolFormat(
            MinecraftProtocolFormat.configuration.copy(registries = registries),
        )
        val originalBytes = format.encodeToByteArray(ChunkDataAndUpdateLightPacket.serializer(), packet)
        val roundTripBytes = format.encodeToByteArray(
            ChunkDataAndUpdateLightPacket.serializer(),
            decoded.toChunkDataAndUpdateLightPacket(chunkPosition, encoder),
        )
        assertContentEquals(originalBytes, roundTripBytes)
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
        val registries = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    StaticRegistrySchema.BLOCK_REGISTRY,
                    blockStates.map { blockState -> ProtocolRegistryEntry(blockState.block, blockState.id) },
                ),
                ProtocolRegistry(ProtocolRegistryContext.BIOME_REGISTRY, biomeEntries),
            ),
            blockStates = blockStates,
            chunkSectionCount = 1,
        )
        val encoder = MinecraftChunkPacketEncoder(
            registries = registries,
            isAir = { blockState -> blockState.id == 0 },
            hasFluid = { false },
            hasSkyLight = false,
        )
        val chunkLayout = ChunkLayout(minSectionY = 0, sectionCount = 1)
        val section = ChunkSection(
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
            metadata = ChunkMetadata(dataVersion = 1, status = "full"),
            layout = chunkLayout,
            sections = listOf(section),
            defaultBlockState = blockStates.first(),
            defaultBiome = biomeEntries.first(),
        )

        val packet = encoder.encodePacket(chunk, ChunkPosition(0, 0))

        assertIs<NetworkPalettedContainer.Direct>(packet.chunkData.sections.single().blockStates)
        assertIs<NetworkPalettedContainer.Direct>(packet.chunkData.sections.single().biomes)
        val decoder = MinecraftChunkPacketDecoder(registries, chunkLayout, ChunkMetadata(1, status = "full"))
        val decoded = decoder.decode(packet)
        assertEquals(blockStates[256], decoded.section(0)?.blockStates?.get(256))
        assertEquals(biomeEntries[8], decoded.section(0)?.biomes?.get(8))
    }

    private fun testRegistries(): ProtocolRegistryContext {
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
