package com.hiczp.minecraft.nbt

/** A named NBT value in the traditional binary root representation. */
data class NamedNbtTag(
    val name: String,
    val tag: NbtTag,
) {
    init {
        require(tag !== NbtEnd) { "A named NBT value cannot be TAG_End" }
    }
}

/**
 * A compound-root document as read and written by `NbtIo.read`/`write`.
 * Its binary root name is always empty and therefore is not model state.
 */
data class NbtDocument(val root: NbtCompound)
