package com.hiczp.minecraft.buildlogic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*
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
import kotlin.io.path.readText

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
            val byteArray = ProtocolHttp.getBytes(
                url = url,
                offline = offline.get(),
            ) { downloaded ->
                val containsRelease = protocolJson.decodeFromString<JsonObject>(downloaded.decodeToString())
                    .getValue("versions")
                    .jsonArray
                    .map { it.jsonObject }
                    .any {
                        it.getValue("id").jsonPrimitive.content == version &&
                                it.getValue("type").jsonPrimitive.content == "release"
                    }
                check(containsRelease) {
                    "Mojang manifest has no stable release $version"
                }
            }
            destination.parent.createDirectories()
            destination.atomicWrite(byteArray)
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
        val manifest = protocolJson.decodeFromString<JsonObject>(
            manifestFile.asFile.get().toPath().readText(),
        )
        val entry = manifest.getValue("versions").jsonArray
            .map { it.jsonObject }
            .firstOrNull {
                it.getValue("id").jsonPrimitive.content == version &&
                        it.getValue("type").jsonPrimitive.content == "release"
            }
            ?: error("Mojang manifest has no stable release $version")
        val metadataUrl = entry.getValue("url").jsonPrimitive.content
        val destination = outputFile.asFile.get().toPath()
        runBlocking {
            val byteArray = ProtocolHttp.getBytes(
                url = metadataUrl,
                offline = offline.get(),
            ) { downloaded ->
                val metadata = protocolJson.decodeFromString<JsonObject>(downloaded.decodeToString())
                check(metadata.getValue("id").jsonPrimitive.content == version) {
                    "Mojang version metadata identifies a different release"
                }
            }
            destination.parent.createDirectories()
            destination.atomicWrite(byteArray)
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
abstract class DownloadHmcSpecificsTask : DefaultTask() {
    @get:Input
    abstract val releaseTag: Property<String>

    @get:Input
    abstract val assetName: Property<String>

    @get:Input
    abstract val assetUrl: Property<String>

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
        runBlocking {
            ProtocolHttp.download(
                url = assetUrl.get(),
                destination = destination,
                offline = offline.get(),
            )
        }
        val metadata = protocolJson.decodeFromString<JsonObject>(destination.readZipEntry("fabric.mod.json").decodeToString())
        check(metadata.getValue("id").jsonPrimitive.content == "headlessmc") {
            "HMC-Specifics Fabric artifact has an unexpected mod id"
        }
        val dependencies = metadata.getValue("depends").jsonObject
        check(dependencies.getValue("fabricloader").jsonPrimitive.content == ">=0.14.19") {
            "HMC-Specifics declares an unexpected Fabric Loader requirement"
        }
        val expectedMinecraft = "~${minecraftVersion.get()}.0"
        check(dependencies.getValue("minecraft").jsonPrimitive.content == expectedMinecraft) {
            "HMC-Specifics does not target Minecraft ${minecraftVersion.get()}"
        }
        logger.lifecycle(
            "Downloaded HMC-Specifics ${releaseTag.get()} Fabric asset ${assetName.get()}: $destination",
        )
    }
}

@CacheableTask
abstract class DownloadFabricLoaderProfileTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Input
    abstract val fabricLoaderVersion: Property<String>

    @get:Input
    abstract val profileUrl: Property<String>

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
        runBlocking {
            val url = profileUrl.get()
            val byteArray = ProtocolHttp.getBytes(
                url = url,
                offline = offline.get(),
            ) { downloaded ->
                val profile = protocolJson.decodeFromString<JsonObject>(downloaded.decodeToString())
                val minecraft = minecraftVersion.get()
                val loader = fabricLoaderVersion.get()
                check(profile.getValue("id").jsonPrimitive.content == "fabric-loader-$loader-$minecraft") {
                    "Fabric profile has an unexpected identity"
                }
                check(profile.getValue("inheritsFrom").jsonPrimitive.content == minecraft) {
                    "Fabric profile inherits from a different Minecraft release"
                }
                check(
                    profile.getValue("mainClass").jsonPrimitive.content ==
                            "net.fabricmc.loader.impl.launch.knot.KnotClient",
                ) {
                    "Fabric profile has an unexpected client main class"
                }
                check(
                    profile.getValue("libraries").jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                        .contains("net.fabricmc:fabric-loader:$loader"),
                ) {
                    "Fabric profile does not contain the selected loader"
                }
            }
            destination.parent.createDirectories()
            destination.atomicWrite(byteArray)
        }
        logger.lifecycle(
            "Downloaded Fabric Loader ${fabricLoaderVersion.get()} profile for Minecraft ${minecraftVersion.get()}: $destination",
        )
    }
}

