package com.hiczp.minecraft.demo.nbtreader

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.SnbtFormat

/** Emits a typed, indented tree in input order. Preview limits never alter the decoded NBT value. */
internal class NbtTreePrinter(
    private val depth: Int = 3,
    private val limit: Int = 16,
    private val full: Boolean = false,
    private val output: (String) -> Unit,
) {
    init {
        require(depth >= 0) { "Tree depth must be zero or greater" }
        require(limit > 0) { "Tree limit must be greater than zero" }
    }

    fun print(nbtTag: NbtTag) {
        print(nbtTag, "(root)", 0)
    }

    private fun print(nbtTag: NbtTag, label: String, level: Int) {
        val indent = "  ".repeat(level)
        val description = when (nbtTag) {
            NbtEnd -> "End"
            is NbtByte -> "Byte = ${nbtTag.value}"
            is NbtShort -> "Short = ${nbtTag.value}"
            is NbtInt -> "Int = ${nbtTag.value}"
            is NbtLong -> "Long = ${nbtTag.value}"
            is NbtFloat -> "Float = ${nbtTag.value}"
            is NbtDouble -> "Double = ${nbtTag.value}"
            is NbtString -> "String = ${nbtTag.value.quotedPreview(full)}"
            is NbtCompound -> "Compound (${nbtTag.size} entries)"
            is NbtList -> "List (${nbtTag.size} elements)"
            is NbtByteArray -> arrayPreview("ByteArray", nbtTag.size) { nbtTag[it].toString() }
            is NbtIntArray -> arrayPreview("IntArray", nbtTag.size) { nbtTag[it].toString() }
            is NbtLongArray -> arrayPreview("LongArray", nbtTag.size) { nbtTag[it].toString() }
        }
        output("$indent$label: $description")
        val size = when (nbtTag) {
            is NbtCompound -> nbtTag.size
            is NbtList -> nbtTag.size
            else -> return
        }
        if (size == 0) return
        if (!full && level >= depth) {
            output("$indent  ... $size entries hidden (depth limit)")
            return
        }
        val shown = if (full) size else minOf(size, limit)
        when (nbtTag) {
            is NbtCompound -> {
                var index = 0
                nbtTag.forEachEntry { name, child ->
                    if (index++ < shown) print(child, name.quotedPreview(full), level + 1)
                }
            }

            is NbtList -> repeat(shown) { print(nbtTag[it], "[$it]", level + 1) }
        }
        if (shown < size) output("$indent  ... ${size - shown} more entries (item limit)")
    }

    private fun arrayPreview(type: String, size: Int, element: (Int) -> String): String {
        val shown = if (full) size else minOf(size, limit)
        val values = (0 until shown).joinToString(", ") { element(it) }
        val remaining = if (shown < size) ", ... ${size - shown} more" else ""
        return "$type[$size] = [$values$remaining]"
    }
}

/** Uses the library's string escaping to display embedded newlines and ESC characters visibly. */
internal fun String.quotedPreview(full: Boolean): String {
    var shown = if (full) length else minOf(length, 160)
    if (shown in 1 until length && this[shown - 1].isHighSurrogate() && this[shown].isLowSurrogate()) shown--
    val quoted = SnbtFormat.encodeTagToString(NbtString(take(shown)))
    return if (shown < length) "$quoted ... (${length - shown} more UTF-16 code units)" else quoted
}
