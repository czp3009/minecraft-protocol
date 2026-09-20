package com.hiczp.minecraft.world.format

import kotlin.test.*
import kotlin.uuid.Uuid

class ChunkStorageTest {
    @Test
    fun paletteGrowthFillAndDuplicateEntriesPreserveEveryCell() {
        val palette = PalettedContainer(4096, 0)
        repeat(4096) { palette[it] = it }
        repeat(4096) { assertEquals(it, palette[it]) }
        val compacted = palette.compactCopy()
        palette.fill(17)
        assertEquals(setOf(17), palette.distinctValues())
        val uniform = palette.compactCopy()
        assertEquals(0, uniform.paletteInfo().bitsPerEntry)
        repeat(4096) { assertEquals(17, uniform[it]); assertEquals(it, compacted[it]) }
        palette[4095] = 4095
        repeat(4095) { assertEquals(17, palette[it]) }
        assertEquals(4095, palette[4095])
        palette.compact()
        assertEquals(listOf(17, 4095), palette.paletteInfo().values)

        val duplicates = PalettedContainer.fromPalette(listOf("a", "a", "b"), intArrayOf(1, 0, 2, 1))
        assertEquals(listOf("a", "a", "b"), duplicates.paletteInfo().values)
        duplicates.compact()
        assertEquals(listOf("a", "b"), duplicates.paletteInfo().values)
        assertEquals(listOf("a", "a", "b", "a"), duplicates.toDenseList())
    }

    @Test
    fun copiesPreserveHistoryAndCellIdsWhileDetachingMutableStorage() {
        val first = BlockState(BlockId("test:first"))
        val second = BlockState(BlockId("test:second"))
        val history = BlockState(BlockId("test:history"))
        val source = PalettedContainer.fromPalette(listOf(first, second, first, history), IntArray(73) { it % 3 })
        val before = source.paletteInfo()
        val copied = source.copy()
        assertEquals(before, copied.paletteInfo())
        repeat(source.size) {
            assertSame(source[it], copied[it])
            assertEquals(source.paletteIndex(it), copied.paletteIndex(it))
        }
        copied[0] = history
        copied[1] = first
        assertSame(first, source[0])
        assertSame(second, source[1])
        source[72] = second
        assertSame(first, copied[72])
        copied.compact()
        assertEquals(before, source.paletteInfo())
        val added = BlockState(BlockId("test:added"))
        copied[0] = added
        assertFalse(added in source.paletteInfo().values)
        val diagnostics = copied.paletteInfo()
        copied.fill(second)
        copied.compact()
        assertTrue(added in diagnostics.values)
        assertEquals(listOf(second), copied.paletteInfo().values)
    }

    @Test
    fun compactCopiesAreEditableAndPreserveTheSourceHistoryAndCells() {
        val source = PalettedContainer.fromPalette(listOf("unused", "a", "a", "b"), intArrayOf(3, 2, 1, 3, 2))
        val before = source.paletteInfo()
        val compacted = source.compactCopy()
        assertEquals(before, source.paletteInfo())
        assertEquals(listOf(3, 2, 1, 3, 2), List(source.size, source::paletteIndex))
        assertEquals(listOf("b", "a"), compacted.paletteInfo().values)
        assertEquals(listOf(0, 1, 1, 0, 1), List(compacted.size, compacted::paletteIndex))
        assertEquals(source.toDenseList(), compacted.toDenseList())
        compacted[0] = "new"
        source[1] = "changed"
        assertEquals("b", source[0])
        assertEquals("a", compacted[1])
        compacted.fill("filled")
        assertEquals("b", source[3])
    }

    @Test
    fun uniformCopiesHandleHistoricalIdsAndCollapsedDuplicateValues() {
        val source = PalettedContainer(65, "history")
        source.fill("current")
        val copied = source.copy()
        val compacted = source.compactCopy()
        assertEquals(listOf("history", "current"), copied.paletteInfo().values)
        assertEquals(listOf("current"), compacted.paletteInfo().values)
        repeat(65) {
            assertEquals(1, copied.paletteIndex(it))
            assertEquals(0, compacted.paletteIndex(it))
        }
        copied[64] = "history"
        compacted[0] = "new"
        assertEquals("current", source[64])
        assertEquals("current", source[0])
        assertEquals("current", copied[0])
        assertEquals("current", compacted[64])
        val duplicates = PalettedContainer.fromPalette(listOf("equal", "equal"), intArrayOf(0, 1, 0))
        val collapsed = duplicates.compactCopy()
        assertEquals(0, collapsed.paletteInfo().bitsPerEntry)
        assertEquals(listOf("equal", "equal", "equal"), collapsed.toDenseList())
        collapsed[2] = "different"
        assertEquals("equal", duplicates[2])
        assertEquals(1, collapsed.paletteIndex(2))
    }

