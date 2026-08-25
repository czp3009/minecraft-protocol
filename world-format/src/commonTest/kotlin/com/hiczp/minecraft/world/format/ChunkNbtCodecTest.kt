package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.test.*

class ChunkNbtCodecTest {
    @Test
    fun blockSectionAndLocalCoordinatesRoundTripAcrossNegativeBoundaries() {
        val samples = listOf(
            BlockPosition(-17, -17, -17),
            BlockPosition(-16, -16, -16),
            BlockPosition(-1, -1, -1),
            BlockPosition(0, 0, 0),
            BlockPosition(15, 15, 15),
            BlockPosition(16, 16, 16),
        )

        samples.forEach { block ->
            assertEquals(block, block.section.block(block.localInSection))
            assertEquals(block, block.chunk.block(block.localInChunk))
            assertEquals(block.localInChunk, block.chunk.local(block))
            assertEquals(block.localInSection, block.section.local(block))
            assertEquals(block.chunk, block.section.chunk)
            assertEquals(block.localInSection, LocalBlockPosition.fromIndex(block.localInSection.index))
        }

        assertFailsWith<IllegalArgumentException> {
            ChunkPosition(0, 0).local(BlockPosition(16, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            SectionPosition(0, 0, 0).local(BlockPosition(0, 16, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            RegionPosition(Int.MAX_VALUE, 0).chunk(LocalChunkPosition(0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkPosition(Int.MAX_VALUE, 0).block(ChunkBlockPosition(0, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            SectionPosition(0, Int.MIN_VALUE, 0).block(LocalBlockPosition(0, 0, 0))
        }
    }

    @Test
    fun absoluteBlockAndBiomeOverloadsValidateThenDelegateToLocalCoordinates() {
        val chunkPosition = ChunkPosition(-2, 3)
        val blockPosition = chunkPosition.block(ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0))
        val sectionPosition = blockPosition.section
        val chunk = emptyChunk(chunkPosition)
        val section = chunk.getOrCreateSection(sectionPosition)

        chunk.setBlock(blockPosition, STONE)
        chunk.setBiome(blockPosition, "minecraft:desert")

        assertEquals(STONE, chunk.block(blockPosition))
        assertEquals("minecraft:desert", chunk.biome(blockPosition))
        assertEquals(STONE, section.block(sectionPosition, blockPosition))
        assertEquals("minecraft:desert", section.biome(sectionPosition, blockPosition))
        assertEquals(section, chunk.section(sectionPosition))
        assertEquals(section, chunk.section(blockPosition))
        assertTrue(chunk.hasSection(blockPosition.localInChunk))
        assertTrue(chunk.hasSection(sectionPosition))

        section.setBlock(sectionPosition, blockPosition, WATER)
        section.setBiome(sectionPosition, blockPosition, "minecraft:forest")
        assertEquals(WATER, chunk.block(blockPosition.localInChunk))
        assertEquals("minecraft:forest", chunk.biome(blockPosition.localInChunk))

        assertFailsWith<IllegalArgumentException> {
            chunk.block(ChunkPosition(0, 0).block(blockPosition.localInChunk))
        }
        assertFailsWith<IllegalArgumentException> {
            section.block(SectionPosition(0, sectionPosition.y, 0), blockPosition)
        }
        val otherSectionPosition = SectionPosition(sectionPosition.x, sectionPosition.y + 1, sectionPosition.z)
        val otherBlockPosition = otherSectionPosition.block(LocalBlockPosition(15, 0, 0))
        assertFailsWith<IllegalArgumentException> {
            section.block(otherSectionPosition, otherBlockPosition)
        }
    }

    @Test
    fun compressedChunkOwnsBytesAndWritesWithoutExposingThem() {
        val original = byteArrayOf(1, 2, 3)
        val chunk = CompressedChunk(Compression.ZLIB, original)
        original[0] = 9

        val returned = chunk.toByteArray()
        returned[1] = 9
        val sink = Buffer()
        chunk.writeTo(sink)

        assertEquals(3L, chunk.compressedByteCount)
        assertContentEquals(byteArrayOf(1, 2, 3), sink.readByteArray())
        assertContentEquals(byteArrayOf(1, 2, 3), chunk.toByteArray())
    }

    @Test
    fun chunkRepresentationsOfferFluentConversionsInBothDirections() {
        val position = ChunkPosition(-7, 11)
        val originalChunk = emptyChunk(position)
        originalChunk.setBlock(3, TEST_LAYOUT.minBlockY, 5, STONE)

        val nbtDocument = originalChunk.toNbtDocument(TEST_CODEC)
        val compressedChunk = nbtDocument.toCompressedChunk(Compression.GZIP)
        val decodedDocument = compressedChunk.toNbtDocument()
        val decodedChunk = compressedChunk.toChunk(position, TEST_CODEC)

        assertEquals(nbtDocument, decodedDocument)
        assertEquals(STONE, decodedChunk.block(3, TEST_LAYOUT.minBlockY, 5))
        assertEquals(decodedChunk.metadata, decodedDocument.toChunk(position, TEST_CODEC).metadata)

        val recompressedChunk = decodedChunk.toCompressedChunk(TEST_CODEC, Compression.LZ4)
        assertEquals(Compression.LZ4, recompressedChunk.compression)
        assertEquals(STONE, recompressedChunk.toChunk(position, TEST_CODEC).block(3, TEST_LAYOUT.minBlockY, 5))

        val nbtSink = Buffer()
        decodedChunk.writeTo(nbtSink, TEST_CODEC)
        assertEquals(nbtDocument, TEST_CODEC.nbt.decodeDocumentFromSource(nbtSink))

        val decompressedSink = Buffer()
        compressedChunk.writeDecompressedTo(decompressedSink)
        assertEquals(nbtDocument, TEST_CODEC.nbt.decodeDocumentFromSource(decompressedSink))
    }

    @Test
    fun compressedAndDocumentValuesOfferFluentTypedNbtDecoding() {
        val value = TypedNbtValue(41)
        val format = CompressedNbtFormat()
        val compressedChunk = format.encode(value)

        assertEquals(value, compressedChunk.decodeNbt<TypedNbtValue>(format))
    }

    @Test
    fun compressedInputsAndSourcesCanContinueAsDetachedValues() {
        val input = object : CompressedChunkInput {
            override val compression: Compression = Compression.NONE
            override val compressedByteCount: Long = 3

            override fun writeTo(sink: kotlinx.io.Sink) {
                sink.write(byteArrayOf(1, 2, 3))
            }
        }
        val compressedChunk = input.toCompressedChunk()
        val source = Buffer().apply { compressedChunk.writeTo(this) }

        assertContentEquals(byteArrayOf(1, 2, 3), compressedChunk.toByteArray())
        assertEquals(compressedChunk, source.readCompressedChunk(Compression.NONE))
    }

    @Test
    fun ordinaryPaletteWritesKeepStableIdsUntilEncodingCompactsThem() {
        val container = PalettedContainer(4, "air")
        container[0] = "stone"
        container[1] = "dirt"
        container[0] = "air"
        container[1] = "air"

        assertEquals(listOf("air", "stone", "dirt"), container.paletteSnapshot().values)
        assertEquals(setOf("air"), container.distinctValues())

        val chunk = emptyChunk()
        chunk.setBlock(0, TEST_LAYOUT.minBlockY, 0, STONE)
        chunk.setBlock(0, TEST_LAYOUT.minBlockY, 0, AIR)
        val document = TEST_CODEC.encodeDocument(chunk)
        val sections = document.root["sections"] as NbtList
        val section = sections.value.single() as NbtCompound
        val blockStates = section["block_states"] as NbtCompound

        assertEquals(1, (blockStates["palette"] as NbtList).size)
        assertNull(blockStates["data"])
    }

    @Test
    fun palettesCanBeCompactedInPlaceOrInspectedAsCompactSnapshots() {
        val container = PalettedContainer(4, "air")
        container[0] = "stone"
        container[1] = "dirt"
        container[0] = "dirt"
        val denseValues = container.toDenseList()

        val compactPalette = container.compactSnapshot()

        assertEquals(listOf("dirt", "air"), compactPalette.values)
        assertEquals(listOf(0, 0, 1, 1), compactPalette.ids)
        assertEquals(1, compactPalette.bitsPerEntry)
        assertEquals(4, compactPalette.entryCount)
        assertEquals(denseValues, List(compactPalette.entryCount) { compactPalette[it] })
        assertEquals(listOf("air", "stone", "dirt"), container.paletteSnapshot().values)

        container.compact()

        assertEquals(listOf("dirt", "air"), container.paletteSnapshot().values)
        assertEquals(denseValues, container.toDenseList())
    }

    @Test
    fun sectionCompactsBothPalettesInPlace() {
        val blockStates = PalettedContainer(SECTION_BLOCK_COUNT, AIR)
        val biomes = PalettedContainer(SECTION_BIOME_COUNT, "minecraft:plains")
        blockStates[0] = STONE
        blockStates[0] = AIR
        biomes[0] = "minecraft:desert"
        biomes[0] = "minecraft:plains"
        val section = ChunkSection(TEST_LAYOUT.minSectionY, blockStates, biomes)

        section.compactPalettes()

        assertEquals(listOf(AIR), section.blockStates.paletteSnapshot().values)
        assertEquals(listOf("minecraft:plains"), section.biomes.paletteSnapshot().values)
        assertEquals(AIR, section.block(0, 0, 0))
        assertEquals("minecraft:plains", section.biome(0, 0, 0))
    }

    @Test
    fun chunkCompactsPresentSectionsWithoutMaterializingMissingSections() {
        val chunk = emptyChunk()
        val firstSectionY = TEST_LAYOUT.minSectionY
        val secondSectionY = firstSectionY + 1

        assertFalse(chunk.hasSection(firstSectionY))
        assertEquals(AIR, chunk.block(0, TEST_LAYOUT.minBlockY, 0))
        assertFalse(chunk.hasSection(firstSectionY))

        val first = chunk.getOrCreateSection(firstSectionY)
        val second = chunk.getOrCreateSection(secondSectionY)
        first.blockStates[0] = STONE
        first.blockStates[0] = AIR
        first.biomes[0] = "minecraft:desert"
        first.biomes[0] = "minecraft:plains"
        second.blockStates[0] = WATER
        second.blockStates[0] = AIR

        chunk.compactPalettes()

        assertTrue(chunk.hasSection(firstSectionY))
        assertFalse(chunk.hasSection(TEST_LAYOUT.maxSectionY))
        assertEquals(listOf(AIR), first.blockStates.paletteSnapshot().values)
        assertEquals(listOf("minecraft:plains"), first.biomes.paletteSnapshot().values)
        assertEquals(listOf(AIR), second.blockStates.paletteSnapshot().values)
    }

    @Test
    fun chunkBlockPresenceTracksOnlyItsContainingSemanticSection() {
        val chunkPosition = ChunkPosition(-2, 3)
        val localPosition = ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0)
        val absolutePosition = chunkPosition.block(localPosition)
        val chunk = emptyChunk(chunkPosition)

        assertFalse(chunk.hasBlock(localPosition.x, localPosition.y, localPosition.z))
        assertFalse(chunk.hasBlock(localPosition))
        assertFalse(chunk.hasBlock(absolutePosition))
        assertEquals(0, chunk.sectionCount)

        chunk.getOrCreateSection(localPosition.sectionY)

        assertTrue(chunk.hasBlock(localPosition.x, localPosition.y, localPosition.z))
        assertTrue(chunk.hasBlock(localPosition))
        assertTrue(chunk.hasBlock(absolutePosition))
        assertEquals(AIR, chunk.block(localPosition))

        chunk.removeSection(localPosition.sectionY)

        assertFalse(chunk.hasBlock(localPosition))
        assertFailsWith<IllegalArgumentException> {
            chunk.hasBlock(ChunkPosition(0, 0).block(localPosition))
        }
    }

    @Test
    fun runtimeBlockOperationsReturnPreviousValuesAndSnapshotsDoNotShareContainers() {
        val chunk = emptyChunk()
        val y = TEST_LAYOUT.minBlockY

        assertEquals(AIR, chunk.replaceBlock(1, y, 2, AIR))
        assertEquals(0, chunk.sectionCount)
        assertEquals(AIR, chunk.replaceBlock(1, y, 2, STONE))
        assertEquals(STONE, chunk.replaceBlock(1, y, 2, WATER))

        val section = assertNotNull(chunk.section(MinecraftCoordinates.sectionCoordinate(y)))
        assertEquals(WATER, section.block(1, 0, 2))
        assertEquals(WATER, section.replaceBlock(1, 0, 2, STONE))
        section.blockLight = NbtByteArray(ByteArray(SECTION_LIGHT_BYTE_COUNT) { 1 })

        val snapshot = chunk.snapshot()
        chunk.setBlock(1, y, 2, WATER)
        section.blockLight = null

        assertEquals(STONE, snapshot.block(1, y, 2))
        assertNotNull(snapshot.section(section.sectionY)?.blockLight)
        assertFailsWith<IllegalArgumentException> {
            section.skyLight = NbtByteArray(ByteArray(1))
        }
    }

    @Test
    fun semanticBlockEntitiesRoundTripAndSupportLocalAndAbsoluteLookup() {
        val chunkPosition = ChunkPosition(-2, 3)
        val localPosition = ChunkBlockPosition(4, TEST_LAYOUT.minBlockY, 7)
        val absolutePosition = chunkPosition.block(localPosition)
        val blockEntity = BlockEntity(
            type = "minecraft:chest",
            position = absolutePosition,
            persistentData = NbtCompound(mapOf("CustomName" to NbtString("storage"))),
        )
        val chunk = emptyChunk(chunkPosition)

        assertNull(chunk.setBlockEntity(blockEntity))
        assertSame(blockEntity, chunk.blockEntity(localPosition))
        assertSame(blockEntity, chunk.blockEntity(absolutePosition))
        assertTrue(chunk.hasBlockEntity(absolutePosition))

        val decoded = TEST_CODEC.decodeDocument(TEST_CODEC.encodeDocument(chunk), chunkPosition)
        val decodedBlockEntity = assertNotNull(decoded.blockEntity(localPosition))

        assertEquals(blockEntity.type, decodedBlockEntity.type)
        assertEquals(blockEntity.persistentData, decodedBlockEntity.persistentData)
        assertEquals(absolutePosition, decodedBlockEntity.position)
        assertNotNull(decoded.removeBlockEntity(absolutePosition))
        assertEquals(0, decoded.blockEntityCount)
    }

    @Test
    fun decodingPreservesPersistedPaletteOrderAndUnusedEntries() {
        val position = ChunkPosition(0, 0)
        val chunk = emptyChunk()
        chunk.setBlock(0, TEST_LAYOUT.minBlockY, 0, STONE)
        val document = TEST_CODEC.encodeDocument(chunk)
        val root = document.root.value.toMutableMap()
        val encodedSection = (root.getValue("sections") as NbtList).value.single() as NbtCompound
        val section = encodedSection.value.toMutableMap()
        section["block_states"] = NbtCompound(
            mapOf(
                "palette" to NbtList(
                    listOf(
                        NbtCompound(mapOf("Name" to NbtString(AIR.name))),
                        NbtCompound(mapOf("Name" to NbtString(STONE.name))),
                        NbtCompound(
                            mapOf(
                                "Name" to NbtString(WATER.name),
                                "Properties" to NbtCompound(
                                    WATER.properties.mapValues { (_, value) -> NbtString(value) },
                                ),
                            ),
                        ),
                    ),
                ),
                "data" to NbtLongArray(LongArray(256) { 0x1111111111111111L }),
            ),
        )
        root["sections"] = NbtList(listOf(NbtCompound(section)))

        val decoded = TEST_CODEC.decodeDocument(
            com.hiczp.minecraft.nbt.NbtDocument(NbtCompound(root)),
            position,
        )

        assertEquals(
            listOf(AIR, STONE, WATER),
            decoded.section(TEST_LAYOUT.minSectionY)?.blockStates?.paletteSnapshot()?.values,
        )
        assertEquals(STONE, decoded.block(0, TEST_LAYOUT.minBlockY, 0))
    }

    @Test
    fun semanticChunkRoundTripsWithOfficialDiskPalettePacking() {
        val position = ChunkPosition(-25, 43)
        val chunk = emptyChunk(position)
        val lowY = TEST_LAYOUT.minBlockY
        chunk.setBlock(0, lowY, 0, STONE)
        chunk.setBlock(15, lowY + 15, 15, WATER)
        chunk.setBiome(12, lowY + 12, 12, "minecraft:desert")

        val document = TEST_CODEC.encodeDocument(chunk)
        val section = ((document.root["sections"] as NbtList).value.single() as NbtCompound)
        val blockStates = section["block_states"] as NbtCompound
        val biomes = section["biomes"] as NbtCompound

        assertEquals(3, (blockStates["palette"] as NbtList).size)
        assertEquals(256, (blockStates["data"] as NbtLongArray).size)
        assertEquals(2, (biomes["palette"] as NbtList).size)
        assertEquals(1, (biomes["data"] as NbtLongArray).size)

        val decoded = TEST_CODEC.decodeDocument(document, position)
        assertEquals(STONE, decoded.block(0, lowY, 0))
        assertEquals(WATER, decoded.block(15, lowY + 15, 15))
        assertEquals(AIR, decoded.block(1, lowY, 0))
        assertEquals("minecraft:desert", decoded.biome(12, lowY + 12, 12))
        assertEquals("minecraft:plains", decoded.biome(0, lowY, 0))

        val bytes = Buffer()
        TEST_CODEC.encodeToSink(decoded, bytes)
        assertEquals(decoded.metadata, TEST_CODEC.decodeFromSource(bytes, position).metadata)
    }

    @Test
    fun chunkPositionRoundTripsAsStateAndExpectedPositionIsValidated() {
        val position = ChunkPosition(-1, -1)
        val chunk = emptyChunk(position)
        val first = TEST_CODEC.encodeDocument(chunk)
        val snapshot = chunk.snapshot()
        val second = TEST_CODEC.encodeDocument(snapshot)

        assertEquals(-1, (first.root["xPos"] as NbtInt).value)
        assertEquals(-1, (first.root["zPos"] as NbtInt).value)
        assertEquals(position, snapshot.position)
        assertEquals(first, second)
        assertEquals(position, TEST_CODEC.decodeDocument(first).position)
        TEST_CODEC.decodeDocument(first, position)
        assertFailsWith<ChunkNbtFormatException> {
            TEST_CODEC.decodeDocument(first, ChunkPosition(0, 0))
        }
    }

    @Test
    fun chunkMetadataRecognizesOnlyTheTerminalWorldGenerationStatus() {
        val metadata = ChunkMetadata(TEST_DATA_VERSION, status = "minecraft:full")

        assertEquals("minecraft:full", ChunkMetadata.FULLY_GENERATED_STATUS)
        assertTrue(metadata.isFullyGenerated)
        assertFalse(metadata.copy(status = "minecraft:spawn").isFullyGenerated)
        assertFalse(metadata.copy(status = "example:custom").isFullyGenerated)
    }

    @Test
    fun encodingRejectsDefaultsThatWouldChangeWhenMissingSectionsAreDecoded() {
        val chunk = Chunk(
            position = ChunkPosition(0, 0),
            metadata = ChunkMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
            layout = TEST_LAYOUT,
            defaultBlockState = STONE,
            defaultBiome = "minecraft:plains",
        )

        assertFailsWith<ChunkNbtFormatException> {
            TEST_CODEC.encodeDocument(chunk)
        }
    }

    @Test
    fun lightOnlySectionsRemainExplicitWithoutBecomingSemanticSections() {
        val light = SectionLighting(blockLight = com.hiczp.minecraft.nbt.NbtByteArray(ByteArray(2_048)))
        val chunk = Chunk(
            position = ChunkPosition(0, 0),
            metadata = ChunkMetadata(
                dataVersion = TEST_DATA_VERSION,
                status = "minecraft:full",
                lightOnlySections = mapOf(TEST_LAYOUT.maxSectionY + 1 to light),
            ),
            layout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        val decoded = TEST_CODEC.decodeDocument(
            TEST_CODEC.encodeDocument(chunk),
            ChunkPosition(0, 0),
        )

        assertEquals(emptyList(), decoded.sections.toList())
        assertEquals(light, decoded.metadata.lightOnlySections.getValue(TEST_LAYOUT.maxSectionY + 1))
    }

    @Test
    fun creatingASemanticSectionPromotesItsExistingLighting() {
        val sectionY = TEST_LAYOUT.minSectionY
        val light = SectionLighting(skyLight = com.hiczp.minecraft.nbt.NbtByteArray(ByteArray(2_048) { 1 }))
        val chunk = Chunk(
            position = ChunkPosition(0, 0),
            metadata = ChunkMetadata(
                dataVersion = TEST_DATA_VERSION,
                status = "minecraft:full",
                lightOnlySections = mapOf(sectionY to light),
            ),
            layout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        val section = chunk.getOrCreateSection(sectionY)

        assertEquals(light.skyLight, section.skyLight)
        assertTrue(chunk.metadata.lightOnlySections.isEmpty())
    }

    @Test
    fun chunkSnapshotsMetadataCollections() {
        val sectionY = TEST_LAYOUT.maxSectionY + 1
        val light = SectionLighting(blockLight = com.hiczp.minecraft.nbt.NbtByteArray(ByteArray(2_048)))
        val lightOnlySections = mutableMapOf(sectionY to light)
        val chunk = Chunk(
            position = ChunkPosition(0, 0),
            metadata = ChunkMetadata(
                dataVersion = TEST_DATA_VERSION,
                status = "minecraft:full",
                lightOnlySections = lightOnlySections,
            ),
            layout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        lightOnlySections.clear()

        assertEquals(light, chunk.metadata.lightOnlySections.getValue(sectionY))
    }

    @Test
    fun callerSuppliedDomainValuesRoundTripWithoutAProtocolModuleDependency() {
        val air = ModBlockState("minecraft:air", emptyMap())
        val lamp = ModBlockState("example:lamp", mapOf("lit" to "true"))
        val registry = object : BlockStateRegistry<ModBlockState> {
            override val defaultValue = air

            override fun resolve(descriptor: BlockStateDescriptor): ModBlockState? =
                listOf(air, lamp).firstOrNull { it.name == descriptor.name && it.properties == descriptor.properties }

            override fun describe(value: ModBlockState): BlockStateDescriptor? =
                BlockStateDescriptor(value.name, value.properties)
        }
        val codec = ChunkNbtCodec(
            ChunkNbtContext(
                layout = TEST_LAYOUT,
                registries = ChunkDataRegistries(registry, NamedBiomeRegistry()),
                expectedDataVersion = TEST_DATA_VERSION,
            ),
        )
        val chunk = Chunk(
            position = ChunkPosition(0, 0),
            metadata = ChunkMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
            layout = TEST_LAYOUT,
            defaultBlockState = air,
            defaultBiome = "minecraft:plains",
        )
        chunk.setBlock(1, TEST_LAYOUT.minBlockY, 2, lamp)

        val decoded = codec.decodeDocument(codec.encodeDocument(chunk), ChunkPosition(0, 0))

        assertEquals(lamp, decoded.block(1, TEST_LAYOUT.minBlockY, 2))
    }

    @Test
    fun strongProjectionRejectsUnmodeledFieldsWhileDocumentRemainsComplete() {
        val position = ChunkPosition(0, 0)
        val ordinary = TEST_CODEC.encodeDocument(emptyChunk(position))
        val extendedRoot = ordinary.root.value.toMutableMap()
        extendedRoot["modded_payload"] = NbtInt(7)
        val extended = com.hiczp.minecraft.nbt.NbtDocument(NbtCompound(extendedRoot))

        assertEquals(NbtInt(7), extended.root["modded_payload"])
        assertFailsWith<ChunkNbtFormatException> {
            TEST_CODEC.decodeDocument(extended, position)
        }
    }

    private fun emptyChunk(position: ChunkPosition = ChunkPosition(0, 0)): Chunk<BlockStateDescriptor, String> = Chunk(
        position = position,
        metadata = ChunkMetadata(
            dataVersion = TEST_DATA_VERSION,
            lastUpdateTime = 12,
            inhabitedTime = 34,
            status = "minecraft:full",
        ),
        layout = TEST_LAYOUT,
        defaultBlockState = AIR,
        defaultBiome = "minecraft:plains",
    )

    private companion object {
        const val TEST_DATA_VERSION = 9_999
        val TEST_LAYOUT = ChunkLayout(minSectionY = -4, sectionCount = 24)
        val AIR = BlockStateDescriptor("minecraft:air")
        val STONE = BlockStateDescriptor("minecraft:stone")
        val WATER = BlockStateDescriptor("minecraft:water", mapOf("level" to "0"))
        val TEST_CODEC = ChunkNbtCodec(
            ChunkNbtContext(
                layout = TEST_LAYOUT,
                registries = ChunkDataRegistries(
                    blockStates = DescriptorBlockStateRegistry(AIR),
                    biomes = NamedBiomeRegistry(),
                ),
                expectedDataVersion = TEST_DATA_VERSION,
            ),
        )
    }

    private data class ModBlockState(
        val name: String,
        val properties: Map<String, String>,
    )

    @Serializable
    private data class TypedNbtValue(val value: Int)
}
