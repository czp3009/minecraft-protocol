package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NbtComponentMappingTest {
    private val componentId = ComponentId("minecraft:counter")
    private val path = NbtPropertyPath(NbtPropertyScope("component"), componentId.value)
    private val readMappings = NbtPropertyReadMappings(mapOf(path to NbtPropertyReader { nbtTag, _ ->
        PropertyValue(PropertyTypes.Long, assertIs<NbtInt>(nbtTag).value.toLong())
    }))
    private val writeMappings = NbtPropertyWriteMappings(
        fields = mapOf(path to NbtPropertyWriter { propertyValue, _ ->
            NbtInt(propertyValue.get(PropertyTypes.Long).toInt())
        }),
        types = NbtPropertyWriters.types,
    )

    @Test
    fun componentMapsAndPatchesUseCanonicalNamesForTheSameSemanticMapping() {
        val input = NbtCompound(mapOf("counter" to NbtInt(7)))
        val components = NbtPropertyReaders.components.read(input, readMappings)
        val patch = NbtPropertyReaders.componentPatch.read(input, readMappings)
        assertEquals(7L, components.get(PropertyTypes.Components).entries.getValue(componentId).get(PropertyTypes.Long))
        val entry = assertIs<ComponentPatchEntry.SetValue>(patch.get(PropertyTypes.ComponentPatch).entries[componentId])
        assertEquals(7L, entry.propertyValue.get(PropertyTypes.Long))
        val expected = NbtCompound(mapOf(componentId.value to NbtInt(7)))
        assertEquals(expected, writeMappings.writeValue(components))
        assertEquals(expected, writeMappings.writeValue(patch))
    }

    @Test
    fun aliasesCannotSilentlyOverwriteAComponentOrItsPatch() {
        val aliases = NbtCompound(linkedMapOf("counter" to NbtInt(1), componentId.value to NbtInt(2)))
        assertFailsWith<IllegalArgumentException> { NbtPropertyReaders.components.read(aliases, readMappings) }
        assertFailsWith<IllegalArgumentException> { NbtPropertyReaders.componentPatch.read(aliases, readMappings) }
        val removedAndSet = NbtCompound(mapOf("!counter" to NbtCompound(emptyMap()), componentId.value to NbtInt(2)))
        assertFailsWith<IllegalArgumentException> {
            NbtPropertyReaders.componentPatch.read(
                removedAndSet,
                readMappings
            )
        }
    }
}
