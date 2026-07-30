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

    fun playerData(playerUuid: String): Path = Path(
        root,
        "players",
        "data",
        "${validatePlayerStorageKey(playerUuid)}.dat",
    )

    fun advancement(playerUuid: String): Path = Path(
        root,
        "players",
        "advancements",
        "${validatePlayerStorageKey(playerUuid)}.json",
    )

    fun statistics(playerUuid: String): Path = Path(
        root,
        "players",
        "stats",
        "${validatePlayerStorageKey(playerUuid)}.json",
    )

    fun legacyPlayerData(playerUuid: String): Path = Path(
        root,
        "playerdata",
        "${validatePlayerStorageKey(playerUuid)}.dat",
    )

    fun legacyAdvancement(playerUuid: String): Path = Path(
        root,
        "advancements",
        "${validatePlayerStorageKey(playerUuid)}.json",
    )

    fun legacyStatistics(playerUuid: String): Path = Path(
        root,
        "stats",
        "${validatePlayerStorageKey(playerUuid)}.json",
    )
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

private val DIMENSION_NAMESPACE_PATTERN = Regex("[a-z0-9._-]+")
private val DIMENSION_PATH_SEGMENT_PATTERN = Regex("[a-z0-9._-]+")
private val PLAYER_STORAGE_KEY_PATTERN = Regex("[A-Za-z0-9_-]+")
