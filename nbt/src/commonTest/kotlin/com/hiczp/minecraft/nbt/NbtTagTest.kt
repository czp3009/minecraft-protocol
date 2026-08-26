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
        val byteArray = byteArrayOf(1, 2)
        val intArray = intArrayOf(3, 4)
        val longArray = longArrayOf(5, 6)
        val mutableList = mutableListOf<NbtTag>(NbtInt(7), NbtString("eight"))
        val linkedHashMap = linkedMapOf<String, NbtTag>("value" to NbtInt(8))

        val nbtByteArray = NbtByteArray(byteArray)
        val nbtIntArray = NbtIntArray(intArray)
        val nbtLongArray = NbtLongArray(longArray)
        val nbtList = NbtList(mutableList)
        val nbtCompound = NbtCompound(linkedHashMap)

        byteArray[0] = 9
        intArray[0] = 9
        longArray[0] = 9
        mutableList[0] = NbtInt(9)
        linkedHashMap["value"] = NbtInt(9)

        assertContentEquals(byteArrayOf(1, 2), nbtByteArray.value)
        assertContentEquals(intArrayOf(3, 4), nbtIntArray.value)
        assertContentEquals(longArrayOf(5, 6), nbtLongArray.value)
        assertEquals(listOf(NbtInt(7), NbtString("eight")), nbtList.value)
        assertEquals(mapOf("value" to NbtInt(8)), nbtCompound.value)

        nbtByteArray.value[0] = 10
        nbtIntArray.value[0] = 10
        nbtLongArray.value[0] = 10
        val returnedList = nbtList.value
        if (returnedList is MutableList<*>) {
            @Suppress("UNCHECKED_CAST")
            (returnedList as MutableList<NbtTag>)[0] = NbtInt(10)
        }
        val returnedCompound = nbtCompound.value
        if (returnedCompound is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (returnedCompound as MutableMap<String, NbtTag>)["value"] =
                NbtInt(10)
        }
        assertContentEquals(byteArrayOf(1, 2), nbtByteArray.value)
        assertContentEquals(intArrayOf(3, 4), nbtIntArray.value)
        assertContentEquals(longArrayOf(5, 6), nbtLongArray.value)
        assertEquals(listOf(NbtInt(7), NbtString("eight")), nbtList.value)
        assertEquals(mapOf("value" to NbtInt(8)), nbtCompound.value)
    }
}
