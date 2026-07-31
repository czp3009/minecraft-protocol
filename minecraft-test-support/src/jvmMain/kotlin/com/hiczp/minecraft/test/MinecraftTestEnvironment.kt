package com.hiczp.minecraft.test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Repository-local runtime environment acquired directly by a standard JVM
 * test. No Gradle preparation task or injected system property is required.
 */
class MinecraftTestEnvironment private constructor(
    val minecraftVersion: String,
    val repositoryRoot: Path,
    val moduleBuildDirectory: Path,
) {
    val javaExecutable: Path = currentJavaExecutable()

    internal val sharedCacheDirectory: Path =
        repositoryRoot.resolve("build/protocol-reference")

    fun freshWorkDirectory(name: String): Path =
        moduleBuildDirectory.resolve("test-runtimes").safeResolve(name)
            .also {
                it.deleteTree()
                it.createDirectories()
            }

    fun reportFile(name: String): Path =
        moduleBuildDirectory.resolve("reports/tests").safeResolve(name)
            .also { it.parent.createDirectories() }

    fun temporaryFile(name: String): Path =
        moduleBuildDirectory.resolve("tmp").safeResolve(name)
            .also { it.parent.createDirectories() }

    fun officialServer(): OfficialServerArtifact =
        OfficialArtifacts.server(this)

    fun officialServerRuntime(): OfficialServerRuntime =
        OfficialArtifacts.serverRuntime(this)

    fun officialClient(
        workers: Int = DEFAULT_CLIENT_DOWNLOAD_WORKERS,
    ): OfficialClientInstallation =
        OfficialClientPreparation.prepare(this, workers)

    fun headlessMinecraftLauncher(): Path =
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
            val module = root.resolve(moduleName)
            require(module.isDirectory()) {
                "Repository module does not exist: $module"
            }
            return MinecraftTestEnvironment(
                minecraftVersion = minecraftVersion,
                repositoryRoot = root,
                moduleBuildDirectory = module.resolve("build"),
            )
        }
    }
}

data class OfficialServerArtifact(
    val jar: Path,
    val sha1: String,
    val requiredJavaMajor: Int,
)

data class OfficialServerRuntime(
    val directory: Path,
    val implementationJar: Path,
    val libraries: List<Path>,
)

internal object OfficialArtifacts {
    private const val HEADLESS_VERSION = "2.10.0"
    private const val HEADLESS_SIZE = 13_010_386L
    private const val HEADLESS_SHA256 =
        "52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd"
    private const val HEADLESS_URL =
        "https://github.com/headlesshq/headlessmc/releases/download/" +
                "$HEADLESS_VERSION/headlessmc-launcher-$HEADLESS_VERSION.jar"

    fun server(
        environment: MinecraftTestEnvironment,
    ): OfficialServerArtifact {
        val version = environment.minecraftVersion
        val directory = environment.sharedCacheDirectory
            .resolve("mojang")
            .safeResolve(version)
        val jar = directory.resolve("server.jar")
        val metadataFile = directory.resolve("download-metadata.json")
        return directory.resolve(".test-support.lock").withExclusiveLock {
            loadVerifiedServer(version, jar, metadataFile)
                ?: downloadServer(version, jar, metadataFile)
        }
    }

    fun serverRuntime(
        environment: MinecraftTestEnvironment,
    ): OfficialServerRuntime {
        val server = server(environment)
        val output = server.jar.parent.resolve("runtime")
        return server.jar.parent.resolve(".runtime-test-support.lock")
            .withExclusiveLock {
                if (!runtimeIsValid(server.jar, output, environment.minecraftVersion)) {
                    unpackServerRuntime(
                        bundle = server.jar,
                        output = output,
                        expectedVersion = environment.minecraftVersion,
                    )
                }
                loadRuntime(server.jar, output, environment.minecraftVersion)
            }
    }

