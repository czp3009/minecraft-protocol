package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import kotlinx.io.Buffer
import kotlinx.io.Sink
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

        samples.forEach { blockPosition ->
            assertEquals(blockPosition, blockPosition.sectionPosition.block(blockPosition.localInSection))
            assertEquals(blockPosition, blockPosition.chunkPosition.block(blockPosition.localInChunk))
            assertEquals(blockPosition.localInChunk, blockPosition.chunkPosition.local(blockPosition))
            assertEquals(blockPosition.localInSection, blockPosition.sectionPosition.local(blockPosition))
            assertEquals(blockPosition.chunkPosition, blockPosition.sectionPosition.chunkPosition)
            assertEquals(blockPosition.localInSection, LocalBlockPosition.fromIndex(blockPosition.localInSection.index))
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
        val sectionPosition = blockPosition.sectionPosition
        val chunk = emptyChunk(chunkPosition)
        val chunkSection = chunk.getOrCreateSection(sectionPosition)

        chunk.setBlock(blockPosition, STONE)
        chunk.setBiome(blockPosition, "minecraft:desert")

        assertEquals(STONE, chunk.block(blockPosition))
        assertEquals("minecraft:desert", chunk.biome(blockPosition))
        assertEquals(STONE, chunkSection.block(sectionPosition, blockPosition))
        assertEquals("minecraft:desert", chunkSection.biome(sectionPosition, blockPosition))
        assertEquals(chunkSection, chunk.section(sectionPosition))
        assertEquals(chunkSection, chunk.section(blockPosition))
        assertTrue(chunk.hasSection(blockPosition.localInChunk))
        assertTrue(chunk.hasSection(sectionPosition))

        chunkSection.setBlock(sectionPosition, blockPosition, WATER)
        chunkSection.setBiome(sectionPosition, blockPosition, "minecraft:forest")
        assertEquals(WATER, chunk.block(blockPosition.localInChunk))
        assertEquals("minecraft:forest", chunk.biome(blockPosition.localInChunk))

        assertFailsWith<IllegalArgumentException> {
            chunk.block(ChunkPosition(0, 0).block(blockPosition.localInChunk))
        }
        assertFailsWith<IllegalArgumentException> {
            chunkSection.block(SectionPosition(0, sectionPosition.y, 0), blockPosition)
        }
        val otherSectionPosition = SectionPosition(sectionPosition.x, sectionPosition.y + 1, sectionPosition.z)
        val otherBlockPosition = otherSectionPosition.block(LocalBlockPosition(15, 0, 0))
        assertFailsWith<IllegalArgumentException> {
            chunkSection.block(otherSectionPosition, otherBlockPosition)
        }
    }

    @Test
    fun compressedChunkOwnsBytesAndWritesWithoutExposingThem() {
        val original = byteArrayOf(1, 2, 3)
        val compressedChunk = CompressedChunk(Compression.ZLIB, original)
        original[0] = 9

        val returned = compressedChunk.toByteArray()
        returned[1] = 9
        val sink = Buffer()
        compressedChunk.writeTo(sink)

        assertEquals(3L, compressedChunk.compressedByteCount)
        assertContentEquals(byteArrayOf(1, 2, 3), sink.readByteArray())
        assertContentEquals(byteArrayOf(1, 2, 3), compressedChunk.toByteArray())
    }

    @Test
    fun chunkRepresentationsOfferFluentConversionsInBothDirections() {
        val chunkPosition = ChunkPosition(-7, 11)
        val originalChunk = emptyChunk(chunkPosition)
        originalChunk.setBlock(3, TEST_LAYOUT.minBlockY, 5, STONE)

        val nbtDocument = originalChunk.toNbtDocument(TEST_CODEC)
        val compressedChunk = nbtDocument.toCompressedChunk(Compression.GZIP)
        val decodedDocument = compressedChunk.toNbtDocument()
        val decodedChunk = compressedChunk.toChunk(TEST_CODEC)

        assertEquals(nbtDocument, decodedDocument)
        assertEquals(STONE, decodedChunk.block(3, TEST_LAYOUT.minBlockY, 5))
        assertEquals(decodedChunk.chunkMetadata, decodedDocument.toChunk(TEST_CODEC).chunkMetadata)

        val recompressedChunk = decodedChunk.toCompressedChunk(TEST_CODEC, Compression.LZ4)
        assertEquals(Compression.LZ4, recompressedChunk.compression)
        assertEquals(STONE, recompressedChunk.toChunk(TEST_CODEC).block(3, TEST_LAYOUT.minBlockY, 5))

        val nbtSink = Buffer()
        decodedChunk.writeTo(nbtSink, TEST_CODEC)
        assertEquals(nbtDocument, TEST_CODEC.nbtFormat.decodeDocumentFromSource(nbtSink))

        val decompressedSink = Buffer()
        compressedChunk.writeDecompressedTo(decompressedSink)
        assertEquals(nbtDocument, TEST_CODEC.nbtFormat.decodeDocumentFromSource(decompressedSink))
    }

    @Test
    fun compressedAndDocumentValuesOfferFluentTypedNbtDecoding() {
        val typedNbtValue = TypedNbtValue(41)
        val compressedNbtFormat = CompressedNbtFormat()
        val compressedChunk = compressedNbtFormat.encode(typedNbtValue)

        assertEquals(typedNbtValue, compressedChunk.decodeNbt<TypedNbtValue>(compressedNbtFormat))
    }

    @Test
    fun compressedInputsAndSourcesCanContinueAsDetachedValues() {
        val input = object : CompressedChunkInput {
            override val compression: Compression = Compression.NONE
            override val compressedByteCount: Long = 99

            override fun writeTo(sink: Sink) {
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
        val palettedContainer = PalettedContainer(4, "air")
        palettedContainer[0] = "stone"
        palettedContainer[1] = "dirt"
        palettedContainer[0] = "air"
        palettedContainer[1] = "air"

        assertEquals(listOf("air", "stone", "dirt"), palettedContainer.paletteSnapshot().values)
        assertEquals(setOf("air"), palettedContainer.distinctValues())

        val chunk = emptyChunk()
        chunk.setBlock(0, TEST_LAYOUT.minBlockY, 0, STONE)
        chunk.setBlock(0, TEST_LAYOUT.minBlockY, 0, AIR)
        val nbtDocument = TEST_CODEC.encodeDocument(chunk)
        val sections = nbtDocument.root["sections"] as NbtList
        val section = sections.value.single() as NbtCompound
        val blockStates = section["block_states"] as NbtCompound

        assertEquals(1, (blockStates["palette"] as NbtList).size)
        assertNull(blockStates["data"])

        val blockStatesWithRedundantData = NbtCompound(blockStates.value + ("data" to NbtLongArray(longArrayOf(1))))
        val sectionWithRedundantData = NbtCompound(section.value + ("block_states" to blockStatesWithRedundantData))
        val rootWithRedundantData = NbtCompound(
            nbtDocument.root.value + ("sections" to NbtList(listOf(sectionWithRedundantData))),
        )
        assertEquals(
            AIR,
            TEST_CODEC.decodeDocument(NbtDocument(rootWithRedundantData)).block(0, TEST_LAYOUT.minBlockY, 0)
        )
    }

    @Test
    fun palettesCanBeCompactedInPlaceOrInspectedAsCompactSnapshots() {
        val palettedContainer = PalettedContainer(4, "air")
        palettedContainer[0] = "stone"
        palettedContainer[1] = "dirt"
        palettedContainer[0] = "dirt"
        val denseValues = palettedContainer.toDenseList()

        val compactPalette = palettedContainer.compactSnapshot()

        assertEquals(listOf("dirt", "air"), compactPalette.values)
        assertEquals(listOf(0, 0, 1, 1), compactPalette.ids)
        assertEquals(1, compactPalette.bitsPerEntry)
        assertEquals(4, compactPalette.entryCount)
        assertEquals(denseValues, List(compactPalette.entryCount) { compactPalette[it] })
        assertEquals(listOf("air", "stone", "dirt"), palettedContainer.paletteSnapshot().values)

        palettedContainer.compact()

        assertEquals(listOf("dirt", "air"), palettedContainer.paletteSnapshot().values)
        assertEquals(denseValues, palettedContainer.toDenseList())
    }

    @Test
    fun sectionCompactsBothPalettesInPlace() {
        val blockStates = PalettedContainer(SECTION_BLOCK_COUNT, AIR)
        val biomes = PalettedContainer(SECTION_BIOME_COUNT, "minecraft:plains")
        blockStates[0] = STONE
        blockStates[0] = AIR
        biomes[0] = "minecraft:desert"
        biomes[0] = "minecraft:plains"
        val chunkSection = ChunkSection(TEST_LAYOUT.minSectionY, blockStates, biomes)

        chunkSection.compactPalettes()

        assertEquals(listOf(AIR), chunkSection.blockStates.paletteSnapshot().values)
        assertEquals(listOf("minecraft:plains"), chunkSection.biomes.paletteSnapshot().values)
        assertEquals(AIR, chunkSection.block(0, 0, 0))
        assertEquals("minecraft:plains", chunkSection.biome(0, 0, 0))
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
        val chunkBlockPosition = ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0)
        val absolutePosition = chunkPosition.block(chunkBlockPosition)
        val chunk = emptyChunk(chunkPosition)

        assertFalse(chunk.hasBlock(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z))
        assertFalse(chunk.hasBlock(chunkBlockPosition))
        assertFalse(chunk.hasBlock(absolutePosition))
        assertEquals(0, chunk.sectionCount)

        chunk.getOrCreateSection(chunkBlockPosition.sectionY)

        assertTrue(chunk.hasBlock(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z))
        assertTrue(chunk.hasBlock(chunkBlockPosition))
        assertTrue(chunk.hasBlock(absolutePosition))
        assertEquals(AIR, chunk.block(chunkBlockPosition))

        chunk.removeSection(chunkBlockPosition.sectionY)

        assertFalse(chunk.hasBlock(chunkBlockPosition))
        assertFailsWith<IllegalArgumentException> {
            chunk.hasBlock(ChunkPosition(0, 0).block(chunkBlockPosition))
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

        val chunkSection = assertNotNull(chunk.section(MinecraftCoordinates.sectionCoordinate(y)))
        assertEquals(WATER, chunkSection.block(1, 0, 2))
        assertEquals(WATER, chunkSection.replaceBlock(1, 0, 2, STONE))
        chunkSection.blockLight = NbtByteArray(ByteArray(SECTION_LIGHT_BYTE_COUNT) { 1 })

        val snapshot = chunk.snapshot()
        chunk.setBlock(1, y, 2, WATER)
        chunkSection.blockLight = null

        assertEquals(STONE, snapshot.block(1, y, 2))
        assertNotNull(snapshot.section(chunkSection.sectionY)?.blockLight)
        assertFailsWith<IllegalArgumentException> {
            chunkSection.skyLight = NbtByteArray(ByteArray(1))
        }
    }

    @Test
    fun semanticBlockEntitiesRoundTripAndSupportLocalAndAbsoluteLookup() {
        val chunkPosition = ChunkPosition(-2, 3)
        val chunkBlockPosition = ChunkBlockPosition(4, TEST_LAYOUT.minBlockY, 7)
        val absolutePosition = chunkPosition.block(chunkBlockPosition)
        val blockEntity = BlockEntity(
            type = "minecraft:chest",
            blockPosition = absolutePosition,
            persistentData = NbtCompound(mapOf("CustomName" to NbtString("storage"))),
        )
        val chunk = emptyChunk(chunkPosition)

        assertNull(chunk.setBlockEntity(blockEntity))
        assertSame(blockEntity, chunk.blockEntity(chunkBlockPosition))
        assertSame(blockEntity, chunk.blockEntity(absolutePosition))
        assertTrue(chunk.hasBlockEntity(absolutePosition))

        val decoded = TEST_CODEC.decodeDocument(TEST_CODEC.encodeDocument(chunk))
        val decodedBlockEntity = assertNotNull(decoded.blockEntity(chunkBlockPosition))

        assertEquals(blockEntity.type, decodedBlockEntity.type)
        assertEquals(blockEntity.persistentData, decodedBlockEntity.persistentData)
        assertEquals(absolutePosition, decodedBlockEntity.blockPosition)
        assertNotNull(decoded.removeBlockEntity(absolutePosition))
        assertEquals(0, decoded.blockEntityCount)
    }

    @Test
    fun persistenceUsesTypedFieldsAndIgnoresUnknownFields() {
        val chunk = emptyChunk()
        val chunkSection = ChunkSection(
            sectionY = TEST_LAYOUT.minSectionY,
            blockStates = PalettedContainer(SECTION_BLOCK_COUNT, AIR),
            biomes = PalettedContainer(SECTION_BIOME_COUNT, "minecraft:plains"),
        )
        val blockEntity = BlockEntity(
            type = "minecraft:chest",
            blockPosition = BlockPosition(0, TEST_LAYOUT.minBlockY, 0),
            persistentData = NbtCompound(
                mapOf(
                    "id" to NbtString("example:collision"),
                    "x" to NbtInt(999),
                    "FutureBlockEntityField" to NbtInt(7),
                ),
            ),
        )
        chunk.setSection(chunkSection)
        chunk.setBlockEntity(blockEntity)

        val encoded = TEST_CODEC.encodeDocument(chunk)
        val rootWithUnknownField = NbtCompound(encoded.root.value + ("FutureRootField" to NbtInt(9)))
        val decoded = TEST_CODEC.decodeDocument(NbtDocument(rootWithUnknownField))
        val decodedBlockEntity = assertNotNull(decoded.blockEntity(blockEntity.blockPosition))

        assertEquals(chunkSection.sectionY, assertNotNull(decoded.section(chunkSection.sectionY)).sectionY)
        assertEquals(blockEntity.type, decodedBlockEntity.type)
        assertEquals(blockEntity.blockPosition, decodedBlockEntity.blockPosition)
        assertEquals(NbtInt(7), decodedBlockEntity.persistentData["FutureBlockEntityField"])
        assertNull(decodedBlockEntity.persistentData["id"])
        assertNull(decodedBlockEntity.persistentData["x"])
    }

    @Test
    fun semanticChunkMaintainsLayoutAndBlockEntityOwnership() {
        val chunk = emptyChunk()
        val outsideSection = ChunkSection(
            sectionY = TEST_LAYOUT.maxSectionY + 1,
            blockStates = PalettedContainer(SECTION_BLOCK_COUNT, AIR),
            biomes = PalettedContainer(SECTION_BIOME_COUNT, "minecraft:plains"),
        )

        assertFailsWith<IllegalArgumentException> { chunk.setSection(outsideSection) }
        assertFailsWith<IllegalArgumentException> {
            chunk.setBlockEntity(BlockEntity("minecraft:chest", BlockPosition(16, TEST_LAYOUT.minBlockY, 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            chunk.setBlockEntity(BlockEntity("minecraft:chest", BlockPosition(0, TEST_LAYOUT.maxBlockY + 1, 0)))
        }
    }

    @Test
    fun decodingPreservesPersistedPaletteOrderAndUnusedEntries() {
        val chunkPosition = ChunkPosition(0, 0)
        val chunk = emptyChunk()
        chunk.setBlock(0, TEST_LAYOUT.minBlockY, 0, STONE)
        val nbtDocument = TEST_CODEC.encodeDocument(chunk)
        val root = nbtDocument.root.value.toMutableMap()
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
                "data" to NbtLongArray(LongArray(257) { 0x1111111111111111L }),
            ),
        )
        root["sections"] = NbtList(listOf(NbtCompound(section)))

        val decoded = TEST_CODEC.decodeDocument(
            NbtDocument(NbtCompound(root)),
        )

        assertEquals(
            listOf(AIR, STONE, WATER),
            decoded.section(TEST_LAYOUT.minSectionY)?.blockStates?.paletteSnapshot()?.values,
        )
        assertEquals(STONE, decoded.block(0, TEST_LAYOUT.minBlockY, 0))
    }

    @Test
    fun semanticChunkRoundTripsWithOfficialDiskPalettePacking() {
        val chunkPosition = ChunkPosition(-25, 43)
        val chunk = emptyChunk(chunkPosition)
        val lowY = TEST_LAYOUT.minBlockY
        chunk.setBlock(0, lowY, 0, STONE)
        chunk.setBlock(15, lowY + 15, 15, WATER)
        chunk.setBiome(12, lowY + 12, 12, "minecraft:desert")

        val nbtDocument = TEST_CODEC.encodeDocument(chunk)
        val section = ((nbtDocument.root["sections"] as NbtList).value.single() as NbtCompound)
        val blockStates = section["block_states"] as NbtCompound
        val biomes = section["biomes"] as NbtCompound

        assertEquals(3, (blockStates["palette"] as NbtList).size)
        assertEquals(256, (blockStates["data"] as NbtLongArray).size)
        assertEquals(2, (biomes["palette"] as NbtList).size)
        assertEquals(1, (biomes["data"] as NbtLongArray).size)

        val decoded = TEST_CODEC.decodeDocument(nbtDocument)
        assertEquals(STONE, decoded.block(0, lowY, 0))
        assertEquals(WATER, decoded.block(15, lowY + 15, 15))
        assertEquals(AIR, decoded.block(1, lowY, 0))
        assertEquals("minecraft:desert", decoded.biome(12, lowY + 12, 12))
        assertEquals("minecraft:plains", decoded.biome(0, lowY, 0))

        val bytes = Buffer()
        TEST_CODEC.encodeToSink(decoded, bytes)
        assertEquals(decoded.chunkMetadata, TEST_CODEC.decodeFromSource(bytes).chunkMetadata)
    }

    @Test
    fun chunkPositionRoundTripsAsState() {
        val chunkPosition = ChunkPosition(-1, -1)
        val chunk = emptyChunk(chunkPosition)
        val first = TEST_CODEC.encodeDocument(chunk)
        val snapshot = chunk.snapshot()
        val second = TEST_CODEC.encodeDocument(snapshot)

        assertEquals(-1, (first.root["xPos"] as NbtInt).value)
        assertEquals(-1, (first.root["zPos"] as NbtInt).value)
        assertEquals(chunkPosition, snapshot.chunkPosition)
        assertEquals(first, second)
        assertEquals(chunkPosition, TEST_CODEC.decodeDocument(first).chunkPosition)
    }

    @Test
    fun chunkStorageMetadataRecognizesOnlyTheTerminalWorldGenerationStatus() {
        val chunkStorageMetadata = ChunkStorageMetadata(TEST_DATA_VERSION, status = "minecraft:full")

        assertEquals("minecraft:full", ChunkStorageMetadata.FULLY_GENERATED_STATUS)
        assertTrue(chunkStorageMetadata.isFullyGenerated)
        assertFalse(chunkStorageMetadata.copy(status = "minecraft:spawn").isFullyGenerated)
        assertFalse(chunkStorageMetadata.copy(status = "example:custom").isFullyGenerated)
    }

    @Test
    fun codecCarriesDataVersionWithoutACompatibilityGate() {
        val chunkPosition = ChunkPosition(0, 0)
        val dataVersion = Int.MIN_VALUE
        val chunk = Chunk(
            chunkPosition = chunkPosition,
            chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(dataVersion, status = "minecraft:full"),
            ),
            chunkLayout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        val decoded = TEST_CODEC.decodeDocument(TEST_CODEC.encodeDocument(chunk))

        assertEquals(dataVersion, decoded.chunkMetadata.chunkStorageMetadata?.dataVersion)
    }

    @Test
    fun persistentEncodingRejectsAChunkWithoutStorageMetadata() {
        val chunk = Chunk(
            chunkPosition = ChunkPosition(0, 0),
            chunkMetadata = ChunkMetadata(),
            chunkLayout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        val failure = assertFailsWith<ChunkNbtFormatException> { TEST_CODEC.encodeDocument(chunk) }

        assertContains(failure.message.orEmpty(), "ChunkStorageMetadata")
    }

    @Test
    fun codecConfigurationOwnsLayoutAndDefaultsWithoutCrossCheckingChunkState() {
        val chunk = Chunk(
            chunkPosition = ChunkPosition(0, 0),
            chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
            ),
            chunkLayout = ChunkLayout(minSectionY = 0, sectionCount = 1),
            defaultBlockState = STONE,
            defaultBiome = "example:other_biome",
        )
        val encoded = TEST_CODEC.encodeDocument(chunk)
        assertEquals(NbtInt(TEST_LAYOUT.minSectionY), encoded.root["yPos"])
        val storedRoot = encoded.root.value.toMutableMap()
        storedRoot.remove("yPos")

        val decoded = TEST_CODEC.decodeDocument(NbtDocument(NbtCompound(storedRoot)))

        assertEquals(TEST_LAYOUT, decoded.chunkLayout)
        assertEquals(AIR, decoded.defaultBlockState)
        assertEquals("minecraft:plains", decoded.defaultBiome)
    }

    @Test
    fun lightOnlySectionsRemainExplicitWithoutBecomingSemanticSections() {
        val sectionLighting = SectionLighting(blockLight = NbtByteArray(ByteArray(2_048)))
        val chunk = Chunk(
            chunkPosition = ChunkPosition(0, 0),
            chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
                lightOnlySections = mapOf(TEST_LAYOUT.maxSectionY + 1 to sectionLighting),
            ),
            chunkLayout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        val decoded = TEST_CODEC.decodeDocument(
            TEST_CODEC.encodeDocument(chunk),
        )

        assertEquals(emptyList(), decoded.sections.toList())
        assertEquals(sectionLighting, decoded.chunkMetadata.lightOnlySections.getValue(TEST_LAYOUT.maxSectionY + 1))
    }

    @Test
    fun semanticChunkRejectsDuplicateSectionCoordinates() {
        val chunk = emptyChunk()
        chunk.getOrCreateSection(TEST_LAYOUT.minSectionY)
        val encoded = TEST_CODEC.encodeDocument(chunk)
        val section = (encoded.root["sections"] as NbtList).value.single()
        val root = NbtCompound(encoded.root.value + ("sections" to NbtList(listOf(section, section))))

        val failure = assertFailsWith<ChunkNbtFormatException> {
            TEST_CODEC.decodeDocument(NbtDocument(root))
        }

        assertEquals("Invalid Chunk", failure.message)
        assertContains(failure.cause?.message.orEmpty(), "duplicate Section Y")
    }

    @Test
    fun creatingASemanticSectionPromotesItsExistingLighting() {
        val sectionY = TEST_LAYOUT.minSectionY
        val sectionLighting = SectionLighting(skyLight = NbtByteArray(ByteArray(2_048) { 1 }))
        val chunk = Chunk(
            chunkPosition = ChunkPosition(0, 0),
            chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
                lightOnlySections = mapOf(sectionY to sectionLighting),
            ),
            chunkLayout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        val chunkSection = chunk.getOrCreateSection(sectionY)

        assertEquals(sectionLighting.skyLight, chunkSection.skyLight)
        assertTrue(chunk.chunkMetadata.lightOnlySections.isEmpty())
    }

    @Test
    fun chunkSnapshotsMetadataCollections() {
        val sectionY = TEST_LAYOUT.maxSectionY + 1
        val sectionLighting = SectionLighting(blockLight = NbtByteArray(ByteArray(2_048)))
        val lightOnlySections = mutableMapOf(sectionY to sectionLighting)
        val chunk = Chunk(
            chunkPosition = ChunkPosition(0, 0),
            chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
                lightOnlySections = lightOnlySections,
            ),
            chunkLayout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )

        lightOnlySections.clear()

        assertEquals(sectionLighting, chunk.chunkMetadata.lightOnlySections.getValue(sectionY))
    }

    @Test
    fun callerSuppliedDomainValuesRoundTripWithoutAProtocolModuleDependency() {
        val air = ModBlockState("minecraft:air", emptyMap())
        val lamp = ModBlockState("example:lamp", mapOf("lit" to "true"))
        val blockStateRegistry = object : BlockStateRegistry<ModBlockState> {
            override val defaultValue = air

            override fun resolve(blockStateDescriptor: BlockStateDescriptor): ModBlockState? =
                listOf(air, lamp).firstOrNull {
                    it.name == blockStateDescriptor.name && it.properties == blockStateDescriptor.properties
                }

            override fun describe(value: ModBlockState): BlockStateDescriptor? =
                BlockStateDescriptor(value.name, value.properties)
        }
        val chunkNbtCodec = ChunkNbtCodec(
            ChunkCodecContext(
                chunkLayout = TEST_LAYOUT,
                chunkDataRegistries = ChunkDataRegistries(blockStateRegistry, NamedBiomeRegistry()),
            ),
        )
        val chunk = Chunk(
            chunkPosition = ChunkPosition(0, 0),
            chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
            ),
            chunkLayout = TEST_LAYOUT,
            defaultBlockState = air,
            defaultBiome = "minecraft:plains",
        )
        chunk.setBlock(1, TEST_LAYOUT.minBlockY, 2, lamp)

        val decoded = chunkNbtCodec.decodeDocument(chunkNbtCodec.encodeDocument(chunk))

        assertEquals(lamp, decoded.block(1, TEST_LAYOUT.minBlockY, 2))
    }

    @Test
    fun strongProjectionIgnoresUnmodeledFieldsWhileDocumentRemainsComplete() {
        val chunkPosition = ChunkPosition(0, 0)
        val ordinary = TEST_CODEC.encodeDocument(emptyChunk(chunkPosition))
        val extendedRoot = ordinary.root.value.toMutableMap()
        extendedRoot["modded_payload"] = NbtInt(7)
        val extended = NbtDocument(NbtCompound(extendedRoot))

        assertEquals(NbtInt(7), extended.root["modded_payload"])
        assertEquals(chunkPosition, TEST_CODEC.decodeDocument(extended).chunkPosition)
    }

    private fun emptyChunk(chunkPosition: ChunkPosition = ChunkPosition(0, 0)): Chunk<BlockStateDescriptor, String> =
        Chunk(
            chunkPosition = chunkPosition,
            chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(
                    dataVersion = TEST_DATA_VERSION,
                    lastUpdateTime = 12,
                    inhabitedTime = 34,
                    status = "minecraft:full",
                ),
            ),
            chunkLayout = TEST_LAYOUT,
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
            ChunkCodecContext(
                chunkLayout = TEST_LAYOUT,
                chunkDataRegistries = ChunkDataRegistries(
                    blockStates = DescriptorBlockStateRegistry(AIR),
                    biomes = NamedBiomeRegistry(),
                ),
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
