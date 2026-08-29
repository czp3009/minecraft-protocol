package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.datapack.DataPackResourceId
import com.hiczp.minecraft.world.format.datapack.DataPackResourcePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NamespacedValuesTest {
    @Test
    fun worldIdsParseCanonicalAndDefaultNamespaces() {
        val dimensionId = DimensionId("moons/blue", "example")
        val dimensionTypeId = DimensionTypeId("overworld")
        val savedDataId = SavedDataId("maps/map_1", "example")

        assertEquals(DimensionId("overworld"), DimensionId.parse("overworld"))
        assertEquals(dimensionId, DimensionId.parse(dimensionId.toString()))
        assertEquals(DimensionTypeId("overworld"), DimensionTypeId.parse("overworld"))
        assertEquals(dimensionTypeId, DimensionTypeId.parse(dimensionTypeId.toString()))
        assertEquals(SavedDataId("world_gen_settings"), SavedDataId.parse("world_gen_settings"))
        assertEquals(savedDataId, SavedDataId.parse(savedDataId.toString()))
    }

    @Test
    fun worldIdSerializersUsePrimitiveStringsIncludingMapKeys() {
        val nbtFormat = NbtFormat()

        assertEquals(
            NbtString("example:moon"),
            nbtFormat.encodeToNbtTag(DimensionId.serializer(), DimensionId("moon", "example")),
        )
        assertEquals(
            NbtString("minecraft:overworld"),
            nbtFormat.encodeToNbtTag(DimensionTypeId.serializer(), DimensionTypeId("overworld")),
        )
        assertEquals(
            NbtString("example:state/value"),
            nbtFormat.encodeToNbtTag(SavedDataId.serializer(), SavedDataId("state/value", "example")),
        )
    }

    @Test
    fun dataPackResourceValuesExposeMatchingParseOperations() {
        assertEquals(
            DataPackResourceId("minecraft", "worldgen/biome"),
            DataPackResourceId.parse("worldgen/biome"),
        )
        assertEquals(
            DataPackResourcePath("example", "worldgen/biome/moon.json"),
            DataPackResourcePath.parse("example:worldgen/biome/moon.json"),
        )
        assertEquals(
            DataPackResourcePath("minecraft", "worldgen/biome/moon.json"),
            DataPackResourcePath.parse("worldgen/biome/moon.json"),
        )
        assertEquals(
            DataPackResourceId("example", "moon"),
            DataPackResourceId.parse(DataPackResourceId("example", "moon").toString()),
        )
        assertEquals(
            DataPackResourcePath("example", "tags/block/moon.json"),
            DataPackResourcePath.parse(DataPackResourcePath("example", "tags/block/moon.json").toString()),
        )
    }

    @Test
    fun namespacedValuesRejectEmptyComponentsExtraSeparatorsAndInvalidCharacters() {
        listOf<() -> Any>(
            { DimensionId.parse(":overworld") },
            { DimensionId.parse("minecraft:") },
            { DimensionId.parse("minecraft:over:world") },
            { DimensionTypeId.parse("Minecraft:overworld") },
            { SavedDataId.parse("minecraft:state value") },
            { DataPackResourceId.parse("minecraft:") },
            { DataPackResourcePath.parse("minecraft:two:paths") },
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { invalid() }
        }
    }
}
