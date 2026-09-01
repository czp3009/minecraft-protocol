package com.hiczp.minecraft.demo.webmap

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

internal enum class WorldDirectorySource {
    ENVIRONMENT,
    PROJECT_DISCOVERY,
}

internal data class DiscoveredWorldDirectory(
    val path: Path,
    val source: WorldDirectorySource,
)

internal fun discoverWorldDirectory(
    fileSystem: FileSystem,
    currentWorkingDirectory: Path,
    explicitWorldDirectory: String?,
    minecraftVersion: String,
): DiscoveredWorldDirectory {
    val canonicalWorkingDirectory = fileSystem.canonicalize(currentWorkingDirectory)
    explicitWorldDirectory?.takeIf(String::isNotBlank)?.let { path ->
        val requestedPath = path.toPath(normalize = true).let { worldPath ->
            if (worldPath.isAbsolute) worldPath else canonicalWorkingDirectory / worldPath
        }
        val canonicalWorldDirectory = fileSystem.canonicalize(requestedPath)
        requireWorldDirectory(fileSystem, canonicalWorldDirectory, "MINECRAFT_WORLD_DIRECTORY")
        return DiscoveredWorldDirectory(canonicalWorldDirectory, WorldDirectorySource.ENVIRONMENT)
    }

    var candidate: Path? = canonicalWorkingDirectory
    var projectRoot: Path? = null
    while (candidate != null) {
        if (fileSystem.metadataOrNull(candidate / PROJECT_ROOT_MARKER) != null) {
            projectRoot = candidate
            break
        }
        candidate = candidate.parent
    }
    val resolvedProjectRoot = checkNotNull(projectRoot) {
        "Unable to find $PROJECT_ROOT_MARKER from $canonicalWorkingDirectory or any parent directory"
    }
    val savesDirectory = resolvedProjectRoot / "demo" / "launcher" / "minecraft" / minecraftVersion / "saves"
    val savesMetadata = fileSystem.metadataOrNull(savesDirectory)
    check(savesMetadata?.isDirectory == true) { "Launcher saves directory does not exist: $savesDirectory" }
    val selectedWorldDirectory = fileSystem.list(savesDirectory)
        .filter { path -> fileSystem.metadataOrNull(path)?.isDirectory == true }
        .sortedBy(Path::name)
        .firstOrNull()
        ?: error("Launcher saves directory contains no world directories: $savesDirectory")
    val canonicalWorldDirectory = fileSystem.canonicalize(selectedWorldDirectory)
    requireWorldDirectory(fileSystem, canonicalWorldDirectory, "project discovery")
    return DiscoveredWorldDirectory(canonicalWorldDirectory, WorldDirectorySource.PROJECT_DISCOVERY)
}

private fun requireWorldDirectory(fileSystem: FileSystem, worldDirectory: Path, source: String) {
    check(fileSystem.metadataOrNull(worldDirectory)?.isDirectory == true) {
        "World directory selected by $source is not a directory: $worldDirectory"
    }
    val levelDataPath = worldDirectory / "level.dat"
    check(fileSystem.metadataOrNull(levelDataPath)?.isRegularFile == true) {
        "World directory selected by $source has no regular level.dat: $worldDirectory"
    }
}

private const val PROJECT_ROOT_MARKER: String = ".minecraft-protocol-root"