    fun headlessLauncher(
        environment: MinecraftTestEnvironment,
    ): Path {
        val directory = environment.sharedCacheDirectory
            .resolve("headlessmc")
            .safeResolve(HEADLESS_VERSION)
        val destination =
            directory.resolve("headlessmc-launcher-$HEADLESS_VERSION.jar")
        return directory.resolve(".test-support.lock").withExclusiveLock {
            TestHttp.ensureDownload(
                url = HEADLESS_URL,
                destination = destination,
                expectedSize = HEADLESS_SIZE,
                digestAlgorithm = "SHA-256",
                expectedDigest = HEADLESS_SHA256,
                timeout = Duration.ofMinutes(5),
            )
            check(destination.sha256() == HEADLESS_SHA256) {
                "HeadlessMC launcher failed its SHA-256 verification"
            }
            destination
        }
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
            check(Files.size(jar) == expectedSize)
            check(jar.sha1() == expectedSha1)
            OfficialServerArtifact(
                jar = jar,
                sha1 = expectedSha1,
                requiredJavaMajor = metadata.requiredInt("java_major_version"),
            )
        }.getOrNull()
    }

    private fun downloadServer(
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
        metadataFile.writeJson(
            jsonObjectOf(
                "minecraft_version" to jsonString(version),
                "version_metadata_url" to jsonString(metadataUrl),
                "version_metadata_sha1" to jsonString(metadataSha1),
                "server_url" to jsonString(server.requiredString("url")),
                "server_sha1" to jsonString(expectedSha1),
                "server_size" to jsonNumber(expectedSize),
                "java_major_version" to jsonNumber(javaMajor),
            ),
        )
        return checkNotNull(loadVerifiedServer(version, jar, metadataFile)) {
            "Downloaded official server did not pass its own verification"
        }
    }

    private fun runtimeIsValid(
        bundle: Path,
        output: Path,
        expectedVersion: String,
    ): Boolean = runCatching {
        loadRuntime(bundle, output, expectedVersion)
    }.isSuccess

    private fun loadRuntime(
        bundle: Path,
        output: Path,
        expectedVersion: String,
    ): OfficialServerRuntime {
        check(output.isDirectory()) { "Official runtime is absent: $output" }
        ZipFile(bundle.toFile()).use { archive ->
            val versionFields = archive.getInputStream(
                archive.getEntry("META-INF/versions.list")
                    ?: error("Server bundle has no versions.list"),
            ).use {
                it.readBytes().toString(StandardCharsets.UTF_8).trim()
            }.split('\t')
            check(versionFields.size == 3 && versionFields[1] == expectedVersion)
            val implementation = output.resolve("server.jar")
            check(
                implementation.isRegularFile() &&
                        implementation.sha256() == versionFields[0].lowercase(),
            ) {
                "Official runtime implementation JAR is invalid"
            }

            val libraries = archive.getInputStream(
                archive.getEntry("META-INF/libraries.list")
                    ?: error("Server bundle has no libraries.list"),
            ).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }.lineSequence().filter(String::isNotBlank).map { line ->
                val fields = line.split('\t')
                check(fields.size == 3)
                output.resolve("libraries").safeResolve(fields[2]).also { path ->
                    check(
                        path.isRegularFile() &&
                                path.sha256() == fields[0].lowercase(),
                    ) {
                        "Official runtime library is invalid: ${fields[2]}"
                    }
                }
            }.sorted().toList()
            return OfficialServerRuntime(
                directory = output,
                implementationJar = implementation,
                libraries = libraries,
            )
        }
    }

    private fun unpackServerRuntime(
        bundle: Path,
        output: Path,
        expectedVersion: String,
    ) {
        output.deleteTree()
        output.createDirectories()
        ZipFile(bundle.toFile()).use { archive ->
            val versionFields = archive.getInputStream(
                archive.getEntry("META-INF/versions.list")
                    ?: error("Server bundle has no versions.list"),
            ).use {
                it.readBytes().toString(StandardCharsets.UTF_8).trim()
            }.split('\t')
            check(versionFields.size == 3)
            check(versionFields[1] == expectedVersion)
            check(versionFields[0].matches(Regex("[0-9a-fA-F]{64}")))
            val implementation = archive.getInputStream(
                archive.getEntry("META-INF/versions/${versionFields[2]}")
                    ?: error("Server bundle is missing its implementation JAR"),
            ).use { it.readBytes() }
            check(implementation.sha256() == versionFields[0].lowercase())
            output.resolve("server.jar").atomicWrite(implementation)

            val libraryLines = archive.getInputStream(
                archive.getEntry("META-INF/libraries.list")
                    ?: error("Server bundle has no libraries.list"),
            ).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }.lineSequence().filter(String::isNotBlank)
            libraryLines.forEach { line ->
                val fields = line.split('\t')
                check(fields.size == 3)
                val digest = fields[0].lowercase()
                check(digest.matches(Regex("[0-9a-f]{64}")))
                val entryName = "META-INF/libraries/${fields[2]}"
                val content = archive.getInputStream(
                    archive.getEntry(entryName)
                        ?: error("Server bundle has no $entryName"),
                ).use { it.readBytes() }
                check(content.sha256() == digest)
                output.resolve("libraries")
                    .safeResolve(fields[2])
                    .atomicWrite(content)
            }
        }
        loadRuntime(bundle, output, expectedVersion)
    }
}

private fun discoverRepositoryRoot(): Path {
    var candidate = Path.of("").toAbsolutePath().normalize()
    while (true) {
        if (candidate.resolve("settings.gradle.kts").isRegularFile()) {
            return candidate
        }
        candidate = candidate.parent
            ?: error("Could not locate the repository root")
    }
}

private fun currentJavaExecutable(): Path {
    val bin = Path.of(System.getProperty("java.home")).resolve("bin")
    val executable = listOf("java", "java.exe")
        .map(bin::resolve)
        .firstOrNull(Path::isRegularFile)
        ?: error("Current JVM has no Java launcher under $bin")
    return executable.toAbsolutePath().normalize()
}

const val DEFAULT_CLIENT_DOWNLOAD_WORKERS: Int = 4
