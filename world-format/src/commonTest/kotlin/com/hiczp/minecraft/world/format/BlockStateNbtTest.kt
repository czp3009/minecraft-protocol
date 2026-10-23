package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.SnbtFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BlockStateNbtTest {
    private val blockId = BlockId("example:machine")
    private val defaults = StateProperties(mapOf("facing" to "north", "powered" to "false"))
    private val decoder = BlockStateNbtDecoder { requested ->
        require(requested == blockId)
        defaults
    }

    @Test
    fun compactAndPartialStatesUseDefinitionDefaults() {
        assertEquals(BlockState(blockId, defaults), decoder.decode(NbtString(blockId.value)))
        assertEquals(
            BlockState(blockId, defaults),
            decoder.decode(SnbtFormat.decodeTagFromString("{id:'example:machine'}"))
        )
        val partial = SnbtFormat.decodeTagFromString("{id:'example:machine',properties:{powered:'true'}}")
        val blockState = decoder.decode(partial)
        assertEquals(mapOf("facing" to "north", "powered" to "true"), blockState.properties.asMap())
        val encoded = BlockStateNbtEncoder().encode(blockState)
        assertEquals(setOf("id", "properties"), encoded.value.keys)
        assertEquals(blockState, decoder.decode(encoded))
    }

    @Test
    fun unknownDefinitionsAndMissingIdentifiersAreRejected() {
        assertFails { decoder.decode(NbtString("example:missing")) }
        assertFails { decoder.decode(NbtCompound(emptyMap())) }
    }
}
