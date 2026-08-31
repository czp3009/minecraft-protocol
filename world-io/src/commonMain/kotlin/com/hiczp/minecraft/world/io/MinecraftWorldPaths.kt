package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.RegionPosition
import com.hiczp.minecraft.world.format.SavedDataId
import okio.Path

internal enum class RegionStorageDirectory(val directoryName: String) {
    CHUNKS("region"),
    ENTITIES("entities"),
    POINTS_OF_INTEREST("poi"),
}

/**
 * Canonical paths inside a Java Edition world directory.
 */
data class MinecraftWorldPaths(
    val root: Path,
) {
    val dataPacksDirectory: Path
        get() = root / "datapacks"

    val levelData: Path
        get() = root / "level.dat"

    val previousLevelData: Path
        get() = root / "level.dat_old"

    val sessionLock: Path
        get() = root / "session.lock"

    internal val playerDataDirectory: Path
        get() = root / "players" / "data"

    fun dimension(dimensionId: DimensionId): Path {
        validateStorageNamespace(dimensionId.namespace, "dimension")
        return parseStoragePath(dimensionId.path, "dimension").fold(
            root / "dimensions" / dimensionId.namespace,
        ) { path, pathSegment -> path / pathSegment }
    }

    internal fun regionDirectory(
        regionStorageDirectory: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionId: DimensionId = DimensionId.Overworld,
    ): Path = dimension(dimensionId) / regionStorageDirectory.directoryName

    internal fun regionFile(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionId: DimensionId = DimensionId.Overworld,
    ): Path = regionDirectory(regionStorageDirectory, dimensionId) /
            "r.${regionPosition.x}.${regionPosition.z}.mca"

    internal fun externalChunk(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionId: DimensionId = DimensionId.Overworld,
    ): Path = regionDirectory(regionStorageDirectory, dimensionId) /
            "c.${chunkPosition.x}.${chunkPosition.z}.mcc"

    fun playerData(playerUuid: String): Path =
        playerDataDirectory /
                "${validatePlayerStorageKey(playerUuid)}.dat"

    fun previousPlayerData(playerUuid: String): Path =
        playerDataDirectory /
                "${validatePlayerStorageKey(playerUuid)}.dat_old"

    fun advancements(playerUuid: String): Path =
        root / "players" / "advancements" /
                "${validatePlayerStorageKey(playerUuid)}.json"

    fun statistics(playerUuid: String): Path =
        root / "players" / "stats" /
                "${validatePlayerStorageKey(playerUuid)}.json"

    fun savedDataDirectory(savedDataScope: SavedDataScope): Path = when (savedDataScope) {
        SavedDataScope.WorldRoot -> root / "data"
        is SavedDataScope.Dimension -> dimension(savedDataScope.dimensionId) / "data"
    }

    fun savedData(
        savedDataId: SavedDataId,
        savedDataScope: SavedDataScope,
    ): Path {
        validateStorageNamespace(savedDataId.namespace, "saved-data")
        val pathSegments = parseStoragePath(savedDataId.path, "saved-data")
        val parent = pathSegments.dropLast(1).fold(
            savedDataDirectory(savedDataScope) / savedDataId.namespace,
        ) { path, pathSegment -> path / pathSegment }
        return parent / "${pathSegments.last()}.dat"
    }
}

private fun validatePlayerStorageKey(value: String): String {
    require(isValidPlayerStorageKey(value)) {
        "Invalid player storage key: $value"
    }
    return value
}

internal fun parsePlayerDataFileName(name: String): String? {
    val playerUuid = when {
        name.endsWith(PLAYER_DATA_PREVIOUS_SUFFIX) -> name.removeSuffix(PLAYER_DATA_PREVIOUS_SUFFIX)
        name.endsWith(PLAYER_DATA_SUFFIX) -> name.removeSuffix(PLAYER_DATA_SUFFIX)
        else -> return null
    }
    return playerUuid.takeIf(::isValidPlayerStorageKey)
}

private fun isValidPlayerStorageKey(value: String): Boolean = value.matches(PLAYER_STORAGE_KEY_PATTERN)

private val PLAYER_STORAGE_KEY_PATTERN = Regex("[A-Za-z0-9_-]+")
private const val PLAYER_DATA_SUFFIX = ".dat"
private const val PLAYER_DATA_PREVIOUS_SUFFIX = ".dat_old"
