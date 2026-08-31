package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.RegionPosition
import com.hiczp.minecraft.world.format.SavedDataId
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MinecraftWorldPathsTest {
    @Test
    fun constructsSelectedReleasePaths() {
        val minecraftWorldPaths = MinecraftWorldPaths("world".toPath())

        assertEquals("world/level.dat", minecraftWorldPaths.levelData.portableString())
        assertEquals("world/level.dat_old", minecraftWorldPaths.previousLevelData.portableString())
        assertEquals("world/session.lock", minecraftWorldPaths.sessionLock.portableString())
        assertEquals(
            "world/dimensions/minecraft/overworld/region/r.-1.2.mca",
            minecraftWorldPaths.regionFile(RegionPosition(-1, 2)).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_nether/entities/c.-33.65.mcc",
            minecraftWorldPaths.externalChunk(
                ChunkPosition(-33, 65),
                RegionStorageDirectory.ENTITIES,
                DimensionId.Nether,
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/example/moons/blue/data/minecraft/maps/map_1.dat",
            minecraftWorldPaths.savedData(
                savedDataId = SavedDataId("maps/map_1"),
                savedDataScope = SavedDataScope.Dimension(
                    DimensionId("moons/blue", namespace = "example"),
                ),
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/overworld/data/example/state/value.dat",
            minecraftWorldPaths.savedData(
                SavedDataId("state/value", namespace = "example"),
                SavedDataScope.Dimension(DimensionId.Overworld),
            ).portableString(),
        )
        assertEquals(
            "world/data/example/state/value.dat",
            minecraftWorldPaths.savedData(
                SavedDataId("state/value", namespace = "example"),
                SavedDataScope.WorldRoot,
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_end/poi/r.1.-2.mca",
            minecraftWorldPaths.regionFile(
                RegionPosition(1, -2),
                RegionStorageDirectory.POINTS_OF_INTEREST,
                DimensionId.End,
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/overworld",
            minecraftWorldPaths.dimension(DimensionId.Overworld).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_nether",
            minecraftWorldPaths.dimension(DimensionId.Nether).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_end",
            minecraftWorldPaths.dimension(DimensionId.End).portableString(),
        )
        assertEquals("world/players/data/player.dat", minecraftWorldPaths.playerData("player").portableString())
        assertEquals(
            "world/players/data/player.dat_old",
            minecraftWorldPaths.previousPlayerData("player").portableString()
        )
        assertEquals(
            "world/players/advancements/player.json",
            minecraftWorldPaths.advancements("player").portableString()
        )
        assertEquals("world/players/stats/player.json", minecraftWorldPaths.statistics("player").portableString())
    }

    @Test
    fun namespacedStorageIdsAreValuesWithMinecraftDefaults() {
        val dimensionId = DimensionId("moons/blue", namespace = "example")
        val savedDataId = SavedDataId("maps/map_1")

        assertEquals(dimensionId, DimensionId("moons/blue", namespace = "example"))
        assertEquals(dimensionId.hashCode(), dimensionId.copy().hashCode())
        assertEquals("example:moons/blue", dimensionId.toString())
        assertEquals("minecraft:maps/map_1", savedDataId.toString())
    }

    @Test
    fun rejectsPathTraversalAndInvalidStorageKeys() {
        val minecraftWorldPaths = MinecraftWorldPaths("world".toPath())

        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.dimension(DimensionId("../escape", namespace = "example"))
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionId("valid", namespace = "Example")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionId("", namespace = "example")
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.dimension(DimensionId("two//parts", namespace = "example"))
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionId("Upper", namespace = "example")
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.savedData(
                SavedDataId("../escape"),
                SavedDataScope.WorldRoot,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SavedDataId("value", namespace = "Example")
        }
        assertFailsWith<IllegalArgumentException> {
            SavedDataId("value:extra", namespace = "example")
        }
        assertFailsWith<IllegalArgumentException> {
            SavedDataId("")
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.savedData(
                SavedDataId("a//b", namespace = "example"),
                SavedDataScope.WorldRoot,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.dimension(DimensionId("valid", namespace = ".."))
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.playerData("../player")
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.statistics("")
        }
    }
}

private fun Path.portableString(): String =
    toString().replace('\\', '/')
