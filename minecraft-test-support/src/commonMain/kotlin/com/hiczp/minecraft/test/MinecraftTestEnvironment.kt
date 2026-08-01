package com.hiczp.minecraft.test

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.minutes

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
        val version = environment.minecraftVersion
        val directory = Path(environment.sharedCacheDirectory, "mojang")
            .safeResolve(version)
        val jar = Path(directory, "server.jar")
        val metadataFile = Path(directory, "download-metadata.json")
        return loadVerifiedServer(version, jar, metadataFile)
            ?: downloadServer(version, jar, metadataFile)
    }

    suspend fun headlessLauncher(
        environment: MinecraftTestEnvironment,
    ): Path {
        val directory = Path(
            environment.sharedCacheDirectory,
            "headlessmc",
        ).safeResolve(HEADLESS_VERSION)
        val destination =
            Path(directory, "headlessmc-launcher-$HEADLESS_VERSION.jar")
        TestHttp.ensureDownload(
            url = HEADLESS_URL,
            destination = destination,
            expectedSize = HEADLESS_SIZE,
            digestAlgorithm = "SHA-256",
            expectedDigest = HEADLESS_SHA256,
            timeout = 5.minutes,
        )
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

    private suspend fun downloadServer(
        version: String,
        jar: Path,
        metadataFile: Path,
    ): OfficialServerArtifact {
        val entry = officialReleaseManifestEntry(version)
        val metadataUrl = entry.requiredString("url")
        val metadataBytes = TestHttp.getBytes(metadataUrl)
        val metadataSha1 = entry.requiredString("sha1").lowercase()
        check(metadataBytes.sha1() == metadataSha1) {
            "Mojang version metadata failed its manifest SHA-1"
        }
        val metadata = metadataBytes.decodeJsonObject(metadataUrl)
        check(metadata.requiredString("id") == version) {
            "Mojang metadata identifies a different release"
        }
        val server = metadata.requiredObject("downloads")
            .requiredObject("server")
        val expectedSha1 = server.requiredString("sha1").lowercase()
        val expectedSize = server.requiredLong("size")
        TestHttp.ensureDownload(
            url = server.requiredString("url"),
            destination = jar,
            expectedSize = expectedSize,
            digestAlgorithm = "SHA-1",
            expectedDigest = expectedSha1,
        )
        val javaMajor = metadata.requiredObject("javaVersion")
            .requiredInt("majorVersion")
        val serverSha256 = jar.sha256()
        metadataFile.writeJson(
            jsonObjectOf(
                "minecraft_version" to jsonString(version),
                "version_metadata_url" to jsonString(metadataUrl),
                "version_metadata_sha1" to jsonString(metadataSha1),
                "server_url" to jsonString(server.requiredString("url")),
                "server_sha1" to jsonString(expectedSha1),
                "server_sha256" to jsonString(serverSha256),
                "server_size" to jsonNumber(expectedSize),
                "java_major_version" to jsonNumber(javaMajor),
            ),
        )
        return checkNotNull(loadVerifiedServer(version, jar, metadataFile)) {
            "Downloaded official server did not pass its own verification"
        }
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