    @Test
    fun paletteConstructionAndUpdatesDoNotSearchTheWholePalette() {
        val comparisons = intArrayOf(0)
        val palette = PalettedContainer(4096, CountedValue(0, comparisons))
        repeat(4096) { palette[it] = CountedValue(it, comparisons) }
        repeat(4096) { palette[it] = CountedValue(it, comparisons) }
        palette.compactCopy()
        assertTrue(comparisons[0] < 4096 * 20, "Distinct-value writes must avoid a per-cell linear palette search")
    }

    @Test
    fun sharedStateFamiliesReuseTransitionsAndRetainOpenProperties() {
        val definition = BlockStateDefinition(BlockId("test:block"))
        val initial = definition.state(StateProperties(mapOf("mode" to "a", "custom" to "unknown")))
        val changed = initial.with("mode", "b")
        assertSame(initial, initial.with("mode", "a"))
        assertSame(changed, initial.with("mode", "b"))
        assertSame(initial, changed.with("mode", "a"))
        assertSame(changed, definition.state(changed.properties))
        assertEquals("unknown", changed.properties["custom"])
        assertEquals("a", initial.properties["mode"])
    }

    @Test
    fun sectionOriginsAndPrimitiveStorageHaveOrdinaryAliasSemantics() {
        val chunk = Chunk(ChunkPosition(0, 0), testChunkContext())
        val sections = chunk.sections
        val section = ChunkSection(null, SectionLighting(), DataProperties())
        sections[-1 - chunk.sectionMinY] = section
        assertSame(section, chunk.getSection(-1))
        chunk.chunkContext =
            chunk.chunkContext.copy(dimensionTypeLayout = DimensionTypeLayout(0, 256, 256, true, false))
        assertSame(section, chunk.getSection(-1))
        chunk.setSection(-20, section)
        assertNotSame(sections, chunk.sections)
        assertSame(section, chunk.getSection(-1))
        assertSame(section, chunk.getSection(-20))
        val detached = chunk.sections
        chunk.sections = arrayOf(section)
        chunk.sectionMinY = 0
        detached[0] = null
        assertSame(section, chunk.getSection(0))

        val packed = ByteArray(2048)
        val light = LightLayer(packed)
        packed[0] = 0x3f
        assertEquals(15, light[0])
        assertEquals(3, light[1])
        light[0] = 4
        assertEquals(0x34.toByte(), packed[0])
        light.fill(7)
        light[0] = 7
        assertNull(light.data)
        packed[0] = 0
        assertEquals(7, light[0])
        assertNull(light.data)
        light[3] = 9
        assertEquals(7, light[2])
        assertEquals(9, light[3])
        val heights = IntArray(256)
        val heightmap = Heightmap(heights)
        heights[7] = 100
        assertEquals(100, heightmap[7])
        heightmap[7] = null
        assertFalse(heightmap.known[7])
    }

