package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.nbt.serialization.SnbtFormat
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.*

class ChunkNbtTest {
    private val context = testChunkContext()
    private val nbtFormat = NbtFormat(NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED))
    private val readMappings = NbtPropertyReadMappings()
    private val writeMappings = NbtPropertyWriteMappings()
    private val metadata = ChunkNbtMetadata(Int.MIN_VALUE, 987)
    private val decoder = ChunkNbtDecoder(ChunkNbtDecoderContext(context, nbtFormat, readMappings, 1000))
    private val encoder = ChunkNbtEncoder(
        ChunkNbtEncoderContext(
            context.dimensionTypeLayout.chunkLayout,
            nbtFormat,
            writeMappings,
            1000,
            metadata
        )
    )

    @Test
    fun binaryTreeAndCompressedPathsShareTheDomainConversion() {
        val chunk = Chunk(ChunkPosition(-25, 43), context)
        val stone = BlockState(BlockId.parse("stone"))
        val water = BlockState(BlockId.parse("water"), StateProperties(mapOf("level" to "0")))
        chunk.setBlockState(ChunkBlockPosition(0, -64, 0), stone)
        chunk.setBlockState(ChunkBlockPosition(15, -49, 15), water)
        chunk.setBiome(ChunkBlockPosition(12, -52, 12), BiomeId.parse("desert"))
        val document = encoder.encodeDocument(chunk)
        val section = document.root.requiredTag<NbtList>("sections")[0].compound()
        assertEquals(256, section.requiredTag<NbtCompound>("block_states").requiredTag<NbtLongArray>("data").size)
        assertEquals(1, section.requiredTag<NbtCompound>("biomes").requiredTag<NbtLongArray>("data").size)
        val binary = Buffer()
        encoder.encode(chunk, binary)
        assertEquals(document, nbtFormat.decodeDocumentFromByteArray(binary.readByteArray()))
        for (compression in listOf(Compression.NONE, Compression.GZIP, Compression.ZLIB, Compression.LZ4)) {
            val result = chunk.toCompressedChunk(encoder, compression).toChunk(decoder)
            assertEquals(metadata, result.chunkNbtMetadata)
            assertSame(context, result.chunk.chunkContext)
            assertEquals(stone, result.chunk.getBlockState(ChunkBlockPosition(0, -64, 0)))
            assertEquals(water, result.chunk.getBlockState(ChunkBlockPosition(15, -49, 15)))
            assertEquals(BiomeId.parse("desert"), result.chunk.getBiome(ChunkBlockPosition(12, -52, 12)))
            assertEquals(document, encoder.encodeDocument(result.chunk))
        }
    }

    @Test
    fun dynamicScopesPreserveNbtWidthsListsArraysAndNestedReferences() {
        val nested = NbtCompound(
            mapOf(
                "byte" to NbtByte(1), "short" to NbtShort(2), "int" to NbtInt(3),
                "long" to NbtLong(4), "float" to NbtFloat(5f), "double" to NbtDouble(6.0),
                "bytes" to NbtByteArray(byteArrayOf(1, 2)), "ints" to NbtIntArray(intArrayOf(3, 4)),
                "longs" to NbtLongArray(longArrayOf(5, 6)), "list" to NbtList(listOf(NbtInt(7), NbtString("mixed")))
            )
        )
        val chunk = Chunk(ChunkPosition(0, 0), context)
        chunk.properties["fabric:attachments"] = readMappings.readValue(NbtCompound(mapOf("example:counter" to nested)))
        chunk.sections[-5] = ChunkSection(null, SectionLighting(skyLight = LightLayer(0)), DataProperties())
        chunk.sections.getValue(-5).properties["example:section"] = readMappings.readValue(nested)
        val position = BlockPosition(1, 2, 3)
        chunk.blockEntities[position] = BlockEntity(
            BlockEntityTypeId.parse("example:machine"),
            DataComponentMap(linkedMapOf(ComponentId.parse("example:component") to readMappings.readValue(nested))),
            DataProperties(linkedMapOf("fabric:attachments" to readMappings.readValue(nested)))
        )
        val roundTrip = chunk.toCompressedChunk(encoder).toChunk(decoder).chunk
        assertEquals(encoder.encodeDocument(chunk), encoder.encodeDocument(roundTrip))
        val attachments = roundTrip.blockEntities.getValue(position).properties.require(
            PropertyKey(
                "fabric:attachments",
                PropertyTypes.Properties
            )
        )
        attachments[PropertyKey("int", PropertyTypes.Int)] = 99
        val saved = encoder.encodeDocument(roundTrip).root.requiredTag<NbtList>("block_entities")[0].compound()
        assertEquals(NbtInt(99), saved.requiredTag<NbtCompound>("fabric:attachments")["int"])
        roundTrip.blockEntities.clear()
        attachments[PropertyKey("int", PropertyTypes.Int)] = 100
        assertEquals(0, encoder.encodeDocument(roundTrip).root.requiredTag<NbtList>("block_entities").size)
    }

    @Test
    fun statusesAreExposedWithoutAProtoChunkModelOrLoadingPolicy() {
        val document = NbtDocument(
            NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(42), "Status" to NbtString("minecraft:noise"),
                    "carving_mask" to NbtLongArray(longArrayOf(123)), "below_zero_retrogen" to NbtCompound(emptyMap())
                )
            )
        )
        val result = decoder.decodeDocument(document)
        assertFalse(result.chunk.isFullyGenerated)
        assertTrue(result.chunk.sections.isEmpty())
        assertEquals(42, result.chunkNbtMetadata.dataVersion)
        assertFalse("carving_mask" in result.chunk.properties.entries)
        val output = encoder.encodeDocument(result.chunk)
        assertEquals(NbtString("minecraft:noise"), output.root["Status"])
        assertNull(output.root["carving_mask"])
    }

    @Test
    fun generationBlockEntityPlaceholdersDoNotPreventReadingTerrainAndStatus() {
        // WorldGenRegion.setBlock writes DUMMY markers until a Block Entity is materialized.
        val root = SnbtFormat.decodeDocumentFromString(
            """
            {
                DataVersion: 42,
                block_entities: [
                    {id: "DUMMY", x: -625, y: -52, z: 80},
                    {id: "example:machine", x: -628, y: -52, z: 81, keepPacked: 1b, energy: 7L},
                    {id: "DUMMY", x: -627, y: -53, z: 82}
                ],
                xPos: -40, zPos: 5,
                sections: [{Y: -4b, block_states: {palette: [{Name: "minecraft:stone"}]}}]
            }
            """.trimIndent()
        ).root
        val blockPosition = BlockPosition(-628, -52, 81)
        for (status in listOf("minecraft:initialize_light", "minecraft:full")) {
            // Status follows block_entities; decoding cannot depend on compound field order.
            val nbtDocument = NbtDocument(NbtCompound(root.value + ("Status" to NbtString(status))))
            val buffer = Buffer()
            nbtFormat.encodeDocumentToSink(nbtDocument, buffer)
            for (result in listOf(decoder.decode(buffer), decoder.decodeDocument(nbtDocument))) {
                val chunk = result.chunk
                assertEquals(ChunkPosition(-40, 5), chunk.chunkPosition)
                assertEquals(status, chunk.status)
                assertEquals(status == "minecraft:full", chunk.isFullyGenerated)
                assertEquals(BlockState(BlockId.parse("stone")), chunk.getBlockState(blockPosition))
                assertEquals(setOf(blockPosition), chunk.blockEntities.keys)
                val blockEntity = chunk.blockEntities.getValue(blockPosition)
                assertEquals(BlockEntityTypeId("example:machine"), blockEntity.blockEntityTypeId)
                assertEquals(7L, blockEntity.properties.require(PropertyKey("energy", PropertyTypes.Long)))
                val saved = encoder.encodeDocument(chunk).root.requiredTag<NbtList>("block_entities")
                assertEquals(listOf(root.requiredTag<NbtList>("block_entities")[1]), saved.value)
            }
        }
    }

    @Test
    fun otherInvalidBlockEntityIdsStillFailInsteadOfBeingSkippedOrNormalized() {
        for (id in listOf("minecraft:DUMMY", "Dummy", "invalid id")) {
            val nbtDocument = NbtDocument(
                NbtCompound(
                    mapOf(
                        "DataVersion" to NbtInt(42), "Status" to NbtString("minecraft:full"),
                        "block_entities" to NbtList(
                            listOf(
                                NbtCompound(
                                    mapOf("id" to NbtString(id), "x" to NbtInt(0), "y" to NbtInt(0), "z" to NbtInt(0))
                                )
                            )
                        )
                    )
                )
            )
            val buffer = Buffer()
            nbtFormat.encodeDocumentToSink(nbtDocument, buffer)
            assertFailsWith<ChunkNbtFormatException> { decoder.decode(buffer) }
            assertFailsWith<ChunkNbtFormatException> { decoder.decodeDocument(nbtDocument) }
        }
    }

    @Test
    fun encoderUsesItsExplicitLayoutAndMetadataEvenAfterRootContextReplacement() {
        val chunk = Chunk(ChunkPosition(0, 0), context)
        chunk.chunkContext = context.copy(dimensionTypeLayout = DimensionTypeLayout(0, 16, 16, false, true))
        val output = encoder.encodeDocument(chunk)
        assertEquals(NbtInt(-4), output.root["yPos"])
        assertEquals(NbtLong(987), output.root["LastUpdate"])
        assertEquals(NbtInt(Int.MIN_VALUE), output.root["DataVersion"])
        assertSame(context, decoder.decodeDocument(output).chunk.chunkContext)
    }

    @Test
    fun ticksUseExplicitClockAndOfficialListOrderWithoutMutatingInput() {
        val chunk = Chunk(ChunkPosition(0, 0), context)
        val late = ScheduledTick(BlockId.parse("stone"), BlockPosition(1, 2, 3), 995, TickPriority.HIGH, 20)
        val early = ScheduledTick(
            BlockId.parse("stone"), BlockPosition(4, 5, 6), 1000L + Int.MAX_VALUE + 1,
            TickPriority.VERY_LOW, 10
        )
        chunk.blockTicks.addAll(listOf(late, early))
        val output = encoder.encodeDocument(chunk)
        val ticks = output.root.requiredTag<NbtList>("block_ticks")
        assertEquals(Int.MIN_VALUE, ticks[0].compound().int("t"))
        assertEquals(-5, ticks[1].compound().int("t"))
        assertSame(late, chunk.blockTicks[0])
        assertEquals(20L, late.subTickOrder)
        val result = decoder.decodeDocument(output).chunk
        assertEquals(listOf(-2L, -1L), result.blockTicks.map { it.subTickOrder })
        assertEquals(1000L + Int.MIN_VALUE, result.blockTicks[0].triggerTick)
        assertEquals(995L, result.blockTicks[1].triggerTick)
        assertEquals(TickPriority.VERY_LOW, result.blockTicks[0].priority)
    }

    @Test
    fun auxiliaryStateKeepsAbsoluteHeightsLightBoundariesAndPositionOrders() {
        val chunk = Chunk(ChunkPosition(0, 0), context)
        val firstAvailable = ColumnData<Int?>(-64)
        firstAvailable[2, 3] = 100
        chunk.heightmaps.maps[HeightmapType.MotionBlocking] = Heightmap(firstAvailable)
        val layer = LightLayer(0)
        layer[LocalBlockPosition(1, 2, 3)] = 15
        chunk.sections[-5] = ChunkSection(null, SectionLighting(blockLight = layer), DataProperties())
        chunk.postProcessing.positions[-4] = mutableListOf(LocalBlockPosition(1, 2, 3), LocalBlockPosition(1, 2, 3))
        chunk.upgradeData = UpgradeData(
            linkedSetOf(Direction8.NORTH_WEST), linkedMapOf(-4 to mutableListOf(LocalBlockPosition(1, 2, 3))),
            mutableListOf(), mutableListOf()
        )
        chunk.blendingData = BlendingData(-4, 0, MutableList(16) { if (it == 0) 3.0 else null }, null, null)
        val output = encoder.encodeDocument(chunk)
        assertEquals(NbtShort(0x321), output.root.requiredTag<NbtList>("PostProcessing")[0].list()[0])
        val result = decoder.decodeDocument(output).chunk
        assertEquals(100, result.heightmaps.maps.getValue(HeightmapType.MotionBlocking).firstAvailable[2, 3])
        assertEquals(-64, result.heightmaps.maps.getValue(HeightmapType.MotionBlocking).firstAvailable[0, 0])
        assertNull(result.sections.getValue(-5).terrain)
        assertEquals(15, result.sections.getValue(-5).lighting.blockLight?.get(LocalBlockPosition(1, 2, 3)))
        assertEquals(chunk.postProcessing, result.postProcessing)
        assertEquals(chunk.upgradeData, result.upgradeData)
        assertEquals(chunk.blendingData, result.blendingData)
        firstAvailable[0] = null
        assertFailsWith<ChunkNbtFormatException> { encoder.encodeDocument(chunk) }
    }

    @Test
    fun conflictsUnknownKotlinValuesCyclesAndMalformedPalettesAreReported() {
        val chunk = Chunk(ChunkPosition(0, 0), context)
        chunk.properties["xPos"] = PropertyValue(PropertyTypes.Int, 10)
        assertFailsWith<ChunkNbtFormatException> { encoder.encodeDocument(chunk) }
        chunk.properties.entries.clear()
        chunk.properties["custom"] = PropertyValue(PropertyType<Any>("custom"), Any())
        assertFailsWith<ChunkNbtFormatException> { encoder.encodeDocument(chunk) }
        chunk.properties["custom"] = PropertyValue(PropertyTypes.Properties, chunk.properties)
        assertFailsWith<ChunkNbtFormatException> { encoder.encodeDocument(chunk) }
        chunk.properties.entries.clear()
        chunk.setBlockState(ChunkBlockPosition(0, 0, 0), BlockState(BlockId.parse("stone")))
        val root = encoder.encodeDocument(chunk).root.value.toMutableMap()
        val section = root.getValue("sections").list()[0].compound().value.toMutableMap()
        val states = section.getValue("block_states").compound().value.toMutableMap()
        states["data"] = NbtLongArray(longArrayOf())
        section["block_states"] = NbtCompound(states)
        root["sections"] = NbtList(listOf(NbtCompound(section)))
        assertFailsWith<ChunkNbtFormatException> { decoder.decodeDocument(NbtDocument(NbtCompound(root))) }
    }
}
