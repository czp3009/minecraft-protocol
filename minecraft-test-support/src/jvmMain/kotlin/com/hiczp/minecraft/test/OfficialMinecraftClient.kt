package com.hiczp.minecraft.test

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.isRegularFile

data class OfficialClientInstallation(
    val directory: Path,
    val version: String,
    val requiredJavaMajor: Int,
    val clientSha1: String,
)

internal object OfficialClientPreparation {
    private const val VERSION_MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val ASSET_OBJECT_BASE_URL =
        "https://resources.download.minecraft.net"
    private val sha1Pattern = Regex("[0-9a-f]{40}")

    fun prepare(
        environment: MinecraftTestEnvironment,
        workers: Int,
    ): OfficialClientInstallation {
        require(workers > 0) { "workers must be positive" }
        val version = environment.minecraftVersion
        val root = environment.sharedCacheDirectory
            .resolve("mojang-client")
            .safeResolve(version)
        return root.resolve(".test-support.lock").withExclusiveLock {
            prepareLocked(version, root, workers)
        }
    }

    private fun prepareLocked(
        version: String,
        clientRoot: Path,
        workers: Int,
    ): OfficialClientInstallation {
        val (metadata, source) = acquireMetadata(clientRoot, version)
        val client = artifactSpec(
            metadata.requiredObject("downloads")
                .getValue("client").jsonObject,
            "client download",
            requirePath = false,
        )
        val clientJar =
            clientRoot.resolve("versions/$version/$version.jar")
        TestHttp.ensureDownload(
            url = client.url,
            destination = clientJar,
            expectedSize = client.size,
            digestAlgorithm = "SHA-1",
            expectedDigest = client.sha1,
        )

        val libraries = collectLibraryArtifacts(metadata)
        libraries.toSortedMap().forEach { (relative, artifact) ->
            TestHttp.ensureDownload(
                url = artifact.url,
                destination = clientRoot.resolve("libraries")
                    .safeResolve(relative),
                expectedSize = artifact.size,
                digestAlgorithm = "SHA-1",
                expectedDigest = artifact.sha1,
            )
        }

        val assetIndexValue = metadata.requiredObject("assetIndex")
        val assetIndexId = assetIndexValue.requiredString("id")
        val assetIndexSha1 = validateSha1(
            assetIndexValue.requiredString("sha1"),
            "asset index",
        )
        val assetIndexPath = clientRoot.resolve("assets/indexes")
            .safeResolve("$assetIndexId.json")
        TestHttp.ensureDownload(
            url = assetIndexValue.requiredString("url"),
            destination = assetIndexPath,
            expectedSize = assetIndexValue.requiredLong("size"),
            digestAlgorithm = "SHA-1",
            expectedDigest = assetIndexSha1,
        )
        val assets = linkedMapOf<String, Long>()
        assetIndexPath.readJsonObject().requiredObject("objects")
            .forEach { (logicalName, element) ->
                val value = element.jsonObject
                val hash = validateSha1(
                    value.requiredString("hash"),
                    "asset '$logicalName'",
                )
                val size = value.requiredLong("size")
                val previous = assets.putIfAbsent(hash, size)
                check(previous == null || previous == size) {
                    "Asset hash $hash has conflicting sizes"
                }
            }

        val executor = Executors.newFixedThreadPool(workers)
        try {
            assets.entries.sortedBy { it.key }.map { (hash, size) ->
                executor.submit(Callable {
                    val relative = "${hash.take(2)}/$hash"
                    TestHttp.ensureDownload(
                        url = "$ASSET_OBJECT_BASE_URL/$relative",
                        destination = clientRoot.resolve("assets/objects")
                            .safeResolve(relative),
                        expectedSize = size,
                        digestAlgorithm = "SHA-1",
                        expectedDigest = hash,
                    )
                })
            }.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val javaMajor = metadata.requiredObject("javaVersion")
            .requiredInt("majorVersion")
        clientRoot.resolve("preparation.json").writeJson(
            jsonObjectOf(
                "schema_version" to jsonNumber(1),
                "minecraft_version" to jsonString(version),
                "version_metadata_sha1" to jsonString(
                    source.requiredString("version_metadata_sha1"),
                ),
                "client_sha1" to jsonString(client.sha1),
                "asset_index_id" to jsonString(assetIndexId),
                "asset_index_sha1" to jsonString(assetIndexSha1),
                "library_artifact_count" to jsonNumber(libraries.size),
                "asset_object_count" to jsonNumber(assets.size),
            ),
        )
        check(clientJar.sha1() == client.sha1) {
            "Official client JAR failed its final Mojang SHA-1 verification"
        }
        return OfficialClientInstallation(
            directory = clientRoot,
            version = version,
            requiredJavaMajor = javaMajor,
            clientSha1 = client.sha1,
        )
    }

    private fun acquireMetadata(
        clientRoot: Path,
        version: String,
    ): Pair<JsonObject, JsonObject> {
        val metadataPath =
            clientRoot.resolve("versions/$version/$version.json")
        val sourcePath =
            clientRoot.resolve("version-metadata-source.json")
        loadCachedMetadata(metadataPath, sourcePath, version)?.let {
            return it
        }

        val manifest = TestHttp.getJson(VERSION_MANIFEST_URL)
        val entry = manifest.requiredArray("versions")
            .map { it.jsonObject }
            .firstOrNull {
                it.requiredString("id") == version &&
                        it.requiredString("type") == "release"
            }
            ?: error("Mojang manifest has no stable release $version")
        val metadataUrl = entry.requiredString("url")
        val expectedSha1 = validateSha1(
            entry.requiredString("sha1"),
            "version manifest entry",
        )
        val data = TestHttp.getBytes(metadataUrl)
        check(data.sha1() == expectedSha1) {
            "Mojang version metadata failed its manifest SHA-1"
        }
        val metadata = data.decodeJsonObject(metadataUrl)
        check(metadata.requiredString("id") == version) {
            "Mojang version metadata identifies a different release"
        }
        val source = jsonObjectOf(
            "minecraft_version" to jsonString(version),
            "version_metadata_sha1" to jsonString(expectedSha1),
            "version_metadata_url" to jsonString(metadataUrl),
        )
        metadataPath.atomicWrite(data)
        sourcePath.writeJson(source)
        return metadata to source
    }

    private fun loadCachedMetadata(
        metadataPath: Path,
        sourcePath: Path,
        version: String,
    ): Pair<JsonObject, JsonObject>? {
        if (!metadataPath.isRegularFile() || !sourcePath.isRegularFile()) {
            return null
        }
        return runCatching {
            val source = sourcePath.readJsonObject()
            check(source.requiredString("minecraft_version") == version)
            check(
                metadataPath.sha1() == validateSha1(
                    source.requiredString("version_metadata_sha1"),
                    "source marker",
                ),
            )
            val metadata = metadataPath.readJsonObject()
            check(metadata.requiredString("id") == version)
            metadata to source
        }.getOrNull()
    }

    private fun collectLibraryArtifacts(
        metadata: JsonObject,
    ): Map<String, Artifact> {
        val artifacts = linkedMapOf<String, Artifact>()
        metadata.requiredArray("libraries")
            .forEachIndexed { index, element ->
                val library = element.jsonObject
                val downloads = library["downloads"]
                    ?.takeUnless { it === JsonNull }
                    ?.jsonObject
                    ?: return@forEachIndexed
                val candidates = buildList {
                    downloads["artifact"]?.let {
                        add("artifact" to it.jsonObject)
                    }
                    downloads["classifiers"]?.jsonObject
                        ?.forEach { (name, value) ->
                            add("classifier $name" to value.jsonObject)
                        }
                }
                candidates.forEach { (kind, value) ->
                    val artifact = artifactSpec(
                        value,
                        "library $index $kind",
                    )
                    val previous = artifacts.putIfAbsent(
                        requireNotNull(artifact.path),
                        artifact,
                    )
                    check(previous == null || previous == artifact) {
                        "Conflicting library artifact: ${artifact.path}"
                    }
                }
            }
        return artifacts
    }

    private fun artifactSpec(
        value: JsonObject,
        context: String,
        requirePath: Boolean = true,
    ): Artifact {
        val sha1 = validateSha1(value.requiredString("sha1"), context)
        val size = value.requiredLong("size")
        require(size >= 0) { "$context has a negative size" }
        val url = value.requiredString("url")
        require(url.isNotEmpty()) { "$context has an empty URL" }
        val path = if (requirePath) {
            value.requiredString("path").also {
                require(it.isNotEmpty()) { "$context has an empty path" }
            }
        } else {
            null
        }
        return Artifact(sha1, size, url, path)
    }

    private fun validateSha1(value: String, context: String): String =
        value.lowercase().also {
            require(it.matches(sha1Pattern)) {
                "$context has an invalid SHA-1"
            }
        }

    private data class Artifact(
        val sha1: String,
        val size: Long,
        val url: String,
        val path: String?,
    )
}
