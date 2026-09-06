package com.hiczp.minecraft.world.format

import kotlin.test.*
import kotlin.uuid.Uuid

class ChunkDomainTest {
    @Test
    fun typedKeysAndCallerViewsUseTheSameMutableStore() {
        val burnTime = PropertyKey("BurnTime", PropertyTypes.Short)
        val properties = DataProperties()
        properties[burnTime] = 12.toShort()
        val blockEntity = BlockEntity(BlockEntityTypeId.parse("furnace"), properties = properties)
        val view = FurnaceView(blockEntity.properties, burnTime)
        view.burnOneTick()
        assertEquals(11.toShort(), properties[burnTime])
        properties["BurnTime"] = PropertyValue(PropertyTypes.Short, 8.toShort())
        assertEquals(8.toShort(), view.remaining)
        properties["BurnTime"] = PropertyValue(PropertyTypes.String, "custom")
        assertFailsWith<IllegalArgumentException> { view.remaining }
        properties.remove("BurnTime")
        assertNull(properties[burnTime])
        assertFailsWith<NoSuchElementException> { properties.require(burnTime) }
        val impostorType = PropertyType<Short>("short")
        properties[burnTime] = 2.toShort()
        assertFailsWith<IllegalArgumentException> { properties[PropertyKey("BurnTime", impostorType)] }
    }

    @Test
    fun blockStateEqualityUsesCanonicalValuesAndRetainsUnknownProperties() {
        val axis = StateProperty("axis", mapOf("x" to 0, "y" to 1, "z" to 2))
        val sameAxis = StateProperty("axis", mapOf("x" to "east", "y" to "up", "z" to "south"))
        val source = linkedMapOf("axis" to "y", "mod_mode" to "unrecognized")
        val state = BlockState(BlockId.parse("example:wood"), StateProperties(source))
        source["axis"] = "x"
        assertEquals(1, state[axis])
        assertEquals("up", state[sameAxis])
        val reordered =
            BlockState(state.blockId, StateProperties(linkedMapOf("mod_mode" to "unrecognized", "axis" to "y")))
        assertEquals(state, reordered)
        assertEquals(state.hashCode(), reordered.hashCode())
        assertEquals("x", state.with(axis, 0).properties["axis"])
        assertEquals("y", state.properties["axis"])
        assertFailsWith<IllegalArgumentException> { state.with(axis, 3) }
        assertEquals("unrecognized", state.properties.toMap()["mod_mode"])
    }

    @Test
    fun absentTerrainReadsWithoutMaterializationAndWritesPreserveOtherSectionData() {
        val context = testChunkContext()
        val chunk = Chunk(ChunkPosition(-1, -2), context)
        val position = BlockPosition(-1, -17, -17)
        assertEquals(context.defaultBlockState, chunk.getBlockState(position))
        assertTrue(chunk.sections.isEmpty())
        val light = LightLayer(4)
        val properties = DataProperties()
        val section = ChunkSection(null, SectionLighting(blockLight = light), properties)
        chunk.sections[-2] = section
        val stone = BlockState(BlockId.parse("stone"))
        assertEquals(context.defaultBlockState, chunk.setBlockState(position, stone))
        assertSame(section, chunk.sections[-2])
        assertSame(light, section.lighting.blockLight)
        assertSame(properties, section.properties)
        assertEquals(stone, section.terrain?.blockStates?.get(LocalBlockPosition(15, 15, 15).index))
        assertNull(section.terrain?.statistics?.nonEmptyBlockCount)
        assertEquals(context.defaultBiome, chunk.setBiome(position, BiomeId.parse("example:biome")))
        assertEquals(BiomeId.parse("example:biome"), chunk.getBiome(position))
        assertFailsWith<IllegalArgumentException> { chunk.getBlockState(BlockPosition(0, -17, -17)) }
        assertFailsWith<IllegalArgumentException> { chunk.getBlockState(BlockPosition(-1, -65, -17)) }
    }

