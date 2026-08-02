package com.hiczp.minecraft.test

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Repository-local test environment acquired directly by a standard test.
 * No Gradle preparation task or injected system property is required.
 */
class MinecraftTestEnvironment private constructor(
    val minecraftVersion: String,
    val repositoryRoot: Path,
    val moduleBuildDirectory: Path,
    val javaExecutable: Path,
) {
    internal val sharedCacheDirectory: Path =
        Path(repositoryRoot, "build", "protocol-reference")

    /** Root for version-specific artifacts. */
    internal val versionCacheRoot: Path
        get() = Path(sharedCacheDirectory, minecraftVersion)

    internal fun serverCacheDir(): Path =
        Path(versionCacheRoot, "mojang-server")

    internal fun clientCacheDir(): Path =
        Path(versionCacheRoot, "mojang-client")

    internal fun headlessMcCacheDir(): Path =
        Path(versionCacheRoot, "headlessmc")

    internal fun codecOracleCacheDir(): Path =
        Path(versionCacheRoot, "codec-oracle")

    fun freshWorkDirectory(name: String): Path {
        val requested = Path(moduleBuildDirectory, "test-runtimes")
            .safeResolve(name)
        return createUniqueDirectory(
            parent = requireNotNull(requested.parent),
            prefix = "${requested.name}-",
        )
    }

    fun reportFile(name: String): Path {
        val requested = Path(moduleBuildDirectory, "reports", "tests")
            .safeResolve(name)
        val runDirectory = createUniqueDirectory(
            parent = requireNotNull(requested.parent),
            prefix = "run-",
        )
        return Path(runDirectory, requested.name)
    }

    fun temporaryFile(name: String): Path =
        Path(moduleBuildDirectory, "tmp")
            .safeResolve(name)
            .also { path ->
                requireNotNull(path.parent).ensureDirectory()
            }

    suspend fun officialServer(): OfficialServerArtifact =
        OfficialArtifacts.server(this)

    suspend fun officialClient(
        workers: Int = DEFAULT_CLIENT_DOWNLOAD_WORKERS,
    ): OfficialClientInstallation =
        OfficialClientPreparation.prepare(this, workers)

    suspend fun headlessMinecraftLauncher(): Path =
        OfficialArtifacts.headlessLauncher(this)

    companion object {
        fun forModule(
            moduleName: String,
            minecraftVersion: String,
        ): MinecraftTestEnvironment {
            require(moduleName.matches(Regex("[a-z0-9-]+"))) {
                "Unsafe module name: $moduleName"
            }
            require(minecraftVersion.matches(Regex("[0-9A-Za-z._-]+"))) {
                "Unsafe Minecraft version: $minecraftVersion"
            }
            val root = discoverRepositoryRoot()
            val module = Path(root, moduleName)
            require(module.isDirectory()) {
                "Repository module does not exist: $module"
            }
            return MinecraftTestEnvironment(
                minecraftVersion = minecraftVersion,
                repositoryRoot = root,
                moduleBuildDirectory = Path(module, "build"),
                javaExecutable = Path("java"),
            )
        }
    }
}

data class OfficialServerArtifact(
    val jar: Path,
    val sha1: String,
    val sha256: String,
    val requiredJavaMajor: Int,
)

internal object OfficialArtifacts {
    private const val HEADLESS_VERSION = "2.10.0"
    private const val HEADLESS_SIZE = 13_010_386L
    private const val HEADLESS_SHA256 =
        "52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd"
    private const val HEADLESS_URL =
        "https://github.com/headlesshq/headlessmc/releases/download/" +
                "$HEADLESS_VERSION/headlessmc-launcher-$HEADLESS_VERSION.jar"

    suspend fun server(
        environment: MinecraftTestEnvironment,
    ): OfficialServerArtifact {
        val directory = environment.serverCacheDir()
        val jar = Path(directory, "server.jar")
        val metadataFile = Path(directory, "download-metadata.json")
        return loadVerifiedServer(
            environment.minecraftVersion, jar, metadataFile,
        ) ?: error(
            "Official server artifact is missing; " +
                    "run the Gradle downloadOfficialMinecraftServer task first",
        )
    }

    suspend fun headlessLauncher(
        environment: MinecraftTestEnvironment,
    ): Path {
        val destination = Path(
            environment.headlessMcCacheDir(),
            "headlessmc-launcher.jar",
        )
        check(destination.isRegularFile()) {
            "HeadlessMC launcher is missing; " +
                    "run the Gradle downloadHeadlessMc task first"
        }
        check(destination.sha256() == HEADLESS_SHA256) {
            "HeadlessMC launcher failed its SHA-256 verification"
        }
        return destination
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

private fun discoverRepositoryRoot(): Path {
    var candidate = SystemFileSystem.resolve(Path("."))
    while (true) {
        if (Path(candidate, "settings.gradle.kts").isRegularFile()) {
            return candidate
        }
        candidate = candidate.parent
            ?: error("Could not locate the repository root")
    }
}

const val DEFAULT_CLIENT_DOWNLOAD_WORKERS: Int = 4
