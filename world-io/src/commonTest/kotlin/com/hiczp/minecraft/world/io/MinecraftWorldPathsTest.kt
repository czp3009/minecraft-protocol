package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.RegionPosition
import okio.Path.Companion.toPath
import kotlin.test.*

class MinecraftWorldPathsTest {
    @Test
    fun constructsCurrentAndExplicitLegacyPaths() {
        val paths = MinecraftWorldPaths("world".toPath())

        assertEquals("world/level.dat", paths.levelData.portableString())
        assertEquals("world/level.dat_old", paths.previousLevelData.portableString())
        assertEquals("world/session.lock", paths.sessionLock.portableString())
        assertEquals(
            "world/dimensions/minecraft/overworld/region/r.-1.2.mca",
            paths.regionFile(RegionPosition(-1, 2)).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_nether/entities/c.-33.65.mcc",
            paths.externalChunk(
                ChunkPosition(-33, 65),
                RegionStorageDirectory.ENTITIES,
                DimensionDirectory.Nether,
            ).portableString(),
        )
        assertEquals(
            "world/DIM-1/region",
            paths.regionDirectory(
                dimension = DimensionDirectory.LegacyNether,
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/example/moons/blue/data/minecraft/maps/map_1.dat",
            paths.savedData(
                identifier = "maps/map_1",
                dimension = DimensionDirectory.Custom(
                    namespace = "example",
                    path = "moons/blue",
                ),
            ).portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/overworld/data/example/state/value.dat",
            paths.savedData("example:state/value").portableString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_end/poi/r.1.-2.mca",
            paths.regionFile(
                RegionPosition(1, -2),
                RegionStorageDirectory.POINTS_OF_INTEREST,
                DimensionDirectory.End,
            ).portableString(),
        )
        assertEquals(
            "world/region",
            paths.regionDirectory(dimension = DimensionDirectory.LegacyOverworld).portableString()
        )
        assertEquals("world/DIM1", paths.dimension(DimensionDirectory.LegacyEnd).portableString())
        assertEquals("world/players/data/player.dat", paths.playerData("player").portableString())
        assertEquals("world/players/data/player.dat_old", paths.previousPlayerData("player").portableString())
        assertEquals("world/players/advancements/player.json", paths.advancement("player").portableString())
        assertEquals("world/players/stats/player.json", paths.statistics("player").portableString())
        assertEquals("world/playerdata/player.dat", paths.legacyPlayerData("player").portableString())
        assertEquals("world/advancements/player.json", paths.legacyAdvancement("player").portableString())
        assertEquals("world/stats/player.json", paths.legacyStatistics("player").portableString())
    }

    @Test
    fun customDimensionsAreValuesAndDefensivelyOwnTheirSegments() {
        val input = mutableListOf("moons", "blue")
        val dimension = DimensionDirectory.Custom("example", input)
        input[0] = "changed"
        val firstView = dimension.pathSegments
        val secondView = dimension.pathSegments

        assertEquals(listOf("moons", "blue"), firstView)
        assertNotSame(firstView, secondView)
        assertEquals(
            dimension,
            DimensionDirectory.Custom("example", "moons/blue"),
        )
        assertEquals(dimension.hashCode(), dimension.copy().hashCode())
        assertEquals("example", dimension.component1())
        assertEquals(listOf("moons", "blue"), dimension.component2())
        assertTrue(dimension.toString().contains("example"))
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
            MinecraftWorldPaths("world".toPath()).savedData("../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("Example:value")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("example:value:extra")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData(":value")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("example:")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftWorldPaths("world".toPath()).savedData("example:a//b")
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
