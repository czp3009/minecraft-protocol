package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.RegionPosition
import okio.Path.Companion.toPath
import kotlin.test.*

class MinecraftWorldPathsTest {
    @Test
    fun constructsCurrentAndExplicitLegacyPaths() {
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
                DimensionDirectory.Nether,
            ).portableString(),
        )
        assertEquals(
            "world/DIM-1/region",
            minecraftWorldPaths.regionDirectory(
                dimensionDirectory = DimensionDirectory.LegacyNether,
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/example/moons/blue/data/minecraft/maps/map_1.dat",
            minecraftWorldPaths.savedData(
                identifier = "maps/map_1",
                savedDataScope = SavedDataScope.Dimension(
                    DimensionDirectory.Custom(
                        namespace = "example",
                        path = "moons/blue",
                    ),
                ),
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/overworld/data/example/state/value.dat",
            minecraftWorldPaths.savedData("example:state/value", SavedDataScope.Dimension(DimensionDirectory.Overworld))
                .portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_end/poi/r.1.-2.mca",
            minecraftWorldPaths.regionFile(
                RegionPosition(1, -2),
                RegionStorageDirectory.POINTS_OF_INTEREST,
                DimensionDirectory.End,
            ).portableString(),
        )
        assertEquals(
            "world/region",
            minecraftWorldPaths.regionDirectory(dimensionDirectory = DimensionDirectory.LegacyOverworld).portableString()
        )
        assertEquals("world/DIM1", minecraftWorldPaths.dimension(DimensionDirectory.LegacyEnd).portableString())
        assertEquals(
            minecraftWorldPaths.dimension(DimensionDirectory.Overworld),
            minecraftWorldPaths.dimension(DimensionDirectory.Custom("minecraft", "overworld")),
        )
        assertEquals(
            minecraftWorldPaths.dimension(DimensionDirectory.Nether),
            minecraftWorldPaths.dimension(DimensionDirectory.Custom("minecraft", "the_nether")),
        )
        assertEquals(
            minecraftWorldPaths.dimension(DimensionDirectory.End),
            minecraftWorldPaths.dimension(DimensionDirectory.Custom("minecraft", "the_end")),
        )
        assertEquals("world/players/data/player.dat", minecraftWorldPaths.playerData("player").portableString())
        assertEquals("world/players/data/player.dat_old", minecraftWorldPaths.previousPlayerData("player").portableString())
        assertEquals("world/players/advancements/player.json", minecraftWorldPaths.advancement("player").portableString())
        assertEquals("world/players/stats/player.json", minecraftWorldPaths.statistics("player").portableString())
        assertEquals("world/playerdata/player.dat", minecraftWorldPaths.legacyPlayerData("player").portableString())
        assertEquals("world/advancements/player.json", minecraftWorldPaths.legacyAdvancement("player").portableString())
        assertEquals("world/stats/player.json", minecraftWorldPaths.legacyStatistics("player").portableString())
    }

    @Test
    fun customDimensionsAreValuesAndDefensivelyOwnTheirSegments() {
        val input = mutableListOf("moons", "blue")
        val dimensionDirectory = DimensionDirectory.Custom("example", input)
        input[0] = "changed"
        val firstView = dimensionDirectory.pathSegments
        val secondView = dimensionDirectory.pathSegments

        assertEquals(listOf("moons", "blue"), firstView)
        assertNotSame(firstView, secondView)
        assertEquals(
            dimensionDirectory,
            DimensionDirectory.Custom("example", "moons/blue"),
        )
        assertEquals(dimensionDirectory.hashCode(), dimensionDirectory.copy().hashCode())
        assertEquals("example", dimensionDirectory.component1())
        assertEquals(listOf("moons", "blue"), dimensionDirectory.component2())
        assertTrue(dimensionDirectory.toString().contains("example"))
    }

    @Test
    fun rejectsPathTraversalAndInvalidStorageKeys() {
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("example", "../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("Example", "valid")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("example", "")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("example", "two//parts")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("example", "Upper")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("../escape", SavedDataScope.WorldRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("Example:value", SavedDataScope.WorldRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("example:value:extra", SavedDataScope.WorldRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("", SavedDataScope.WorldRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData(":value", SavedDataScope.WorldRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("example:", SavedDataScope.WorldRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("example:a//b", SavedDataScope.WorldRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).playerData("../player")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).statistics("")
        }
    }
}

private fun okio.Path.portableString(): String =
    toString().replace('\\', '/')
