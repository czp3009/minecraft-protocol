package com.hiczp.minecraft.demo.nbtreader

import com.hiczp.minecraft.nbt.*
import kotlin.test.*

class NbtTreePrinterTest {
    @Test
    fun showsTypesInputOrderAndMixedListIndices() {
        val nbtCompound = NbtCompound(
            linkedMapOf(
                "z" to NbtByte(1),
                "a" to NbtList(listOf(NbtString("hello"), NbtLong(2))),
                "empty" to NbtCompound(emptyMap()),
            ),
        )
        val lines = mutableListOf<String>()
        NbtTreePrinter(output = lines::add).print(nbtCompound)
        assertEquals(
            listOf(
                "(root): Compound (3 entries)",
                "  \"z\": Byte = 1",
                "  \"a\": List (2 elements)",
                "    [0]: String = \"hello\"",
                "    [1]: Long = 2",
                "  \"empty\": Compound (0 entries)",
            ),
            lines,
        )
    }

    @Test
    fun boundsChildrenArraysAndDepthAndMarksOmissions() {
        val nbtList = NbtList(
            listOf(
                NbtCompound(mapOf("deep" to NbtInt(99))),
                NbtIntArray(intArrayOf(10, 20, 30)),
                NbtInt(999),
            ),
        )
        val lines = mutableListOf<String>()
        NbtTreePrinter(depth = 1, limit = 2, output = lines::add).print(nbtList)
        assertEquals(
            listOf(
                "(root): List (3 elements)",
                "  [0]: Compound (1 entries)",
                "    ... 1 entries hidden (depth limit)",
                "  [1]: IntArray[3] = [10, 20, ... 1 more]",
                "  ... 1 more entries (item limit)",
            ),
            lines,
        )
        assertEquals(3, nbtList.size)
    }

    @Test
    fun fullOutputDisablesAllPreviewLimits() {
        val nbtCompound = NbtCompound(
            mapOf(
                "nested" to NbtList(listOf(NbtByteArray(byteArrayOf(1, 2)), NbtLongArray(longArrayOf(3, 4)))),
                "long" to NbtString("x".repeat(200)),
            ),
        )
        val lines = mutableListOf<String>()
        NbtTreePrinter(depth = 0, limit = 1, full = true, output = lines::add).print(nbtCompound)
        val output = lines.joinToString("\n")
        assertContains(output, "ByteArray[2] = [1, 2]")
        assertContains(output, "LongArray[2] = [3, 4]")
        assertContains(output, "x".repeat(200))
        assertFalse(output.contains("..."))
    }

    @Test
    fun escapesControlCharactersAndQuotesEmptyKeysWithoutRequiringSnbtRepresentability() {
        val lines = mutableListOf<String>()
        NbtTreePrinter(output = lines::add).print(
            NbtCompound(mapOf("" to NbtDouble(Double.NaN), "line\nkey" to NbtString("\u001b[31m\ntext"))),
        )
        assertEquals("  \"\": Double = NaN", lines[1])
        assertEquals("  \"line\\nkey\": String = \"\\x1B[31m\\ntext\"", lines[2])
        assertFalse(lines.any { it.contains('\u001b') || it.contains('\n') })
        assertContains("x".repeat(200).quotedPreview(false), "40 more UTF-16 code units")
    }

    @Test
    fun rejectsInvalidPreviewSettings() {
        assertFailsWith<IllegalArgumentException> { NbtTreePrinter(depth = -1, output = {}) }
        assertFailsWith<IllegalArgumentException> { NbtTreePrinter(limit = 0, output = {}) }
    }

    @Test
    fun preservesNumericTypesAndSignedValues() {
        val nbtList = NbtList(
            listOf(
                NbtByte(Byte.MIN_VALUE), NbtShort(Short.MIN_VALUE), NbtInt(Int.MIN_VALUE), NbtLong(Long.MIN_VALUE),
                NbtFloat(-0.0f), NbtDouble(-2.5), NbtFloat(Float.POSITIVE_INFINITY),
            ),
        )
        val lines = mutableListOf<String>()
        NbtTreePrinter(output = lines::add).print(nbtList)
        assertEquals(
            listOf(
                "(root): List (7 elements)",
                "  [0]: Byte = -128",
                "  [1]: Short = -32768",
                "  [2]: Int = -2147483648",
                "  [3]: Long = -9223372036854775808",
                "  [4]: Float = -0.0",
                "  [5]: Double = -2.5",
                "  [6]: Float = Infinity",
            ),
            lines,
        )
    }

    @Test
    fun emptyContainersAndArraysHaveNoOmissionMarker() {
        val lines = mutableListOf<String>()
        val nbtTreePrinter = NbtTreePrinter(depth = 0, limit = 1, output = lines::add)
        listOf(
            NbtCompound(emptyMap()), NbtList(emptyList()), NbtByteArray(byteArrayOf()),
            NbtIntArray(intArrayOf()), NbtLongArray(longArrayOf()),
        ).forEach { nbtTreePrinter.print(it) }
        assertEquals(
            listOf(
                "(root): Compound (0 entries)", "(root): List (0 elements)", "(root): ByteArray[0] = []",
                "(root): IntArray[0] = []", "(root): LongArray[0] = []",
            ),
            lines,
        )
    }

    @Test
    fun exactPreviewLimitsDoNotMarkCompleteValuesAsTruncated() {
        val lines = mutableListOf<String>()
        NbtTreePrinter(depth = 1, limit = 2, output = lines::add).print(
            NbtCompound(mapOf("first" to NbtIntArray(intArrayOf(1, 2)), "second" to NbtString("x".repeat(160)))),
        )
        assertEquals(3, lines.size)
        assertFalse(lines.any { it.contains("...") })
    }

    @Test
    fun stringPreviewsDoNotSplitSupplementaryUnicodeCharacters() {
        val prefix = "x".repeat(159)
        val text = "$prefix\uD83D\uDE00"
        assertEquals("\"$prefix\" ... (2 more UTF-16 code units)", text.quotedPreview(false))
        assertEquals("\"$text\"", text.quotedPreview(true))
    }
}
