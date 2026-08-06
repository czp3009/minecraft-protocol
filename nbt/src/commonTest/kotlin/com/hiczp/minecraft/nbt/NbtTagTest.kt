package com.hiczp.minecraft.nbt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NbtTagTest {
    @Test
    fun `logical lists allow mixed tags but reject END`() {
        assertEquals(
            NbtList(listOf(NbtInt(1), NbtString("two"))),
            NbtList(listOf(NbtInt(1), NbtString("two"))),
        )
        assertFailsWith<IllegalArgumentException> {
            NbtList(listOf(NbtEnd))
        }
    }

    @Test
    fun `compounds reject END values`() {
        assertFailsWith<IllegalArgumentException> {
            NbtCompound(mapOf("end" to NbtEnd))
        }
    }

    @Test
    fun `containers and arrays take immutable snapshots`() {
        val bytes = byteArrayOf(1, 2)
        val ints = intArrayOf(3, 4)
        val longs = longArrayOf(5, 6)
        val list = mutableListOf<NbtTag>(NbtInt(7), NbtString("eight"))
        val compound = linkedMapOf<String, NbtTag>("value" to NbtInt(8))

        val byteTag = NbtByteArray(bytes)
        val intTag = NbtIntArray(ints)
        val longTag = NbtLongArray(longs)
        val listTag = NbtList(list)
        val compoundTag = NbtCompound(compound)

        bytes[0] = 9
        ints[0] = 9
        longs[0] = 9
        list[0] = NbtInt(9)
        compound["value"] = NbtInt(9)

        assertContentEquals(byteArrayOf(1, 2), byteTag.value)
        assertContentEquals(intArrayOf(3, 4), intTag.value)
        assertContentEquals(longArrayOf(5, 6), longTag.value)
        assertEquals(listOf(NbtInt(7), NbtString("eight")), listTag.value)
        assertEquals(mapOf("value" to NbtInt(8)), compoundTag.value)

        byteTag.value[0] = 10
        intTag.value[0] = 10
        longTag.value[0] = 10
        val returnedList = listTag.value
        if (returnedList is MutableList<*>) {
            @Suppress("UNCHECKED_CAST")
            (returnedList as MutableList<NbtTag>)[0] = NbtInt(10)
        }
        val returnedCompound = compoundTag.value
        if (returnedCompound is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (returnedCompound as MutableMap<String, NbtTag>)["value"] =
                NbtInt(10)
        }
        assertContentEquals(byteArrayOf(1, 2), byteTag.value)
        assertContentEquals(intArrayOf(3, 4), intTag.value)
        assertContentEquals(longArrayOf(5, 6), longTag.value)
        assertEquals(listOf(NbtInt(7), NbtString("eight")), listTag.value)
        assertEquals(mapOf("value" to NbtInt(8)), compoundTag.value)
    }
}