@CacheableTask
abstract class DownloadHeadlessMcLauncherTask : DefaultTask() {
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
            ProtocolHttp.download(
                url = "https://github.com/headlesshq/headlessmc/releases/download/$version/headlessmc-launcher-wrapper-$version.jar",
                destination = destination,
                offline = offline.get(),
            )
        }
        logger.lifecycle(
            "Downloaded HeadlessMC launcher wrapper: $destination",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadHeadlessMcDummyFilesTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadHeadlessMcAssetReplacementsTask : DefaultTask() {
    @get:Input
    abstract val headlessMcVersion: Property<String>

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val dummyOggFile: RegularFileProperty

    @get:OutputFile
    abstract val dummyPngFile: RegularFileProperty

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
                        ProtocolHttp.download(
                            url = "$sourceRoot/dummy.ogg",
                            destination = dummyOggFile.asFile.get().toPath(),
                            offline = offline.get(),
                        )
                    },
                    async {
                        ProtocolHttp.download(
                            url = "$sourceRoot/dummy.png",
                            destination = dummyPngFile.asFile.get().toPath(),
                            offline = offline.get(),
                        )
                    },
                ).awaitAll()
            }
        }
        logger.lifecycle(
            "Downloaded HeadlessMC $version binary asset replacements",
        )
    }
}

