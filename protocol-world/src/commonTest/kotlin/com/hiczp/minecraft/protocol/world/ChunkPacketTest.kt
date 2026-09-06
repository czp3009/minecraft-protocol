package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormatConfiguration
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.HeightmapType
import kotlin.test.*
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as PacketPalettedContainer

class ChunkPacketTest {
    private val air = BlockState(BlockId("minecraft:air"))
    private val stone = BlockState(BlockId("example:block"), StateProperties(mapOf("mode" to "active")))
    private val plains = BiomeId("minecraft:plains")
    private val context =
        ChunkContext(DimensionId.Overworld, DimensionTypeLayout(-16, 32, 32, true, false), air, plains)
    private val registry = registry(listOf(air, stone), listOf(plains))
    private val position = ChunkPosition(-3, 2)
    private val noUpdateTags = ChunkPacketWriteMappings({ null })
    private val missingCounts = ChunkPacketRequiredDataProvider(
        { _, _, _ -> 0 },
        { _, _, _ -> error("Unexpected unknown height") },
        { _, _, _ -> null })

    @Test
    fun blockEntityUpdateTagsReuseCanonicalComponentAndScopedPropertyMappings() {
        val componentId = ComponentId("minecraft:counter")
        val blockEntityTypeId = BlockEntityTypeId("example:machine")
        val reader = NbtPropertyReader { nbtTag, _ ->
            PropertyValue(PropertyTypes.Long, assertIs<NbtInt>(nbtTag).value.toLong())
        }
        val mappings = ChunkPacketReadMappings.dynamic(
            NbtPropertyReadMappings(
                mapOf(
                    NbtPropertyPath(NbtPropertyScope("component"), componentId.value) to reader,
                    NbtPropertyPath(NbtPropertyScope("block_entity", blockEntityTypeId.value), "visible") to reader,
                )
            )
        )
        val tag = NbtCompound(
            mapOf(
                "id" to NbtString("example:ignored"), "x" to NbtInt(999),
                "components" to NbtCompound(mapOf("counter" to NbtInt(7))), "visible" to NbtInt(9),
            )
        )
        val contents = mappings.blockEntity(blockEntityTypeId, tag)
        assertEquals(7L, contents.components.entries.getValue(componentId).get(PropertyTypes.Long))
        assertEquals(9L, contents.properties.require(PropertyKey("visible", PropertyTypes.Long)))
        assertEquals(setOf("visible"), contents.properties.entries.keys)
        val empty = mappings.blockEntity(blockEntityTypeId, null)
        assertTrue(empty.components.entries.isEmpty())
        assertTrue(empty.properties.entries.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            mappings.blockEntity(blockEntityTypeId, NbtCompound(mapOf("components" to NbtInt(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            mappings.blockEntity(
                blockEntityTypeId, NbtCompound(
                    mapOf(
                        "components" to NbtCompound(mapOf("counter" to NbtInt(1), componentId.value to NbtInt(2))),
                    )
                )
            )
        }
    }

    @Test
    fun existingDataWinsWhileMissingValuesAndPrivateFieldsRemainExplicit() {
        val chunk = Chunk(position, context)
        val local = ChunkBlockPosition(15, -15, 2)
        chunk.setBlockState(local, stone)
        val terrain = assertNotNull(chunk.sections[-1]?.terrain)
        terrain.statistics.nonEmptyBlockCount = 17
        terrain.statistics.fluidCount = 3
        chunk.sections.getValue(-1).lighting.blockLight = LightLayer(0)
        chunk.sections[-2] = ChunkSection(null, SectionLighting(skyLight = LightLayer(7)), DataProperties())
        val blockEntity = BlockEntity(BlockEntityTypeId("example:machine"), DataComponentMap(), DataProperties())
        blockEntity.properties[PropertyKey("visible", PropertyTypes.String)] = "hello"
        blockEntity.properties[PropertyKey("secret", PropertyTypes.String)] = "server-only"
        chunk.blockEntities[position.block(local)] = blockEntity
        val countRequests = mutableListOf<Pair<Int, SectionStatistic>>()
        val encoder = ChunkPacketEncoder(
            ChunkPacketEncoderContext(
                context.dimensionTypeLayout.chunkLayout, context.dimensionTypeLayout.hasSkyLight, air, plains, registry,
                ChunkPacketWriteMappings({ entity ->
                    NbtCompound(
                        mapOf(
                            "visible" to NbtString(
                                entity.properties.require(
                                    PropertyKey(
                                        "visible",
                                        PropertyTypes.String
                                    )
                                )
                            )
                        )
                    )
                }),
                missingCounts.copy(sectionStatistic = { _, sectionY, statistic ->
                    countRequests.add(sectionY to statistic)
                    0
                }),
            )
        )
        // Encoder configuration remains explicit when a caller replaces the domain context reference.
        chunk.chunkContext = context.copy(dimensionTypeLayout = DimensionTypeLayout(0, 16, 16, false, false))
        val packet = encoder.encode(chunk)
        assertEquals(listOf(0 to SectionStatistic.NON_EMPTY_BLOCKS, 0 to SectionStatistic.FLUIDS), countRequests)
        assertTrue(packet.lightData.emptyBlockYMask[1])
        assertFalse(packet.lightData.emptyBlockYMask[2])
        val supplied = ChunkPacketMissingData("example:client", 91, false)
        var calls = 0
        val decoder = ChunkPacketDecoder(
            ChunkPacketDecoderContext(
                context, registry, ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
                { authoritativePosition ->
                    calls++
                    assertEquals(position, authoritativePosition)
                    supplied
                },
            )
        )
        val decoded = decoder.decode(packet)
        assertEquals(1, calls)
        assertSame(context, decoded.chunkContext)
        assertSame(supplied.properties, decoded.properties)
        assertSame(supplied.blockTicks, decoded.blockTicks)
        assertSame(supplied.lighting, decoded.lighting)
        assertEquals("example:client", decoded.status)
        assertEquals(91, decoded.inhabitedTime)
        assertEquals(stone, decoded.getBlockState(local))
        assertEquals(17, decoded.sections[-1]?.terrain?.statistics?.nonEmptyBlockCount)
        assertEquals(3, decoded.sections[-1]?.terrain?.statistics?.fluidCount)
        assertNull(decoded.sections[-1]?.terrain?.statistics?.tickingBlockCount)
        assertEquals(0, decoded.sections[-1]?.lighting?.blockLight?.get(0))
        assertNull(decoded.sections[0]?.lighting?.blockLight)
        assertEquals(7, decoded.sections[-2]?.lighting?.skyLight?.get(4095))
        assertNull(decoded.sections[-2]?.terrain)
        val received = decoded.blockEntities.getValue(position.block(local))
        assertEquals("hello", received.properties.require(PropertyKey("visible", PropertyTypes.String)))
        assertNull(received.properties["secret"])

        val nbtEncoder = ChunkNbtEncoder(
            ChunkNbtEncoderContext(
                context.dimensionTypeLayout.chunkLayout,
                NbtFormat,
                NbtPropertyWriteMappings(),
                100,
                ChunkNbtMetadata(12345, 400)
            )
        )
        val nbtDecoder = ChunkNbtDecoder(ChunkNbtDecoderContext(context, NbtFormat, NbtPropertyReadMappings(), 100))
        val stored = decoded.toCompressedChunk(nbtEncoder, Compression.NONE).toChunk(nbtDecoder)
        assertEquals("example:client", stored.chunk.status)
        assertEquals(400, stored.chunkNbtMetadata.lastUpdateTime)
        assertEquals(
            "hello",
            stored.chunk.blockEntities.getValue(position.block(local)).properties.require(
                PropertyKey(
                    "visible",
                    PropertyTypes.String
                )
            )
        )
        decoded.blockEntities.clear()
        received.properties[PropertyKey("visible", PropertyTypes.String)] = "old reference"
        assertTrue(encoder.encode(decoded).chunkData.blockEntitiesData.isEmpty())
    }

    @Test
    fun requiredProvidersRunOnlyForUnknownFieldsAndNeverWriteTheirResultsBack() {
        val chunk = Chunk(position, context)
        chunk.setBlockState(ChunkBlockPosition(0, -16, 0), air)
        val heightmap = Heightmap(ColumnData<Int?>(null))
        heightmap.firstAvailable[0] = -16
        chunk.heightmaps.maps[HeightmapType.WorldSurface] = heightmap
        var heights = 0
        var counts = 0
        val encoder = ChunkPacketEncoder(
            ChunkPacketEncoderContext(
                context.dimensionTypeLayout.chunkLayout,
                context.dimensionTypeLayout.hasSkyLight,
                air,
                plains,
                registry,
                noUpdateTags,
                missingCounts.copy(
                    sectionStatistic = { _, _, _ -> counts++; 2 },
                    heightmapValue = { _, type, index ->
                        assertEquals(HeightmapType.WorldSurface, type)
                        assertTrue(index > 0)
                        heights++
                        -8
                    },
                ),
            )
        )
        val decoded = decoder(registry).decode(encoder.encode(chunk))
        assertEquals(4, counts)
        assertEquals(255, heights)
        assertNull(chunk.sections[-1]?.terrain?.statistics?.nonEmptyBlockCount)
        assertNull(heightmap.firstAvailable[1])
        assertEquals(-16, decoded.heightmaps.maps.getValue(HeightmapType.WorldSurface).firstAvailable[0])
        assertEquals(-8, decoded.heightmaps.maps.getValue(HeightmapType.WorldSurface).firstAvailable[255])
        assertFailsWith<IllegalStateException> {
            ChunkPacketEncoder(encoder.chunkPacketEncoderContext.copy(chunkPacketRequiredDataProvider = ChunkPacketRequiredDataProvider.RequirePresent)).encode(
                chunk
            )
        }
    }

    @Test
    fun directPalettesPreserveCanonicalStatesAndBiomesWithoutDependingOnPaletteIds() {
        val states =
            List(300) { index -> BlockState(BlockId("example:state"), StateProperties(mapOf("variant" to "$index"))) }
        val biomes = List(20) { BiomeId("example:biome_$it") }
        val registry = registry(listOf(air) + states, listOf(plains) + biomes)
        val chunk = Chunk(position, context)
        val terrain = SectionTerrain(air, plains)
        states.forEachIndexed { index, state -> terrain.blockStates[index] = state }
        biomes.forEachIndexed { index, biome -> terrain.biomes[index] = biome }
        chunk.sections[-1] = ChunkSection(terrain, SectionLighting(), DataProperties())
        val packet = ChunkPacketEncoder(
            ChunkPacketEncoderContext(
                context.dimensionTypeLayout.chunkLayout, context.dimensionTypeLayout.hasSkyLight, air, plains,
                registry, noUpdateTags, missingCounts,
            )
        ).encode(chunk)
        val sections =
            MinecraftChunkSectionPayloadFormat(MinecraftChunkSectionPayloadFormatConfiguration(registry, 2)).decode(
                packet.chunkData.buffer
            )
        assertIs<PacketPalettedContainer.Direct>(sections[0].states)
        assertIs<PacketPalettedContainer.Direct>(sections[0].biomes)
        val decoded = decoder(registry).decode(packet)
        val actual = assertNotNull(decoded.sections[-1]?.terrain)
        states.forEachIndexed { index, state -> assertEquals(state, actual.blockStates[index]) }
        biomes.forEachIndexed { index, biome -> assertEquals(biome, actual.biomes[index]) }
        assertEquals(300, terrain.blockStates.paletteSnapshot().values.size - 1)
    }

    private fun decoder(packetCodecContext: PacketCodecContext) = ChunkPacketDecoder(
        ChunkPacketDecoderContext(
            context, packetCodecContext, ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
            { ChunkPacketMissingData("minecraft:full", 0, true) },
        )
    )

    private fun registry(states: List<BlockState>, biomes: List<BiomeId>): PacketCodecContext = PacketCodecContext(
        listOf(
            RegistryIdMap(
                PacketCodecContext.BIOME_REGISTRY,
                biomes.mapIndexed { id, biome -> RegistryIdMapping(Identifier(biome.value), id) }),
            RegistryIdMap(Identifier("block_entity_type"), listOf(RegistryIdMapping(Identifier("example:machine"), 7))),
        ),
        states.mapIndexed { id, state ->
            BlockStateIdMapping(
                id,
                Identifier(state.blockId.value),
                state.properties.toMap(),
                id == 0
            )
        },
    )
}
