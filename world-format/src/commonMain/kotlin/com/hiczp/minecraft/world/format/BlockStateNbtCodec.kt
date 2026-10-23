package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.NbtTag

/**
 * Decodes compact block identifiers and full `id`/`properties` compounds.
 * [defaultProperties] supplies the block's complete default state when the saved value omits some or all properties.
 * Bind a release- or world-scoped definition provider; the decoder never substitutes vanilla data implicitly.
 * Explicit properties remain open canonical strings, allowing caller-defined blocks and state properties.
 */
class BlockStateNbtDecoder(val defaultProperties: (BlockId) -> StateProperties) {
    fun decode(nbtTag: NbtTag): BlockState {
        val nbtCompound = if (nbtTag is NbtString) null else nbtTag.compound()
        val blockId = BlockId.parse(nbtCompound?.string("id") ?: nbtTag.string())
        val defaults = defaultProperties(blockId)
        val encodedProperties = nbtCompound?.optionalTag<NbtCompound>("properties")
        val properties = if (encodedProperties == null || encodedProperties.size == 0) defaults else StateProperties(
            buildMap {
                putAll(defaults.asMap())
                encodedProperties.forEachEntry { name, tag -> put(name, tag.string()) }
            },
        )
        return BlockState(blockId, properties)
    }
}

/** Encodes the full selected-release block-state form, preserving all properties without requiring default-state data. */
class BlockStateNbtEncoder {
    fun encode(blockState: BlockState): NbtCompound = NbtCompound(buildMap {
        put("id", NbtString(blockState.blockId.toString()))
        if (blockState.properties.size != 0) {
            put(
                "properties",
                NbtCompound(blockState.properties.associate { (name, value) -> name to NbtString(value) })
            )
        }
    })
}