    @Test
    fun itemCopiesDetachMutableTreesWithoutSerializationAndSupportCustomCopiers() {
        val stack = ItemStack(ItemId("test:item"), 5)
        val shared = intArrayOf(1, 2)
        stack.properties["first"] = PropertyValue(PropertyTypes.IntArray, shared)
        stack.properties["second"] = PropertyValue(PropertyTypes.IntArray, shared)
        val copy = stack.copy(count = 2)
        val first = copy.properties.require(PropertyKey("first", PropertyTypes.IntArray))
        first[0] = 10
        assertEquals(1, shared[0])
        assertEquals(1, copy.properties.require(PropertyKey("second", PropertyTypes.IntArray))[0])
        assertEquals(5, stack.count)
        assertEquals(2, copy.count)
        val key = PropertyKey("counter", PropertyTypes.Int)
        stack.properties[key] = 1
        val cell = stack.properties["counter"]
        stack.properties[key] = 2
        assertSame(cell, stack.properties["counter"])
        assertEquals(2, cell!!.get(PropertyTypes.Int))

        val customType = PropertyType<MutableList<Int>>("custom")
        stack.properties["custom"] = PropertyValue(customType, mutableListOf(1))
        assertFailsWith<IllegalStateException> { stack.copy() }
        val customCopy = stack.copy(
            propertyCopyContext = PropertyCopyContext(
                listOf(
                    PropertyValueCopier(customType) { value, _ -> value.toMutableList() },
                )
            )
        )
        customCopy.properties["custom"]!!.get(customType).add(2)
        assertEquals(listOf(1), stack.properties["custom"]!!.get(customType))
        stack.properties.remove("custom")
        stack.properties["self"] = PropertyValue(PropertyTypes.ItemStack, stack)
        assertFailsWith<IllegalArgumentException> { stack.copy() }
    }

    @Test
    fun copiedComponentsSlotsAndEntityPropertiesDetachNestedMutableValues() {
        val item = ItemStack(ItemId("test:child"), 3)
        val slots = ItemSlots(arrayOf(item))
        val componentId = ComponentId("test:inventory")
        val stack = ItemStack(
            ItemId("test:container"), 1, DataComponentPatch(
                linkedMapOf(
                    componentId to ComponentPatchEntry.SetValue(PropertyValue(PropertyTypes.ItemSlots, slots)),
                )
            )
        )
        val attributeId = AttributeId("test:attribute")
        val modifierId = ModifierId("test:modifier")
        val modifier = AttributeModifier(2.0, AttributeOperation.ADD_VALUE, true)
        val attributes =
            EntityAttributes(linkedMapOf(attributeId to AttributeInstance(1.0, linkedMapOf(modifierId to modifier))))
        val hidden = EffectState(1, 10, false, true, true, null)
        val effect = hidden.copy(hiddenEffect = hidden)
        val effectId = MobEffectId("test:effect")
        stack.properties[EntityProperties.Attributes] = attributes
        stack.properties[EntityProperties.ActiveEffects] = EntityEffects(linkedMapOf(effectId to effect))
        stack.properties[EntityProperties.Equipment] = EntityEquipment(linkedMapOf(EquipmentSlotId("mainhand") to item))
        val copied = stack.copy()
        val copiedSlots = assertIs<ComponentPatchEntry.SetValue>(copied.components.entries[componentId])
            .propertyValue.get(PropertyTypes.ItemSlots)
        copiedSlots[0]!!.count = 9
        copied.properties.require(EntityProperties.Attributes).entries.getValue(attributeId)
            .modifiers.getValue(modifierId).amount = 8.0
        copied.properties.require(EntityProperties.ActiveEffects).entries.getValue(effectId).hiddenEffect!!.duration =
            50
        copied.properties.require(EntityProperties.Equipment).slots.values.single()!!.count = 11
        assertEquals(3, item.count)
        assertEquals(2.0, modifier.amount)
        assertEquals(10, hidden.duration)
        assertEquals(9, copiedSlots[0]!!.count)
        assertEquals(10, effect.duration)
    }

    @Test
    fun entityTraversalUsesIdentityAndAnIterativePath() {
        fun entity() = Entity(
            EntityTypeId("test:entity"), Uuid.fromLongs(0, 1), EntityVector3d.ZERO,
            EntityVector3d.ZERO, EntityRotation.ZERO, mutableListOf()
        )

        val root = entity()
        var tail = root
        repeat(2048) { tail = entity().also { child -> tail.passengers!!.add(child) } }
        assertEquals(2049, root.allEntities().count())
        val other = entity()
        assertNotEquals(root, other)
        val parent = entity().also { it.passengers = mutableListOf(other, other) }
        assertEquals(listOf(parent, other, other), parent.allEntities().toList())
        tail.passengers!!.add(root)
        assertFailsWith<IllegalArgumentException> { root.allEntities().count() }
    }

    private class CountedValue(val id: Int, val comparisons: IntArray) {
        override fun hashCode(): Int = id
        override fun equals(other: Any?): Boolean {
            comparisons[0]++
            return other is CountedValue && id == other.id
        }
    }
}
