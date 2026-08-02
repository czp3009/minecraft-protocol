package com.hiczp.minecraft.protocol.buildScript

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadVersionManifestTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadVersionManifestTask : DefaultTask() {
    @get:Input
    abstract val manifestUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val destination = outputFile.asFile.get().toPath()
        runBlocking {
            val bytes = ProtocolHttp.getBytes(manifestUrl.get())
            destination.parent.createDirectories()
            destination.atomicWrite(bytes)
        }
        logger.lifecycle("Downloaded Mojang version manifest: $destination")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadVersionMetadataTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadVersionMetadataTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val version = minecraftVersion.get()
        val manifest = manifestFile.asFile.get().toPath()
            .readJsonObject()
        val entry = manifest.requiredArray("versions")
            .map { it.jsonObject }
            .firstOrNull {
                it.requiredString("id") == version &&
                        it.requiredString("type") == "release"
            }
            ?: error("Mojang manifest has no stable release $version")
        val metadataUrl = entry.requiredString("url")
        val expectedSha1 = entry.requiredString("sha1").lowercase()
        val destination = outputFile.asFile.get().toPath()
        runBlocking {
            val bytes = ProtocolHttp.getBytes(metadataUrl)
            check(bytes.sha1() == expectedSha1) {
                "Mojang version metadata failed its manifest SHA-1"
            }
            val metadata = bytes.decodeJsonObject(metadataUrl)
            check(metadata.requiredString("id") == version) {
                "Mojang version metadata identifies a different release"
            }
            destination.parent.createDirectories()
            destination.atomicWrite(bytes)
        }
        logger.lifecycle(
            "Downloaded Mojang version metadata $version: $destination",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadHeadlessMcTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadHeadlessMcTask : DefaultTask() {
    companion object {
        const val HEADLESS_VERSION = "2.10.0"
        const val HEADLESS_URL =
            "https://github.com/headlesshq/headlessmc/releases/download/" +
                    "$HEADLESS_VERSION/headlessmc-launcher-$HEADLESS_VERSION.jar"
        const val HEADLESS_SIZE = 13_010_386L
        const val HEADLESS_SHA256 =
            "52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd"
    }

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val destination = outputFile.asFile.get().toPath()
        if (
            destination.isRegularFile() &&
            Files.size(destination) == HEADLESS_SIZE &&
            destination.sha256() == HEADLESS_SHA256
        ) {
            logger.lifecycle("Verified HeadlessMC launcher: $destination")
            return
        }
        runBlocking {
            destination.parent.createDirectories()
            val bytes = ProtocolHttp.getBytes(HEADLESS_URL)
            check(bytes.size.toLong() == HEADLESS_SIZE) {
                "HeadlessMC launcher has wrong size"
            }
            check(bytes.sha256() == HEADLESS_SHA256) {
                "HeadlessMC launcher failed SHA-256 verification"
            }
            destination.atomicWrite(bytes)
        }
        logger.lifecycle(
            "Downloaded HeadlessMC launcher: $destination",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftClientTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadOfficialMinecraftClientTask : DefaultTask() {
    companion object {
        private const val LIBRARY_CONCURRENCY = 8
    }

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val offline: Property<Boolean>

    init {
        offline.convention(false)
    }

    @TaskAction
    fun download() {
        val version = minecraftVersion.get()
        val metadata = metadataFile.asFile.get().toPath()
            .readJsonObject()
        check(metadata.requiredString("id") == version)

        val root = outputDirectory.asFile.get().toPath()
        val clientMetadataFile = root.resolve("download-metadata.json")
        runBlocking {
            downloadClientArtifacts(version, metadata, root, clientMetadataFile)
        }
    }

    private suspend fun downloadClientArtifacts(
        version: String,
        metadata: JsonObject,
        root: Path,
        metadataFile: Path,
    ) {
        root.createDirectories()

        // Client JAR
        val client = metadata.requiredObject("downloads")
            .requiredObject("client")
        val clientUrl = client.requiredString("url")
        val clientSha1 = client.requiredString("sha1").lowercase()
        val clientSize = client.requiredLong("size")
        val clientJar = root.resolve("client.jar")
        ProtocolHttp.ensureDownload(
            url = clientUrl,
            destination = clientJar,
            expectedSize = clientSize,
            expectedSha1 = clientSha1,
        )
        logger.lifecycle("Downloaded official client JAR: $clientJar")

        // Libraries
        val libraries = collectLibraryArtifacts(metadata)
        logger.lifecycle(
            "Verifying ${libraries.size} official client library artifacts (concurrency=$LIBRARY_CONCURRENCY)",
        )
        val sorted = libraries.entries.sortedBy { it.key }
        runBlocking {
            val semaphore = Semaphore(LIBRARY_CONCURRENCY)
            coroutineScope {
                sorted.map { (relative, artifact) ->
                    async {
                        semaphore.acquire()
                        try {
                            ProtocolHttp.ensureDownload(
                                url = artifact.url,
                                destination = root.resolve("libraries").resolve(relative),
                                expectedSize = artifact.size,
                                expectedSha1 = artifact.sha1,
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                }.forEach { it.await() }
            }
        }

        // Asset index
        val assetIndex = metadata.requiredObject("assetIndex")
        val assetIndexUrl = assetIndex.requiredString("url")
        val assetIndexSha1 = assetIndex.requiredString("sha1").lowercase()
        val assetIndexSize = assetIndex.requiredLong("size")
        val assetIndexId = assetIndex.requiredString("id")
        val indexesDir = root.resolve("assets/indexes")
        val assetIndexFile = indexesDir.resolve("$assetIndexId.json")
        ProtocolHttp.ensureDownload(
            url = assetIndexUrl,
            destination = assetIndexFile,
            expectedSize = assetIndexSize,
            expectedSha1 = assetIndexSha1,
        )
        logger.lifecycle(
            "Downloaded official asset index $assetIndexId",
        )

        // Write download metadata
        metadataFile.writeJson(
            jsonObjectOf(
                "schema_version" to jsonNumber(1),
                "minecraft_version" to jsonString(version),
                "client_sha1" to jsonString(clientSha1),
                "client_url" to jsonString(clientUrl),
                "library_count" to jsonNumber(libraries.size),
                "asset_index_id" to jsonString(assetIndexId),
                "asset_index_sha1" to jsonString(assetIndexSha1),
            ),
        )
        logger.lifecycle(
            "Official Minecraft client $version is ready",
        )
    }

    private fun collectLibraryArtifacts(
        metadata: JsonObject,
    ): Map<String, ArtifactSpec> {
        val artifacts = linkedMapOf<String, ArtifactSpec>()
        metadata.requiredArray("libraries").forEach { element ->
            val library = element.jsonObject
            val downloads = library["downloads"]?.jsonObject ?: return@forEach
            val candidates = buildList {
                downloads["artifact"]?.jsonObject?.let { add("artifact" to it) }
                downloads["classifiers"]?.jsonObject?.forEach { (name, value) ->
                    add("classifier $name" to value.jsonObject)
                }
            }
            candidates.forEach { (_, value) ->
                val sha1 = value.requiredString("sha1").lowercase()
                val size = value.requiredLong("size")
                val url = value.requiredString("url")
                val path = value.requiredString("path")
                val previous = artifacts.put(
                    path,
                    ArtifactSpec(sha1, size, url),
                )
                check(previous == null || previous == ArtifactSpec(sha1, size, url))
            }
        }
        return artifacts
    }

    private data class ArtifactSpec(
        val sha1: String,
        val size: Long,
        val url: String,
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftAssetsTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadOfficialMinecraftAssetsTask : DefaultTask() {
    companion object {
        private const val ASSET_CONCURRENCY = 32
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val assetIndexesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val indexesDir = assetIndexesDir.asFile.get().toPath()
        check(Files.isDirectory(indexesDir)) {
            "Asset indexes directory does not exist: $indexesDir"
        }
        val indexFiles = Files.list(indexesDir)
            .filter { it.isRegularFile() && it.name.endsWith(".json") }
            .toList()
        check(indexFiles.size == 1) {
            "Expected exactly one asset index file in $indexesDir, " +
                    "found ${indexFiles.size}: ${indexFiles.map { it.name }}"
        }
        val index = indexFiles.single().readJsonObject()
        val objects = index.requiredObject("objects")
        val root = outputDirectory.asFile.get().toPath()

        val assets = linkedMapOf<String, Long>()
        objects.forEach { (_, element) ->
            val value = element.jsonObject
            val hash = value.requiredString("hash").lowercase()
            val size = value.requiredLong("size")
            assets[hash] = size
        }

        logger.lifecycle(
            "Downloading ${assets.size} official asset objects (concurrency=$ASSET_CONCURRENCY)",
        )
        runBlocking {
            val semaphore = Semaphore(ASSET_CONCURRENCY)
            coroutineScope {
                assets.entries.map { (hash, size) ->
                    async {
                        semaphore.acquire()
                        try {
                            val relative = "${hash.take(2)}/$hash"
                            ProtocolHttp.ensureDownload(
                                url = "https://resources.download.minecraft.net/$relative",
                                destination = root.resolve(relative),
                                expectedSize = size,
                                expectedSha1 = hash,
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                }.forEach { it.await() }
            }
        }
        logger.lifecycle(
            "Downloaded ${assets.size} official asset objects",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PrepareHeadlessMcClientTask
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Creates the standard Minecraft launcher directory layout that HeadlessMC
 * expects under `-Dhmc.mcdir`:
 *   `versions/<version>/<version>.jar`
 *   `versions/<version>/<version>.json`
 *
 * This task depends on [DownloadOfficialMinecraftClientTask] for the client
 * JAR and on [DownloadVersionMetadataTask] for the version metadata.
 */
@CacheableTask
abstract class PrepareHeadlessMcClientTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val clientJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionMetadata: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val version = minecraftVersion.get()
        val versionsDir = outputDirectory.asFile.get().toPath()
        versionsDir.createDirectories()

        val clientJarSrc = clientJar.asFile.get().toPath()
        val clientSha1 = clientJarSrc.sha1()
        val jarDst = versionsDir.resolve("$version.jar")
        if (!jarDst.isRegularFile() || jarDst.sha1() != clientSha1) {
            clientJarSrc.copyTo(jarDst, overwrite = true)
        }

        val metadataSrc = versionMetadata.asFile.get().toPath()
        val metadataSha1 = metadataSrc.sha1()
        val jsonDst = versionsDir.resolve("$version.json")
        if (!jsonDst.isRegularFile() || jsonDst.sha1() != metadataSha1) {
            metadataSrc.copyTo(jsonDst, overwrite = true)
        }

        logger.lifecycle(
            "Prepared HeadlessMC versions directory for $version: $versionsDir",
        )
    }
}
