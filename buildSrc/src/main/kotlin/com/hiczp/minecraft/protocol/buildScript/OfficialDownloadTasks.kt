package com.hiczp.minecraft.protocol.buildScript

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
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

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        offline.convention(false)
    }

    @TaskAction
    fun download() {
        val destination = outputFile.asFile.get().toPath()
        val version = minecraftVersion.get()
        runBlocking {
            val url = manifestUrl.get()
            val bytes = ProtocolHttp.getBytes(
                url = url,
                offline = offline.get(),
            ) { downloaded ->
                val containsRelease = downloaded.decodeJsonObject(url)
                    .requiredArray("versions")
                    .map { it.jsonObject }
                    .any {
                        it.requiredString("id") == version &&
                                it.requiredString("type") == "release"
                    }
                check(containsRelease) {
                    "Mojang manifest has no stable release $version"
                }
            }
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

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        offline.convention(false)
    }

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
            val bytes = ProtocolHttp.getBytes(
                url = metadataUrl,
                offline = offline.get(),
            ) { downloaded ->
                check(downloaded.sha1() == expectedSha1) {
                    "Mojang version metadata failed its manifest SHA-1"
                }
                val metadata = downloaded.decodeJsonObject(metadataUrl)
                check(metadata.requiredString("id") == version) {
                    "Mojang version metadata identifies a different release"
                }
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
    @get:Input
    abstract val headlessMcVersion: Property<String>

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val launcherFile: RegularFileProperty

    init {
        offline.convention(false)
    }

    @TaskAction
    fun download() {
        val version = headlessMcVersion.get()
        val destination = launcherFile.asFile.get().toPath()
        runBlocking {
            ProtocolHttp.downloadVerifiedSha256(
                url = "https://github.com/headlesshq/headlessmc/releases/download/$version/headlessmc-launcher-$version.jar",
                destination = destination,
                expectedSize = HeadlessMcTarget.LAUNCHER_SIZE,
                expectedSha256 = HeadlessMcTarget.LAUNCHER_SHA256,
                offline = offline.get(),
            )
        }
        logger.lifecycle(
            "Downloaded and verified HeadlessMC launcher: $destination",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadHeadlessMcDummyFilesTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadHeadlessMcDummyFilesTask : DefaultTask() {
    @get:Input
    abstract val headlessMcVersion: Property<String>

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val dummyOggFile: RegularFileProperty

    @get:OutputFile
    abstract val dummyPngFile: RegularFileProperty

    @get:OutputFile
    abstract val dummyJsonFile: RegularFileProperty

    init {
        offline.convention(false)
    }

    @TaskAction
    fun download() {
        val version = headlessMcVersion.get()
        val sourceRoot =
            "https://raw.githubusercontent.com/headlesshq/headlessmc/$version/headlessmc-launcher/src/main/resources/assets"
        runBlocking {
            coroutineScope {
                listOf(
                    async {
                        ProtocolHttp.downloadVerifiedSha256(
                            url = "$sourceRoot/dummy.ogg",
                            destination = dummyOggFile.asFile.get().toPath(),
                            expectedSize = HeadlessMcTarget.DUMMY_OGG_SIZE,
                            expectedSha256 = HeadlessMcTarget.DUMMY_OGG_SHA256,
                            offline = offline.get(),
                        )
                    },
                    async {
                        ProtocolHttp.downloadVerifiedSha256(
                            url = "$sourceRoot/dummy.png",
                            destination = dummyPngFile.asFile.get().toPath(),
                            expectedSize = HeadlessMcTarget.DUMMY_PNG_SIZE,
                            expectedSha256 = HeadlessMcTarget.DUMMY_PNG_SHA256,
                            offline = offline.get(),
                        )
                    },
                ).awaitAll()
            }
        }
        dummyJsonFile.asFile.get().toPath()
            .atomicWrite("{}".encodeToByteArray())
        logger.lifecycle(
            "Downloaded and verified HeadlessMC $version dummy files and created its JSON replacement",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftClientTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadOfficialMinecraftClientTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:OutputFile
    abstract val clientJar: RegularFileProperty

    @get:OutputFile
    abstract val downloadMetadataFile: RegularFileProperty

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
        val client = metadata.requiredObject("downloads")
            .requiredObject("client")
        val destination = clientJar.asFile.get().toPath()
        runBlocking {
            ProtocolHttp.downloadVerified(
                url = client.requiredString("url"),
                destination = destination,
                expectedSize = client.requiredLong("size"),
                expectedSha1 = client.requiredString("sha1").lowercase(),
                offline = offline.get(),
            )
        }
        logger.lifecycle(
            "Downloaded and verified official client JAR: $destination",
        )
        val assetIndex = metadata.requiredObject("assetIndex")
        downloadMetadataFile.asFile.get().toPath().writeJson(
            jsonObjectOf(
                "schema_version" to jsonNumber(1),
                "minecraft_version" to jsonString(version),
                "client_sha1" to jsonString(
                    client.requiredString("sha1").lowercase(),
                ),
                "client_url" to jsonString(client.requiredString("url")),
                "library_count" to jsonNumber(
                    collectClientLibraryArtifacts(metadata).size,
                ),
                "asset_index_id" to jsonString(
                    assetIndex.requiredString("id"),
                ),
                "asset_index_sha1" to jsonString(
                    assetIndex.requiredString("sha1").lowercase(),
                ),
            ),
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftClientLibrariesTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadOfficialMinecraftClientLibrariesTask : DefaultTask() {
    companion object {
        private const val LIBRARY_CONCURRENCY = 8
    }

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:OutputDirectory
    abstract val librariesDirectory: DirectoryProperty

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
        val libraries = collectClientLibraryArtifacts(metadata)
        val output = librariesDirectory.asFile.get().toPath()
        logger.lifecycle(
            "Downloading ${libraries.size} official client library artifacts (concurrency=$LIBRARY_CONCURRENCY)",
        )
        val sorted = libraries.entries.sortedBy { it.key }
        runBlocking {
            coroutineScope {
                val semaphore = Semaphore(LIBRARY_CONCURRENCY)
                sorted.map { (relative, artifact) ->
                    async {
                        semaphore.acquire()
                        try {
                            ProtocolHttp.downloadVerified(
                                url = artifact.url,
                                destination = output.resolve(relative),
                                expectedSize = artifact.size,
                                expectedSha1 = artifact.sha1,
                                offline = offline.get(),
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
        }
        logger.lifecycle(
            "Downloaded and verified ${libraries.size} official client library artifacts",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftAssetIndexTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadOfficialMinecraftAssetIndexTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:OutputDirectory
    abstract val assetIndexesDirectory: DirectoryProperty

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
        val assetIndex = metadata.requiredObject("assetIndex")
        val assetIndexId = assetIndex.requiredString("id")
        val destination = assetIndexesDirectory.asFile.get().toPath()
            .resolve("$assetIndexId.json")
        runBlocking {
            ProtocolHttp.downloadVerified(
                url = assetIndex.requiredString("url"),
                destination = destination,
                expectedSize = assetIndex.requiredLong("size"),
                expectedSha1 = assetIndex.requiredString("sha1").lowercase(),
                offline = offline.get(),
            )
        }
        logger.lifecycle(
            "Downloaded and verified official asset index: $destination",
        )
    }
}

private fun collectClientLibraryArtifacts(
    metadata: JsonObject,
): Map<String, ClientArtifactSpec> {
    val artifacts = linkedMapOf<String, ClientArtifactSpec>()
    metadata.requiredArray("libraries").forEach { element ->
        val library = element.jsonObject
        val downloads = library["downloads"]?.jsonObject ?: return@forEach
        val candidates = buildList {
            downloads["artifact"]?.jsonObject?.let { add(it) }
            downloads["classifiers"]?.jsonObject?.values?.forEach {
                add(it.jsonObject)
            }
        }
        candidates.forEach { value ->
            val artifact = ClientArtifactSpec(
                sha1 = value.requiredString("sha1").lowercase(),
                size = value.requiredLong("size"),
                url = value.requiredString("url"),
            )
            val path = value.requiredString("path")
            val previous = artifacts.put(path, artifact)
            check(previous == null || previous == artifact)
        }
    }
    return artifacts
}

private data class ClientArtifactSpec(
    val sha1: String,
    val size: Long,
    val url: String,
)

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftAssetsTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadOfficialMinecraftAssetsTask : DefaultTask() {
    companion object {
        private const val ASSET_CONCURRENCY = 8
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val assetIndexesDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dummyOggFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dummyPngFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dummyJsonFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val offline: Property<Boolean>

    init {
        offline.convention(false)
    }

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun download() {
        val dummyAssets = mapOf(
            "ogg" to dummyOggFile.asFile.get(),
            "png" to dummyPngFile.asFile.get(),
            "json" to dummyJsonFile.asFile.get(),
        )
        val assets = readOfficialClientAssets(
            assetIndexesDirectory.asFile.get().toPath(),
            dummyAssets.keys,
        )
        val root = outputDirectory.asFile.get().toPath()
        val officialAssets = assets.filter { it.dummyFormat == null }
        logger.lifecycle(
            "Downloading ${officialAssets.size} official client asset objects without HeadlessMC replacements (concurrency=$ASSET_CONCURRENCY)",
        )
        runBlocking {
            val semaphore = Semaphore(ASSET_CONCURRENCY)
            coroutineScope {
                officialAssets.map { asset ->
                    async {
                        semaphore.acquire()
                        try {
                            ProtocolHttp.downloadVerified(
                                url = "https://resources.download.minecraft.net/${asset.relativePath}",
                                destination = root.resolve(asset.relativePath),
                                expectedSize = asset.size,
                                expectedSha1 = asset.hash,
                                offline = offline.get(),
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
        }
        fileSystemOperations.sync { sync ->
            sync.preserve { preserved ->
                preserved.include(officialAssets.map { it.relativePath })
            }
            assets.filter { it.dummyFormat != null }.forEach { asset ->
                sync.from(dummyAssets.getValue(asset.dummyFormat!!)) { copy ->
                    copy.into(asset.hash.take(2))
                    copy.rename { asset.hash }
                }
            }
            sync.into(outputDirectory)
        }
        val expectedPaths = assets.mapTo(mutableSetOf()) { it.relativePath }
        val actualPaths = Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .map { root.relativize(it).joinToString("/") }
                .toList()
                .toSet()
        }
        check(actualPaths == expectedPaths) {
            val missing = expectedPaths - actualPaths
            val unexpected = actualPaths - expectedPaths
            "Prepared asset objects do not match the official index; missing=$missing, unexpected=$unexpected"
        }
        logger.lifecycle(
            "Downloaded ${officialAssets.size} verified official and wrote ${assets.size - officialAssets.size} HeadlessMC dummy asset objects",
        )
    }
}

private fun readOfficialClientAssets(
    indexesDirectory: Path,
    dummyFormats: Set<String>,
): List<OfficialClientAsset> {
    check(Files.isDirectory(indexesDirectory)) {
        "Asset indexes directory does not exist: $indexesDirectory"
    }
    val indexFiles = Files.list(indexesDirectory).use { paths ->
        paths.filter { it.isRegularFile() && it.name.endsWith(".json") }
            .toList()
    }
    check(indexFiles.size == 1) {
        "Expected exactly one asset index file in $indexesDirectory, found ${indexFiles.size}: ${indexFiles.map { it.name }}"
    }
    val assets = linkedMapOf<String, OfficialClientAsset>()
    indexFiles.single().readJsonObject()
        .requiredObject("objects")
        .forEach { (name, element) ->
            val value = element.jsonObject
            val format = name.substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase()
            val asset = OfficialClientAsset(
                hash = value.requiredString("hash").lowercase(),
                size = value.requiredLong("size"),
                dummyFormat = format.takeIf(dummyFormats::contains),
            )
            val previous = assets.putIfAbsent(asset.hash, asset)
            check(previous == null || previous == asset) {
                "Asset index maps ${asset.hash} to incompatible formats"
            }
        }
    return assets.values.toList()
}

private data class OfficialClientAsset(
    val hash: String,
    val size: Long,
    val dummyFormat: String?,
) {
    val relativePath: String
        get() = "${hash.take(2)}/$hash"
}
