package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.io.files.Path

enum class RegionStorageDirectory(val directoryName: String) {
    CHUNKS("region"),
    ENTITIES("entities"),
    POINTS_OF_INTEREST("poi"),
}

sealed interface DimensionDirectory {
    data object Overworld : DimensionDirectory
    data object Nether : DimensionDirectory
    data object End : DimensionDirectory
    data object LegacyOverworld : DimensionDirectory
    data object LegacyNether : DimensionDirectory
    data object LegacyEnd : DimensionDirectory

    data class Custom(
        val namespace: String,
        val pathSegments: List<String>,
    ) : DimensionDirectory {
        constructor(namespace: String, path: String) :
                this(namespace, path.split('/').filter(String::isNotEmpty))

        init {
            require(namespace.isNotBlank())
            require('/' !in namespace && '\\' !in namespace)
            require(pathSegments.isNotEmpty())
            require(
                pathSegments.all {
                    it.isNotBlank() && '/' !in it && '\\' !in it &&
                            it != "." && it != ".."
                },
            )
        }
    }
}

/**
 * Canonical paths inside a Java Edition world directory.
 */
class MinecraftWorldPaths(
    val root: Path,
) {
    val levelData: Path
        get() = Path(root, "level.dat")

    val previousLevelData: Path
        get() = Path(root, "level.dat_old")

    val sessionLock: Path
        get() = Path(root, "session.lock")

    fun dimension(dimension: DimensionDirectory): Path = when (dimension) {
        DimensionDirectory.Overworld ->
            Path(root, "dimensions", "minecraft", "overworld")

        DimensionDirectory.Nether ->
            Path(root, "dimensions", "minecraft", "the_nether")

        DimensionDirectory.End ->
            Path(root, "dimensions", "minecraft", "the_end")

        DimensionDirectory.LegacyOverworld -> root
        DimensionDirectory.LegacyNether -> Path(root, "DIM-1")
        DimensionDirectory.LegacyEnd -> Path(root, "DIM1")
        is DimensionDirectory.Custom -> Path(
            Path(root, "dimensions", dimension.namespace),
            *dimension.pathSegments.toTypedArray(),
        )
    }

    fun regionDirectory(
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path = Path(dimension(dimension), storage.directoryName)

    fun regionFile(
        position: RegionPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path = Path(
        regionDirectory(storage, dimension),
        "r.${position.x}.${position.z}.mca",
    )

    fun externalChunk(
        position: ChunkPosition,
        storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimension: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path = Path(
        regionDirectory(storage, dimension),
        "c.${position.x}.${position.z}.mcc",
    )

    fun playerData(playerUuid: String): Path =
        Path(root, "players", "data", "$playerUuid.dat")

    fun advancement(playerUuid: String): Path =
        Path(root, "players", "advancements", "$playerUuid.json")

    fun statistics(playerUuid: String): Path =
        Path(root, "players", "stats", "$playerUuid.json")

    fun legacyPlayerData(playerUuid: String): Path =
        Path(root, "playerdata", "$playerUuid.dat")

    fun legacyAdvancement(playerUuid: String): Path =
        Path(root, "advancements", "$playerUuid.json")

    fun legacyStatistics(playerUuid: String): Path =
        Path(root, "stats", "$playerUuid.json")
}
