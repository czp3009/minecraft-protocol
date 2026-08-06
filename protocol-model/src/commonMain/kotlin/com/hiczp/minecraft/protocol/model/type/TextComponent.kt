package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.NbtTag
import kotlinx.serialization.Serializable

/** A protocol text component encoded as a no-name network NBT tag. */
@Serializable
data class TextComponent(val value: NbtTag) {
    companion object {
        fun literal(text: String): TextComponent = TextComponent(NbtString(text))
    }
}
