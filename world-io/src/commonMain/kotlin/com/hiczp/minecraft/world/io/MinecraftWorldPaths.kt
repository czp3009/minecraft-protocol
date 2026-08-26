package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.RegionPosition
import okio.Path

internal enum class RegionStorageDirectory(val directoryName: String) {
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

    class Custom(
        val namespace: String,
        pathSegments: List<String>,
    ) : DimensionDirectory {
        private val storedPathSegments = pathSegments.toList()

        val pathSegments: List<String>
            get() = storedPathSegments.toList()

        constructor(namespace: String, path: String) :
                this(namespace, parseDimensionPath(path))

        init {
            require(
                namespace.matches(DIMENSION_NAMESPACE_PATTERN) &&
                        namespace != "." &&
                        namespace != ".."
            ) {
                "Invalid dimension namespace: $namespace"
            }
            require(storedPathSegments.isNotEmpty())
            require(
                storedPathSegments.all {
                    it.matches(DIMENSION_PATH_SEGMENT_PATTERN) &&
                            it != "." && it != ".."
                },
            ) {
                "Invalid dimension path: ${storedPathSegments.joinToString("/")}"
            }
        }

        operator fun component1(): String = namespace

        operator fun component2(): List<String> = pathSegments

        fun copy(
            namespace: String = this.namespace,
            pathSegments: List<String> = storedPathSegments,
        ): Custom = Custom(namespace, pathSegments)

        override fun equals(other: Any?): Boolean =
            this === other ||
                    other is Custom &&
                    namespace == other.namespace &&
                    storedPathSegments == other.storedPathSegments

        override fun hashCode(): Int =
            31 * namespace.hashCode() + storedPathSegments.hashCode()

        override fun toString(): String =
            "Custom(namespace=$namespace, pathSegments=$storedPathSegments)"
    }
}

/**
 * Canonical paths inside a Java Edition world directory.
 */
class MinecraftWorldPaths(
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

    fun dimension(dimensionDirectory: DimensionDirectory): Path = when (dimensionDirectory) {
        DimensionDirectory.Overworld ->
            root / "dimensions" / "minecraft" / "overworld"

        DimensionDirectory.Nether ->
            root / "dimensions" / "minecraft" / "the_nether"

        DimensionDirectory.End ->
            root / "dimensions" / "minecraft" / "the_end"

        DimensionDirectory.LegacyOverworld -> root
        DimensionDirectory.LegacyNether -> root / "DIM-1"
        DimensionDirectory.LegacyEnd -> root / "DIM1"
        is DimensionDirectory.Custom -> dimensionDirectory.pathSegments.fold(
            root / "dimensions" / dimensionDirectory.namespace,
        ) { path, segment -> path / segment }
    }

    internal fun regionDirectory(
        regionStorageDirectory: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path = dimension(dimensionDirectory) / regionStorageDirectory.directoryName

    internal fun regionFile(
        regionPosition: RegionPosition,
        regionStorageDirectory: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path = regionDirectory(regionStorageDirectory, dimensionDirectory) /
            "r.${regionPosition.x}.${regionPosition.z}.mca"

    internal fun externalChunk(
        chunkPosition: ChunkPosition,
        regionStorageDirectory: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path = regionDirectory(regionStorageDirectory, dimensionDirectory) /
            "c.${chunkPosition.x}.${chunkPosition.z}.mcc"

    fun playerData(playerUuid: String): Path =
        root / "players" / "data" /
                "${validatePlayerStorageKey(playerUuid)}.dat"

    fun previousPlayerData(playerUuid: String): Path =
        root / "players" / "data" /
                "${validatePlayerStorageKey(playerUuid)}.dat_old"

    fun advancement(playerUuid: String): Path =
        root / "players" / "advancements" /
                "${validatePlayerStorageKey(playerUuid)}.json"

    fun statistics(playerUuid: String): Path =
        root / "players" / "stats" /
                "${validatePlayerStorageKey(playerUuid)}.json"

    fun legacyPlayerData(playerUuid: String): Path =
        root / "playerdata" / "${validatePlayerStorageKey(playerUuid)}.dat"

    fun legacyAdvancement(playerUuid: String): Path =
        root / "advancements" /
                "${validatePlayerStorageKey(playerUuid)}.json"

    fun legacyStatistics(playerUuid: String): Path =
        root / "stats" / "${validatePlayerStorageKey(playerUuid)}.json"

    fun savedDataDirectory(
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path = dimension(dimensionDirectory) / "data"

    fun savedData(
        identifier: String,
        dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    ): Path {
        val storedIdentifier = parseStoredIdentifier(identifier)
        val parent = storedIdentifier.pathSegments.dropLast(1).fold(
            savedDataDirectory(dimensionDirectory) / storedIdentifier.namespace,
        ) { path, segment -> path / segment }
        return parent / "${storedIdentifier.pathSegments.last()}.dat"
    }
}

private fun parseDimensionPath(path: String): List<String> {
    val segments = path.split('/')
    require(segments.isNotEmpty() && segments.none(String::isEmpty)) {
        "Invalid dimension path: $path"
    }
    return segments
}

private fun validatePlayerStorageKey(value: String): String {
    require(value.matches(PLAYER_STORAGE_KEY_PATTERN)) {
        "Invalid player storage key: $value"
    }
    return value
}

private fun parseStoredIdentifier(value: String): StoredIdentifier {
    val separatorIndex = value.indexOf(':')
    require(separatorIndex == value.lastIndexOf(':')) {
        "Invalid saved-data identifier: $value"
    }
    val namespace = if (separatorIndex < 0) {
        DEFAULT_NAMESPACE
    } else {
        value.substring(0, separatorIndex)
    }
    val path = if (separatorIndex < 0) {
        value
    } else {
        value.substring(separatorIndex + 1)
    }
    val segments = path.split('/')
    require(
        namespace.matches(STORED_IDENTIFIER_NAMESPACE_PATTERN) &&
                namespace != "." && namespace != ".." &&
                segments.isNotEmpty() &&
                segments.all {
                    it.matches(STORED_IDENTIFIER_SEGMENT_PATTERN) &&
                            it != "." && it != ".."
                },
    ) {
        "Invalid saved-data identifier: $value"
    }
    return StoredIdentifier(namespace, segments)
}

private data class StoredIdentifier(
    val namespace: String,
    val pathSegments: List<String>,
)

private val DIMENSION_NAMESPACE_PATTERN = Regex("[a-z0-9._-]+")
private val DIMENSION_PATH_SEGMENT_PATTERN = Regex("[a-z0-9._-]+")
private val PLAYER_STORAGE_KEY_PATTERN = Regex("[A-Za-z0-9_-]+")
private val STORED_IDENTIFIER_NAMESPACE_PATTERN = Regex("[a-z0-9._-]+")
private val STORED_IDENTIFIER_SEGMENT_PATTERN = Regex("[a-z0-9._-]+")
private const val DEFAULT_NAMESPACE = "minecraft"
