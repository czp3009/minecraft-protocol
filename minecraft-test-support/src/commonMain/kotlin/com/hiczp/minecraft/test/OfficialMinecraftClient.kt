package com.hiczp.minecraft.test

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val ASSET_OBJECT_BASE_URL =
        "https://resources.download.minecraft.net"
    private val sha1Pattern = Regex("[0-9a-f]{40}")

    suspend fun prepare(
        environment: MinecraftTestEnvironment,
        workers: Int,
    ): OfficialClientInstallation {
        require(workers > 0) { "workers must be positive" }
        val version = environment.minecraftVersion
        val root = Path(environment.sharedCacheDirectory, "mojang-client")
            .safeResolve(version)
        officialClientLogger.info {
            "Preparing official Minecraft client $version in $root " +
                    "with $workers asset workers"
        }
        return prepareClient(version, root, workers)
    }

    private suspend fun prepareClient(
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
        val clientJar = clientRoot.safeResolve(
            "versions/$version/$version.jar",
        )
        val downloadedClient = TestHttp.ensureDownload(
            url = client.url,
            destination = clientJar,
            expectedSize = client.size,
            digestAlgorithm = "SHA-1",
            expectedDigest = client.sha1,
        )
        officialClientLogger.info {
            "Official client JAR ready (downloaded=$downloadedClient)"
        }

        val libraries = collectLibraryArtifacts(metadata)
        var downloadedLibraries = 0
        officialClientLogger.info {
            "Verifying ${libraries.size} official client library artifacts"
        }
        libraries.entries.sortedBy { it.key }
            .forEach { (relative, artifact) ->
                if (
                    TestHttp.ensureDownload(
                        url = artifact.url,
                        destination = Path(clientRoot, "libraries")
                            .safeResolve(relative),
                        expectedSize = artifact.size,
                        digestAlgorithm = "SHA-1",
                        expectedDigest = artifact.sha1,
                    )
                ) {
                    downloadedLibraries++
                }
            }
        officialClientLogger.info {
            "Official client libraries ready: ${libraries.size} verified, " +
                    "$downloadedLibraries downloaded"
        }

        val assetIndexValue = metadata.requiredObject("assetIndex")
        val assetIndexId = assetIndexValue.requiredString("id")
        val assetIndexSha1 = validateSha1(
            assetIndexValue.requiredString("sha1"),
            "asset index",
        )
        val assetIndexPath = Path(clientRoot, "assets", "indexes")
            .safeResolve("$assetIndexId.json")
        val downloadedAssetIndex = TestHttp.ensureDownload(
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
                val previous = assets.put(hash, size)
                check(previous == null || previous == size) {
                    "Asset hash $hash has conflicting sizes"
                }
            }

        officialClientLogger.info {
            "Official asset index $assetIndexId ready " +
                    "(downloaded=$downloadedAssetIndex, " +
                    "${assets.size} unique objects)"
        }
        val assetAcquisition = downloadAssets(clientRoot, assets, workers)

        val javaMajor = metadata.requiredObject("javaVersion")
            .requiredInt("majorVersion")
        Path(clientRoot, "preparation.json").writeJson(
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
        officialClientLogger.info {
            "Official Minecraft client $version is ready: " +
                    "${assetAcquisition.total} assets verified, " +
                    "${assetAcquisition.downloaded} downloaded"
        }
        return OfficialClientInstallation(
            directory = clientRoot,
            version = version,
            requiredJavaMajor = javaMajor,
            clientSha1 = client.sha1,
        )
    }

    private suspend fun acquireMetadata(
        clientRoot: Path,
        version: String,
    ): Pair<JsonObject, JsonObject> {
        val metadataPath = clientRoot.safeResolve(
            "versions/$version/$version.json",
        )
        val sourcePath = Path(clientRoot, "version-metadata-source.json")
        loadCachedMetadata(metadataPath, sourcePath, version)?.let {
            return it
        }

        val entry = officialReleaseManifestEntry(version)
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

    private suspend fun downloadAssets(
        clientRoot: Path,
        assets: Map<String, Long>,
        workers: Int,
    ): AcquisitionSummary = coroutineScope {
        if (assets.isEmpty()) {
            return@coroutineScope AcquisitionSummary(0, 0)
        }
        val progress = Mutex()
        var completed = 0
        var downloaded = 0
        val queue = Channel<Map.Entry<String, Long>>(capacity = workers)
        val producer = launch {
            try {
                assets.entries.sortedBy { it.key }.forEach { entry ->
                    queue.send(entry)
                }
            } finally {
                queue.close()
            }
        }
        val consumers = List(minOf(workers, assets.size)) {
            launch {
                for ((hash, size) in queue) {
                    val relative = "${hash.take(2)}/$hash"
                    val acquired = TestHttp.ensureDownload(
                        url = "$ASSET_OBJECT_BASE_URL/$relative",
                        destination = Path(clientRoot, "assets", "objects")
                            .safeResolve(relative),
                        expectedSize = size,
                        digestAlgorithm = "SHA-1",
                        expectedDigest = hash,
                    )
                    progress.withLock {
                        completed++
                        if (acquired) downloaded++
                        if (
                            completed % ASSET_PROGRESS_INTERVAL == 0 ||
                            completed == assets.size
                        ) {
                            officialClientLogger.info {
                                "Official client assets: $completed/" +
                                        "${assets.size} verified, " +
                                        "$downloaded downloaded"
                            }
                        }
                    }
                }
            }
        }
        producer.join()
        consumers.joinAll()
        AcquisitionSummary(assets.size, downloaded)
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
                    val path = requireNotNull(artifact.path)
                    val previous = artifacts.put(path, artifact)
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

    private data class AcquisitionSummary(
        val total: Int,
        val downloaded: Int,
    )

    private const val ASSET_PROGRESS_INTERVAL = 250
}
