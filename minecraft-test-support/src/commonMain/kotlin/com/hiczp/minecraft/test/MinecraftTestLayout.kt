package com.hiczp.minecraft.test

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal data class MinecraftTestLayout(
    val minecraftVersion: String,
    val repositoryRoot: Path,
    val moduleBuildDirectory: Path,
    val javaExecutable: Path,
) {
    val versionCacheRoot: Path
        get() = Path(
            repositoryRoot,
            "build",
            "protocol-reference",
            minecraftVersion,
        )

    fun serverCacheDirectory(): Path =
        Path(versionCacheRoot, "mojang-server")

    fun clientCacheDirectory(): Path =
        Path(versionCacheRoot, "mojang-client")

    fun headlessMcCacheDirectory(): Path =
        Path(versionCacheRoot, "headlessmc")

    fun codecOracleCacheDirectory(): Path =
        Path(versionCacheRoot, "codec-oracle")

    fun newRuntimeDirectory(kind: MinecraftRuntimeKind): Path =
        createUniqueDirectory(
            Path(
                moduleBuildDirectory,
                "test-runtimes",
                kind.directoryName,
                minecraftVersion,
            ),
        )

    fun newScratchDirectory(): Path = createUniqueDirectory(
        Path(moduleBuildDirectory, "tmp", "minecraft-test-support"),
    )

    fun reportFile(name: String): Path = uniqueFile(
        root = Path(moduleBuildDirectory, "reports", "tests"),
        name = name,
    )

    fun temporaryFile(name: String): Path = uniqueFile(
        root = Path(moduleBuildDirectory, "tmp", "minecraft-test-support"),
        name = name,
    )

    private fun uniqueFile(root: Path, name: String): Path {
        root.safeResolve(name)
        val runDirectory = createUniqueDirectory(root)
        return runDirectory.safeResolve(name).also { path ->
            requireNotNull(path.parent).ensureDirectory()
        }
    }

    companion object {
        fun discover(): MinecraftTestLayout {
            val currentDirectory = SystemFileSystem.resolve(Path("."))
            val repositoryRoot = discoverRepositoryRoot(currentDirectory)
            val module = discoverOwningModule(
                repositoryRoot = repositoryRoot,
                currentDirectory = currentDirectory,
            )
            return MinecraftTestLayout(
                minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
                repositoryRoot = repositoryRoot,
                moduleBuildDirectory = Path(module, "build"),
                javaExecutable = Path("java"),
            )
        }
    }
}

internal enum class MinecraftRuntimeKind(val directoryName: String) {
    OFFICIAL_SERVER("official-server"),
    OFFICIAL_CLIENT("official-client"),
}

internal data class OfficialServerArtifact(
    internal val jar: Path,
    val sha1: String,
    val sha256: String,
    val requiredJavaMajor: Int,
)

internal object OfficialArtifacts {
    fun server(layout: MinecraftTestLayout): OfficialServerArtifact {
        val directory = layout.serverCacheDirectory()
        val jar = Path(directory, "server.jar")
        val metadataFile = Path(directory, "download-metadata.json")
        return loadVerifiedServer(
            version = layout.minecraftVersion,
            jar = jar,
            metadataFile = metadataFile,
        ) ?: error(
            "The verified official server fixture is missing or invalid. Run this test through its standard Gradle test task so the fixture producer can restore it.",
        )
    }

    fun clientRoot(layout: MinecraftTestLayout): Path =
        layout.clientCacheDirectory()

    fun headlessLauncher(layout: MinecraftTestLayout): Path {
        val launcher = Path(
            layout.headlessMcCacheDirectory(),
            "headlessmc-launcher.jar",
        )
        check(launcher.isRegularFile()) {
            "The verified HeadlessMC fixture is missing: $launcher. Run this test through its standard Gradle test task."
        }
        return launcher
    }

    private fun loadVerifiedServer(
        version: String,
        jar: Path,
        metadataFile: Path,
    ): OfficialServerArtifact? {
        if (!jar.isRegularFile() || !metadataFile.isRegularFile()) return null
        return runCatching {
            val metadata = metadataFile.readJsonObject()
            check(metadata.requiredString("minecraft_version") == version)
            val expectedSize = metadata.requiredLong("server_size")
            val expectedSha1 = metadata.requiredString("server_sha1")
            val expectedSha256 = metadata.requiredString("server_sha256")
            check(jar.size() == expectedSize)
            check(jar.sha1() == expectedSha1)
            check(jar.sha256() == expectedSha256)
            OfficialServerArtifact(
                jar = jar,
                sha1 = expectedSha1,
                sha256 = expectedSha256,
                requiredJavaMajor =
                    metadata.requiredInt("java_major_version"),
            )
        }.getOrNull()
    }
}

private fun discoverRepositoryRoot(start: Path): Path {
    var candidate: Path? = start
    while (candidate != null) {
        if (Path(candidate, "settings.gradle.kts").isRegularFile()) {
            return candidate
        }
        candidate = candidate.parent
    }
    error("Could not locate the minecraft-protocol repository root from $start")
}

private fun discoverOwningModule(
    repositoryRoot: Path,
    currentDirectory: Path,
): Path {
    val modules = SystemFileSystem.list(repositoryRoot)
        .filter { candidate ->
            candidate.isDirectory() &&
                    Path(candidate, "build.gradle.kts").isRegularFile()
        }
    modules.singleOrNull { module ->
        currentDirectory == module || currentDirectory.isBelow(module)
    }?.let { return it }

    val currentComponents = generateSequence(currentDirectory) { it.parent }
        .takeWhile { it != repositoryRoot }
        .map(Path::name)
        .toSet()
    val encodedMatches = modules.filter { module ->
        currentComponents.any { component ->
            component == module.name ||
                    component.startsWith("${module.name}-") ||
                    component.endsWith("-${module.name}") ||
                    "-${module.name}-" in component
        }
    }
    return encodedMatches.maxByOrNull { it.name.length }
        ?: error(
            "Could not infer the owning Gradle module from test working directory $currentDirectory",
        )
}

private fun Path.isBelow(ancestor: Path): Boolean =
    generateSequence(parent) { it.parent }.any { it == ancestor }
