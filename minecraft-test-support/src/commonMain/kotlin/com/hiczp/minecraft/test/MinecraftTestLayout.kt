package com.hiczp.minecraft.test

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal data class MinecraftTestLayout(
    val minecraftVersion: String,
    val repositoryRoot: Path,
    val javaExecutable: Path,
) {
    val repositoryBuildDirectory: Path
        get() = Path(repositoryRoot, "build")

    val versionCacheRoot: Path
        get() = Path(
            repositoryBuildDirectory,
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
                repositoryBuildDirectory,
                "minecraft-test-support",
                "runtimes",
                kind.directoryName,
                minecraftVersion,
            ),
        )

    fun newScratchDirectory(): Path = createUniqueDirectory(
        Path(
            repositoryBuildDirectory,
            "minecraft-test-support",
            "tmp",
        ),
    )

    fun reportFile(name: String): Path = uniqueFile(
        root = Path(
            repositoryBuildDirectory,
            "minecraft-test-support",
            "reports",
        ),
        name = name,
    )

    fun temporaryFile(name: String): Path = uniqueFile(
        root = Path(
            repositoryBuildDirectory,
            "minecraft-test-support",
            "tmp",
        ),
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
            return MinecraftTestLayout(
                minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
                repositoryRoot = repositoryRoot,
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

private const val ROOT_MARKER_MAGIC = "minecraft-protocol-root-v1"

internal fun discoverRepositoryRoot(start: Path): Path {
    var candidate: Path? = start
    while (candidate != null) {
        val markerFile = Path(candidate, ".minecraft-protocol-root")
        if (markerFile.isRegularFile()) {
            val content = markerFile.readText().trim()
            check(content == ROOT_MARKER_MAGIC) {
                "File $markerFile has unexpected content: expected " +
                        "'$ROOT_MARKER_MAGIC' but found '$content'"
            }
            return candidate
        }
        candidate = candidate.parent
    }
    error(
        "Could not locate the minecraft-protocol repository root. No valid .minecraft-protocol-root marker found when searching upward from $start. Tests must run from inside the repository tree.",
    )
}