    @Test
    fun callerReferencesAreRetainedAndMutationsHaveNoCoupledEffects() {
        val chunk = Chunk(ChunkPosition(0, 0), testChunkContext())
        val position = BlockPosition(0, 0, 0)
        val blockEntity = BlockEntity(BlockEntityTypeId.parse("chest"))
        val entities = linkedMapOf(position to blockEntity)
        val sections = linkedMapOf<Int, ChunkSection>()
        val complete = chunk.copy(sections = sections, blockEntities = entities)
        assertSame(sections, complete.sections)
        assertSame(entities, complete.blockEntities)
        complete.setBlockState(position, BlockState(BlockId.parse("stone")))
        assertSame(blockEntity, complete.blockEntities[position])
        complete.sections.getValue(0).terrain!!.statistics.nonEmptyBlockCount = 47
        complete.setBlockState(position, complete.chunkContext.defaultBlockState)
        assertEquals(47, complete.sections.getValue(0).terrain!!.statistics.nonEmptyBlockCount)
        complete.blockEntities.remove(position)
        blockEntity.properties["old_reference"] = PropertyValue(PropertyTypes.Int, 1)
        assertTrue(complete.blockEntities.isEmpty())
        complete.chunkContext = testChunkContext().copy(defaultBiome = BiomeId.parse("desert"))
        assertEquals(BiomeId.parse("desert"), complete.getBiome(BlockPosition(0, 32, 0)))
    }

    @Test
    fun paletteWritesKeepIdsAndCompactionDoesNotChangeLogicalValues() {
        val palette = PalettedContainer(64, "air")
        palette[0] = "stone"
        palette[1] = "unused"
        palette[1] = "stone"
        val before = palette.paletteSnapshot()
        assertEquals(listOf("air", "stone", "unused"), before.values)
        val compact = palette.compactSnapshot()
        assertEquals(listOf("stone", "air"), compact.values)
        assertEquals(before, palette.paletteSnapshot())
        palette.compact()
        assertEquals(compact.values, palette.paletteSnapshot().values)
        assertEquals("stone", palette[0])
        assertEquals("stone", palette[1])
        assertEquals("air", palette[2])
    }

    @Test
    fun entityAndPoiCollectionsRemainOrdinaryReferences() {
        val passenger = Entity(
            EntityTypeId.parse("pig"), Uuid.fromLongs(0, 2), EntityVector3d(100.0, 1000.0, 100.0),
            EntityVector3d.ZERO, EntityRotation.ZERO, mutableListOf()
        )
        val passengers = mutableListOf(passenger)
        val entity = passenger.copy(uuid = Uuid.fromLongs(0, 1), passengers = passengers)
        val rootEntities = mutableListOf(entity)
        val entityChunk =
            EntityChunk(ChunkPosition(0, 0), EntityChunkContext(DimensionId.Overworld), rootEntities, DataProperties())
        assertSame(rootEntities, entityChunk.rootEntities)
        assertSame(passengers, entity.passengers)
        passenger.position = EntityVector3d(-40.0, -1000.0, 0.0)
        assertSame(passenger, entityChunk.allEntities().last())
        val records = linkedMapOf(BlockPosition(0, 0, 0) to PoiRecord(PoiTypeId.parse("home"), -3))
        val section = PoiSection(true, records, DataProperties())
        section.records.clear()
        assertTrue(records.isEmpty())
        assertTrue(section.isValid)
    }

    private class FurnaceView(private val properties: DataProperties, private val key: PropertyKey<Short>) {
        val remaining: Short get() = properties.require(key)
        fun burnOneTick() {
            properties[key] = (remaining - 1).toShort()
        }
    }
}

internal fun testChunkContext(): ChunkContext = ChunkContext(
    DimensionId.Overworld, DimensionTypeLayout(-64, 384, 384, true, false),
    BlockState(BlockId.parse("air")), BiomeId.parse("plains"),
)
