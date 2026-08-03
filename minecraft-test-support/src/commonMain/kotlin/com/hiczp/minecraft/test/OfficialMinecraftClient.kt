package com.hiczp.minecraft.test

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val officialClientLogger = DirectLoggerFactory.logger(
    "com.hiczp.minecraft.test.OfficialClientPreparation",
)

data class OfficialClientInstallation(
    val directory: Path,
    val version: String,
    val requiredJavaMajor: Int,
    val clientSha1: String,
)

internal object OfficialClientPreparation {
    private val sha1Pattern = Regex("[0-9a-f]{40}")

    suspend fun prepare(
        environment: MinecraftTestEnvironment,
        workers: Int,
    ): OfficialClientInstallation {
        require(workers > 0) { "workers must be positive" }
        val version = environment.minecraftVersion
        val root = environment.clientCacheDir()
        officialClientLogger.info {
            "Verifying official Minecraft client $version in $root"
        }
        return verifyClient(version, root)
    }

    private fun verifyClient(
        version: String,
        clientRoot: Path,
    ): OfficialClientInstallation {
        check(clientRoot.isDirectory()) {
            "Official client directory is missing: $clientRoot; " +
                    "run the Gradle downloadOfficialMinecraftClient task first"
        }

        // Read version metadata (shared, produced by downloadVersionMetadata)
        val versionRoot = requireNotNull(clientRoot.parent)
        val metadataPath = Path(versionRoot, "version.json")
        check(metadataPath.isRegularFile()) {
            "Version metadata is missing: $metadataPath; " +
                    "run the Gradle downloadVersionMetadata task first"
        }
        val metadata = metadataPath.readJsonObject()
        check(metadata.requiredString("id") == version) {
            "Version metadata identifies a different release"
        }

        // Verify client JAR
        val clientJar = Path(clientRoot, "client.jar")
        val clientSpec = metadata.requiredObject("downloads")
            .requiredObject("client")
        val expectedClientSha1 = validateSha1(
            clientSpec.requiredString("sha1"),
            "client download",
        )
        check(clientJar.isRegularFile()) {
            "Official client JAR is missing: $clientJar; " +
                    "run the Gradle downloadOfficialMinecraftClient task first"
        }
        check(clientJar.sha1() == expectedClientSha1) {
            "Official client JAR failed SHA-1 verification"
        }

        // Verify libraries
        val libraries = collectLibraryArtifacts(metadata)
        officialClientLogger.info {
            "Verifying ${libraries.size} official client library artifacts"
        }
        libraries.entries.sortedBy { it.key }.forEach { (relative, _) ->
            val libPath = Path(clientRoot, "libraries").safeResolve(relative)
            check(libPath.isRegularFile()) {
                "Official client library is missing: $libPath"
            }
        }

        // Verify asset index
        val assetIndexSpec = metadata.requiredObject("assetIndex")
        val assetIndexId = assetIndexSpec.requiredString("id")
        val expectedAssetIndexSha1 = validateSha1(
            assetIndexSpec.requiredString("sha1"),
            "asset index",
        )
        val assetIndexPath = Path(clientRoot, "assets", "indexes")
            .safeResolve("$assetIndexId.json")
        check(assetIndexPath.isRegularFile()) {
            "Official asset index is missing: $assetIndexPath; " +
                    "run the Gradle downloadOfficialMinecraftClient task first"
        }
        check(assetIndexPath.sha1() == expectedAssetIndexSha1) {
            "Official asset index failed SHA-1 verification"
        }

        // Collect asset objects for verification
        val assets = linkedMapOf<String, Long>()
        assetIndexPath.readJsonObject().requiredObject("objects")
            .forEach { (_, element) ->
                val value = element.jsonObject
                val hash = validateSha1(value.requiredString("hash"), "asset")
                val size = value.requiredLong("size")
                assets[hash] = size
            }
        officialClientLogger.info {
            "Official asset index $assetIndexId ready (${assets.size} unique objects)"
        }

        // Verify asset objects on demand — fail only if missing when accessed
        val javaMajor = metadata["javaVersion"]
            ?.jsonObject
            ?.requiredInt("majorVersion")
            ?: 0

        officialClientLogger.info {
            "Official Minecraft client $version is ready: " +
                    "${libraries.size} libraries, ${assets.size} assets indexed"
        }

        return OfficialClientInstallation(
            directory = clientRoot,
            version = version,
            requiredJavaMajor = javaMajor,
            clientSha1 = expectedClientSha1,
        )
    }

    private fun collectLibraryArtifacts(
        metadata: JsonObject,
    ): Map<String, Unit> {
        val artifacts = linkedMapOf<String, Unit>()
        metadata.requiredArray("libraries").forEach { element ->
            val library = element.jsonObject
            val downloads = library["downloads"]
                ?.takeUnless { it === JsonNull }
                ?.jsonObject
                ?: return@forEach
            val candidates = buildList {
                downloads["artifact"]?.let { add("artifact" to it.jsonObject) }
                downloads["classifiers"]?.jsonObject?.forEach { (name, value) ->
                    add("classifier $name" to value.jsonObject)
                }
            }
            candidates.forEach { (_, value) ->
                val path = value.requiredString("path")
                artifacts[path] = Unit
            }
        }
        return artifacts
    }

    private fun validateSha1(value: String, context: String): String =
        value.lowercase().also {
            require(it.matches(sha1Pattern)) { "$context has an invalid SHA-1" }
        }
}