@CacheableTask
abstract class GenerateHeadlessMcJsonReplacementTask : DefaultTask() {
    @get:Input
    abstract val headlessMcVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val output = outputFile.asFile.get().toPath()
        output.atomicWrite("{}".encodeToByteArray())
        logger.lifecycle(
            "Generated HeadlessMC ${headlessMcVersion.get()} JSON asset replacement: $output",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadMinecraftClientJarTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadMinecraftClientJarTask : DefaultTask() {
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
        val metadata = protocolJson.decodeFromString<JsonObject>(
            metadataFile.asFile.get().toPath().readText(),
        )
        check(metadata.getValue("id").jsonPrimitive.content == version)
        val client = metadata.getValue("downloads").jsonObject.getValue("client").jsonObject
        val destination = clientJar.asFile.get().toPath()
        runBlocking {
            ProtocolHttp.download(
                url = client.getValue("url").jsonPrimitive.content,
                destination = destination,
                offline = offline.get(),
            )
        }
        logger.lifecycle(
            "Downloaded official client JAR: $destination",
        )
        val assetIndex = metadata.getValue("assetIndex").jsonObject
        downloadMetadataFile.asFile.get().toPath().writeJson(
            buildJsonObject {
                put("schema_version", 1)
                put("minecraft_version", version)
                put("client_url", client.getValue("url").jsonPrimitive.content)
                put("library_count", collectClientLibraryArtifacts(metadata).size)
                put("asset_index_id", assetIndex.getValue("id").jsonPrimitive.content)
                put("asset_index_url", assetIndex.getValue("url").jsonPrimitive.content)
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadMinecraftClientLibrariesTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadMinecraftClientLibrariesTask : DefaultTask() {
    companion object {
        private const val LIBRARY_CONCURRENCY = 8
    }

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fabricProfileFile: RegularFileProperty

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
        val metadata = protocolJson.decodeFromString<JsonObject>(
            metadataFile.asFile.get().toPath().readText(),
        )
        check(metadata.getValue("id").jsonPrimitive.content == version)
        val libraries = linkedMapOf<String, ClientArtifactSpec>().apply {
            putAll(collectClientLibraryArtifacts(metadata))
            collectFabricLibraryArtifacts(
                protocolJson.decodeFromString<JsonObject>(fabricProfileFile.asFile.get().toPath().readText()),
            ).forEach { (path, clientArtifactSpec) ->
                val previous = put(path, clientArtifactSpec)
                check(previous == null || previous == clientArtifactSpec) {
                    "Minecraft and Fabric profiles map $path to different library artifacts"
                }
            }
        }
        val staging = createIsolatedTemporaryDirectory("client-libraries")
        logger.lifecycle(
            "Downloading ${libraries.size} official client library artifacts (concurrency=$LIBRARY_CONCURRENCY)",
        )
        val sorted = libraries.entries.sortedBy { it.key }
        try {
            runBlocking {
                coroutineScope {
                    val semaphore = Semaphore(LIBRARY_CONCURRENCY)
                    sorted.map { (relative, clientArtifactSpec) ->
                        async {
                            semaphore.acquire()
                            try {
                                ProtocolHttp.download(
                                    url = clientArtifactSpec.url,
                                    destination = staging.resolve(relative),
                                    offline = offline.get(),
                                )
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
                }
            }
            fileSystemOperations.sync { syncSpec ->
                syncSpec.from(staging)
                syncSpec.into(librariesDirectory)
            }
        } finally {
            staging.deleteTree()
        }
        logger.lifecycle(
            "Downloaded ${libraries.size} official client library artifacts",
        )
    }

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations
}

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftAssetIndexTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadMinecraftClientAssetIndexTask : DefaultTask() {
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
        val metadata = protocolJson.decodeFromString<JsonObject>(
            metadataFile.asFile.get().toPath().readText(),
        )
        check(metadata.getValue("id").jsonPrimitive.content == version)
        val assetIndex = metadata.getValue("assetIndex").jsonObject
        val assetIndexId = assetIndex.getValue("id").jsonPrimitive.content
        val output = assetIndexesDirectory.asFile.get().toPath()
        val staging = createIsolatedTemporaryDirectory("asset-index")
        val destination = output.resolve("$assetIndexId.json")
        try {
            runBlocking {
                ProtocolHttp.download(
                    url = assetIndex.getValue("url").jsonPrimitive.content,
                    destination = staging.resolve("$assetIndexId.json"),
                    offline = offline.get(),
                )
            }
            fileSystemOperations.sync { syncSpec ->
                syncSpec.from(staging)
                syncSpec.into(assetIndexesDirectory)
            }
        } finally {
            staging.deleteTree()
        }
        logger.lifecycle(
            "Downloaded official asset index: $destination",
        )
    }

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations
}

private fun collectClientLibraryArtifacts(
    metadata: JsonObject,
): Map<String, ClientArtifactSpec> {
    val artifacts = linkedMapOf<String, ClientArtifactSpec>()
    metadata.getValue("libraries").jsonArray.forEach { jsonElement ->
        val library = jsonElement.jsonObject
        val downloads = library["downloads"]?.jsonObject ?: return@forEach
        val candidates = buildList {
            downloads["artifact"]?.jsonObject?.let { add(it) }
            downloads["classifiers"]?.jsonObject?.values?.forEach {
                add(it.jsonObject)
            }
        }
        candidates.forEach { jsonObject ->
            val clientArtifactSpec = ClientArtifactSpec(
                url = jsonObject.getValue("url").jsonPrimitive.content,
            )
            val path = jsonObject.getValue("path").jsonPrimitive.content
            val previous = artifacts.put(path, clientArtifactSpec)
            check(previous == null || previous == clientArtifactSpec)
        }
    }
    return artifacts
}

private fun collectFabricLibraryArtifacts(
    profile: JsonObject,
): Map<String, ClientArtifactSpec> = profile.getValue("libraries").jsonArray
    .associate { jsonElement ->
        val library = jsonElement.jsonObject
        val coordinate = library.getValue("name").jsonPrimitive.content
        val fields = coordinate.split(':')
        check(fields.size == 3 && fields.all(String::isNotBlank)) {
            "Unsupported Fabric library coordinate: $coordinate"
        }
        val group = fields[0].replace('.', '/')
        val artifact = fields[1]
        val version = fields[2]
        val relative = "$group/$artifact/$version/$artifact-$version.jar"
        val repository = library.getValue("url").jsonPrimitive.content.trimEnd('/')
        relative to ClientArtifactSpec(url = "$repository/$relative")
    }

private data class ClientArtifactSpec(
    val url: String,
)

// ═══════════════════════════════════════════════════════════════════════════════
// DownloadOfficialMinecraftAssetsTask
// ═══════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class DownloadMinecraftClientAssetObjectsTask : DefaultTask() {
    companion object {
        private const val ASSET_CONCURRENCY = 8
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetIndexesDirectory: DirectoryProperty

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
        val assets = readOfficialClientAssets(
            assetIndexesDirectory.asFile.get().toPath(),
            REPLACED_ASSET_FORMATS,
        )
        val root = outputDirectory.asFile.get().toPath()
        val officialAssets = assets.filter { it.dummyFormat == null }
        val staging = createIsolatedTemporaryDirectory("asset-objects")
        logger.lifecycle(
            "Downloading ${officialAssets.size} official client asset objects without HeadlessMC replacements (concurrency=$ASSET_CONCURRENCY)",
        )
        try {
            runBlocking {
                val semaphore = Semaphore(ASSET_CONCURRENCY)
                coroutineScope {
                    officialAssets.map { officialClientAsset ->
                        async {
                            semaphore.acquire()
                            try {
                                ProtocolHttp.download(
                                    url = "https://resources.download.minecraft.net/${officialClientAsset.relativePath}",
                                    destination = staging.resolve(officialClientAsset.relativePath),
                                    offline = offline.get(),
                                )
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
                }
            }
            fileSystemOperations.sync { syncSpec ->
                syncSpec.from(staging)
                syncSpec.into(outputDirectory)
            }
        } finally {
            staging.deleteTree()
        }
        val expectedPaths = officialAssets.mapTo(mutableSetOf()) {
            it.relativePath
        }
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
            "Downloaded ${officialAssets.size} original client asset objects; ${assets.size - officialAssets.size} objects are supplied by HeadlessMC replacements",
        )
    }
}

@CacheableTask
abstract class AssembleHeadlessClientAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetIndexesDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val originalObjectsDirectory: DirectoryProperty

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

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun assemble() {
        val indexes = assetIndexesDirectory.asFile.get().toPath()
        val assets = readOfficialClientAssets(
            indexes,
            REPLACED_ASSET_FORMATS,
        )
        val replacements = mapOf(
            "ogg" to dummyOggFile.asFile.get(),
            "png" to dummyPngFile.asFile.get(),
            "json" to dummyJsonFile.asFile.get(),
        )
        fileSystemOperations.sync { syncSpec ->
            syncSpec.from(assetIndexesDirectory) { copySpec ->
                copySpec.into("indexes")
            }
            syncSpec.from(originalObjectsDirectory) { copySpec ->
                copySpec.into("objects")
            }
            assets.filter { it.dummyFormat != null }.forEach { officialClientAsset ->
                syncSpec.from(replacements.getValue(officialClientAsset.dummyFormat!!)) { copySpec ->
                    copySpec.into("objects/${officialClientAsset.hash.take(2)}")
                    copySpec.rename { officialClientAsset.hash }
                }
            }
            syncSpec.into(outputDirectory)
        }
        val indexFiles = Files.list(indexes).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(".json") }
                .toList()
        }
        val expectedPaths = assets.mapTo(mutableSetOf()) {
            "objects/${it.relativePath}"
        }.apply {
            add("indexes/${indexFiles.single().name}")
        }
        val root = outputDirectory.asFile.get().toPath()
        val actualPaths = Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .map { root.relativize(it).joinToString("/") }
                .toList()
                .toSet()
        }
        check(actualPaths == expectedPaths) {
            val missing = expectedPaths - actualPaths
            val unexpected = actualPaths - expectedPaths
            "Assembled assets do not match the selected index; missing=$missing, unexpected=$unexpected"
        }
        logger.lifecycle(
            "Assembled ${assets.size} HeadlessMC client asset objects: $root",
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
    protocolJson.decodeFromString<JsonObject>(indexFiles.single().readText())
        .getValue("objects")
        .jsonObject
        .forEach { (name, jsonElement) ->
            val jsonObject = jsonElement.jsonObject
            val format = name.substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase()
            val officialClientAsset = OfficialClientAsset(
                hash = jsonObject.getValue("hash").jsonPrimitive.content.lowercase(),
                dummyFormat = format.takeIf(dummyFormats::contains),
            )
            val previous = assets.putIfAbsent(officialClientAsset.hash, officialClientAsset)
            check(previous == null || previous == officialClientAsset) {
                "Asset index maps ${officialClientAsset.hash} to incompatible formats"
            }
        }
    return assets.values.toList()
}

private data class OfficialClientAsset(
    val hash: String,
    val dummyFormat: String?,
) {
    val relativePath: String
        get() = "${hash.take(2)}/$hash"
}

private val REPLACED_ASSET_FORMATS = setOf("ogg", "png", "json")
