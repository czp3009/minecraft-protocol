package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

internal const val VERSION_MANIFEST_URL =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
private const val ASSET_OBJECT_BASE_URL =
    "https://resources.download.minecraft.net"
private val sha1Pattern = Regex("[0-9a-f]{40}")

@CacheableTask
abstract class DownloadOfficialMinecraftServerTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val serverJar: RegularFileProperty

    @get:OutputFile
    abstract val metadataFile: RegularFileProperty

    init {
        offline.convention(false)
    }

    @TaskAction
    fun download() {
        val version = minecraftVersion.get()
        require(version.matches(Regex("[0-9A-Za-z._-]+"))) {
            "Unsafe Minecraft version identifier: $version"
        }
        val metadata = resolveServerDownload(version)
        val serverJar = serverJar.asFile.get().toPath()
        val changed = ProtocolHttp.ensureDownload(
            url = metadata.requiredString("server_url"),
            destination = serverJar,
            expectedSize = metadata.requiredLong("server_size"),
            expectedSha1 = metadata.requiredString("server_sha1"),
            offline = offline.get(),
        )
        metadataFile.asFile.get().toPath().writeJson(
            metadata,
            sortKeys = true,
        )
        val action = if (changed) "Downloaded and verified" else "Verified"
        logger.lifecycle(
            "$action Mojang server: $serverJar (${serverJar.sha1()})",
        )
    }

    private fun resolveServerDownload(version: String): JsonObject {
        val manifest = ProtocolHttp.getJson(VERSION_MANIFEST_URL)
        val entry = manifest.requiredArray("versions")
            .map { it.jsonObject }
            .firstOrNull {
                it.requiredString("id") == version &&
                        it.requiredString("type") == "release"
            }
            ?: error("Mojang manifest has no stable release $version")
        val metadataUrl = entry.requiredString("url")
        val versionMetadata = ProtocolHttp.getJson(metadataUrl)
        val server = versionMetadata.requiredObject("downloads")
            .requiredObject("server")
        val javaMajor = versionMetadata["javaVersion"]
            ?.jsonObject
            ?.requiredInt("majorVersion")
            ?: 0
        return jsonObjectOf(
            "minecraft_version" to jsonString(version),
            "version_metadata_url" to jsonString(metadataUrl),
            "version_metadata_sha1" to
                    jsonString(entry.requiredString("sha1")),
            "server_url" to jsonString(server.requiredString("url")),
            "server_sha1" to jsonString(server.requiredString("sha1")),
            "server_size" to jsonNumber(server.requiredLong("size")),
            "java_major_version" to jsonNumber(javaMajor),
        )
    }
}

@CacheableTask
abstract class GenerateOfficialServerPropertiesTask :
    MinecraftProtocolToolTask() {
    @get:Internal
    abstract val javaExecutable: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val target = repository.readMinecraftProtocolTarget()
        val serverJar = serverJar.asFile.get().toPath()
        check(serverJar.isRegularFile()) {
            "Official server is missing; run downloadOfficialMinecraftServer"
        }
        val workDirectory = temporaryDir.toPath()
        workDirectory.deleteTree()
        workDirectory.createDirectories()
        val result = runProcess(
            listOf(
                javaExecutable.get(),
                "-Djava.awt.headless=true",
                "-jar",
                serverJar.toString(),
                "--initSettings",
                "nogui",
            ),
            workingDirectory = workDirectory,
            timeout = Duration.ofMinutes(2),
        )
        check(result.exitCode == 0) {
            "Official server --initSettings exited with " +
                    "${result.exitCode}:\n${result.output}"
        }
        val propertiesPath = workDirectory.resolve("server.properties")
        check(propertiesPath.isRegularFile()) {
            "Official server did not generate server.properties"
        }
        val properties = Properties()
        Files.newBufferedReader(
            propertiesPath,
            StandardCharsets.UTF_8,
        ).use(properties::load)
        val entries = properties.stringPropertyNames()
            .sorted()
            .map { name ->
                val value = properties.getProperty(name)
                jsonObjectOf(
                    "name" to jsonString(name),
                    "default" to jsonString(
                        if (name == "management-server-secret") {
                            check(value.isNotEmpty()) {
                                "Official management-server-secret is empty"
                            }
                            "<generated-secret>"
                        } else {
                            value
                        },
                    ),
                )
            }
        val report = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString(target.minecraftVersion),
            "protocol_version" to jsonNumber(target.protocolVersion),
            "official_server_sha1" to jsonString(serverJar.sha1()),
            "property_count" to jsonNumber(entries.size),
            "properties" to JsonArray(entries),
        )
        val output = reportFile.asFile.get().toPath()
        output.writeJson(report)
        logger.lifecycle(
            "Generated ${entries.size} official server properties: $output",
        )
    }
}

@CacheableTask
abstract class GenerateOfficialMinecraftReportsTask :
    MinecraftProtocolToolTask() {
    @get:Internal
    abstract val javaExecutable: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val downloadMetadata: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = repository.readMinecraftProtocolTarget()
        val serverJar = serverJar.asFile.get().toPath()
        val metadata = downloadMetadata.asFile.get().toPath()
            .readJsonObject()
        check(serverJar.isRegularFile()) {
            "Official server is missing; run downloadOfficialMinecraftServer"
        }
        check(
            metadata.requiredString("minecraft_version") ==
                    target.minecraftVersion &&
                    metadata.requiredString("server_sha1") ==
                    serverJar.sha1(),
        ) {
            "Official server JAR does not match its Mojang metadata"
        }
        val outputDirectory = outputDirectory.asFile.get().toPath()
        val workDirectory = temporaryDir.toPath()
        workDirectory.deleteTree()
        workDirectory.createDirectories()
        val generatorOutput = workDirectory.resolve("generated")
        val packetsReport =
            generatorOutput.resolve("reports/packets.json")

        validateAnalysisJava(
            javaExecutable.get(),
            target.javaMajorVersion,
            target.minecraftVersion,
        )
        val command = listOf(
            javaExecutable.get(),
            "-DbundlerMainClass=net.minecraft.data.Main",
            "-jar",
            serverJar.toString(),
            "--reports",
            "--output",
            generatorOutput.toString(),
        )
        logger.lifecycle(
            "Running vanilla data generator for protocol reports...",
        )
        val result = runProcess(command, workDirectory)
        check(result.exitCode == 0) {
            "Vanilla data generator exited with ${result.exitCode}:\n" +
                    result.output.lineSequence().toList().takeLast(80)
                        .joinToString("\n")
        }
        check(packetsReport.isRegularFile()) {
            val candidates = Files.walk(generatorOutput).use { paths ->
                paths.filter {
                    Files.isRegularFile(it) &&
                            it.fileName.toString().contains(
                                "packet",
                                ignoreCase = true,
                            ) &&
                            it.fileName.toString().endsWith(".json")
                }.map { generatorOutput.relativize(it).toString() }
                    .sorted()
                    .toList()
            }
            "Vanilla data generator did not create reports/packets.json; " +
                    "packet-like outputs: " +
                    candidates.ifEmpty { listOf("none") }.joinToString()
        }
        outputDirectory.deleteTree()
        listOf(
            "packets.json",
            "registries.json",
            "blocks.json",
        ).forEach { name ->
            val source = generatorOutput.resolve("reports/$name")
            check(source.isRegularFile()) {
                "Vanilla data generator did not create reports/$name"
            }
            outputDirectory.resolve("reports/$name")
                .atomicWrite(Files.readAllBytes(source))
        }
        logger.lifecycle(
            "Generated official protocol reports: " +
                    outputDirectory.resolve("reports"),
        )
    }

    private fun validateAnalysisJava(
        executable: String,
        requiredMajor: Int,
        minecraftVersion: String,
    ) {
        val result = runProcess(
            listOf(executable, "-version"),
            timeout = Duration.ofSeconds(30),
        )
        val actualMajor = Regex("""version\s+"(\d+)(?:\.|")""")
            .find(result.output)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: 0
        check(result.exitCode == 0 && actualMajor >= requiredMajor) {
            "Minecraft $minecraftVersion analysis requires Java " +
                    "$requiredMajor or newer, but '$executable' reports Java " +
                    "${actualMajor.takeIf { it > 0 } ?: "unknown"}. Install the " +
                    "required JDK and let Gradle detect it (or set " +
                    "-Dorg.gradle.java.installations.paths=<jdk-home>). This " +
                    "analysis JDK does not change the library's Java/KMP targets."
        }
    }
}

@CacheableTask
abstract class UnpackOfficialMinecraftServerTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:OutputDirectory
    abstract val runtimeDirectory: DirectoryProperty

    @TaskAction
    fun unpack() {
        val version = minecraftVersion.get()
        val bundle = serverJar.asFile.get().toPath()
        val output = runtimeDirectory.asFile.get().toPath()
        output.deleteTree()
        output.createDirectories()
        ZipFile(bundle.toFile()).use { archive ->
            val versionsEntry = archive.getEntry("META-INF/versions.list")
                ?: error("Server bundle has no META-INF/versions.list")
            val fields = archive.getInputStream(versionsEntry).use {
                it.readBytes().toString(StandardCharsets.UTF_8).trim()
            }.split('\t')
            check(fields.size == 3) {
                "META-INF/versions.list has an unexpected shape"
            }
            val expectedSha256 = fields[0].lowercase()
            check(fields[1] == version) {
                "Server bundle contains ${fields[1]}, expected " +
                        version
            }
            check(expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
                "Nested server SHA-256 is malformed"
            }
            val entryName = "META-INF/versions/${fields[2]}"
            val entry = archive.getEntry(entryName)
                ?: error("Server bundle has no $entryName")
            val implementation = archive.getInputStream(entry).use {
                it.readBytes()
            }
            check(implementation.sha256() == expectedSha256) {
                "Nested server JAR failed its bundle SHA-256 verification"
            }
            output.resolve("server.jar").atomicWrite(implementation)

            val librariesEntry = archive.getEntry("META-INF/libraries.list")
                ?: error("Server bundle has no META-INF/libraries.list")
            val libraries = archive.getInputStream(librariesEntry).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }.lineSequence().filter(String::isNotBlank).toList()
            libraries.forEach { line ->
                val libraryFields = line.split('\t')
                check(libraryFields.size == 3) {
                    "META-INF/libraries.list has an unexpected row"
                }
                val digest = libraryFields[0].lowercase()
                check(digest.matches(Regex("[0-9a-f]{64}"))) {
                    "Bundled library SHA-256 is malformed"
                }
                val relative = libraryFields[2]
                val libraryEntryName = "META-INF/libraries/$relative"
                val libraryEntry = archive.getEntry(libraryEntryName)
                    ?: error("Server bundle has no $libraryEntryName")
                val content = archive.getInputStream(libraryEntry).use {
                    it.readBytes()
                }
                check(content.sha256() == digest) {
                    "Bundled library failed SHA-256 verification: $relative"
                }
                output.resolve("libraries")
                    .safeResolve(relative)
                    .atomicWrite(content)
            }
        }
        logger.lifecycle(
            "Extracted verified official server runtime: $output",
        )
    }
}

@CacheableTask
abstract class PrepareOfficialMinecraftClientTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:Internal
    abstract val workers: Property<Int>

    @get:OutputDirectory
    abstract val clientDirectory: DirectoryProperty

    init {
        offline.convention(false)
        workers.convention(12)
    }

    @TaskAction
    fun prepare() {
        val workerCount = workers.get()
        require(workerCount > 0) { "workers must be positive" }
        val version = minecraftVersion.get()
        require(version.matches(Regex("[0-9A-Za-z._-]+"))) {
            "Unsafe Minecraft version identifier: $version"
        }
        prepareClient(
            version,
            clientDirectory.asFile.get().toPath(),
            offline.get(),
            workerCount,
        )
    }

    private fun prepareClient(
        version: String,
        clientRoot: Path,
        offline: Boolean,
        workerCount: Int,
    ) {
        val (metadata, source) = acquireMetadata(
            clientRoot,
            version,
            offline,
        )
        val downloads = metadata.requiredObject("downloads")
        val client = artifactSpec(
            downloads.getValue("client").jsonObject,
            "client download",
            requirePath = false,
        )
        var downloadedFiles = if (
            ProtocolHttp.ensureDownload(
                client.url,
                clientRoot.resolve("versions/$version/$version.jar"),
                client.size,
                client.sha1,
                offline,
            )
        ) {
            1
        } else {
            0
        }

        val libraries = collectLibraryArtifacts(metadata)
        libraries.toSortedMap().forEach { (relative, artifact) ->
            val destination = clientRoot.resolve("libraries")
                .safeResolve(relative)
            if (
                ProtocolHttp.ensureDownload(
                    artifact.url,
                    destination,
                    artifact.size,
                    artifact.sha1,
                    offline,
                )
            ) {
                downloadedFiles++
            }
        }

        val assetIndexValue = metadata.requiredObject("assetIndex")
        val assetIndexId = assetIndexValue.requiredString("id")
        val assetIndexSha1 = validateSha1(
            assetIndexValue.requiredString("sha1"),
            "asset index",
        )
        val assetIndexSize = assetIndexValue.requiredLong("size")
        val assetIndexPath = clientRoot.resolve("assets/indexes")
            .safeResolve("$assetIndexId.json")
        if (
            ProtocolHttp.ensureDownload(
                assetIndexValue.requiredString("url"),
                assetIndexPath,
                assetIndexSize,
                assetIndexSha1,
                offline,
            )
        ) {
            downloadedFiles++
        }
        val assetIndex = assetIndexPath.readJsonObject()
        val assets = linkedMapOf<String, Long>()
        assetIndex.requiredObject("objects")
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

        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val sortedAssets = assets.entries.sortedBy { it.key }
            val futures = sortedAssets.map { (hash, size) ->
                executor.submit(Callable {
                    val relative = "${hash.take(2)}/$hash"
                    ProtocolHttp.ensureDownload(
                        "$ASSET_OBJECT_BASE_URL/$relative",
                        clientRoot.resolve("assets/objects")
                            .safeResolve(relative),
                        size,
                        hash,
                        offline,
                    )
                })
            }
            futures.forEachIndexed { index, future ->
                if (future.get()) downloadedFiles++
                val completed = index + 1
                if (
                    completed % 250 == 0 ||
                    completed == futures.size
                ) {
                    logger.lifecycle(
                        "Verified $completed/${futures.size} client assets",
                    )
                }
            }
        } finally {
            executor.shutdownNow()
        }

        val completion = jsonObjectOf(
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
        )
        clientRoot.resolve("preparation.json")
            .writeJson(completion, sortKeys = true)
        logger.lifecycle(
            "Official client $version is complete under $clientRoot: " +
                    "${libraries.size} library artifact(s), " +
                    "${assets.size} asset object(s), " +
                    "$downloadedFiles file(s) downloaded",
        )
    }

    private fun acquireMetadata(
        clientRoot: Path,
        version: String,
        offline: Boolean,
    ): Pair<JsonObject, JsonObject> {
        val metadataPath = clientRoot.resolve(
            "versions/$version/$version.json",
        )
        val sourcePath = clientRoot.resolve("version-metadata-source.json")
        loadCachedMetadata(
            metadataPath,
            sourcePath,
            version,
        )?.let {
            logger.lifecycle(
                "Using cached Mojang client metadata for $version",
            )
            return it
        }
        check(!offline) {
            "Official client metadata is not cached for $version"
        }
        val manifest = ProtocolHttp.getJson(VERSION_MANIFEST_URL)
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
        val data = ProtocolHttp.getBytes(metadataUrl)
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
        sourcePath.writeJson(source, sortKeys = true)
        logger.lifecycle("Cached Mojang client metadata for $version")
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
            if (
                source.requiredString("minecraft_version") != version ||
                metadataPath.sha1() != validateSha1(
                    source.requiredString("version_metadata_sha1"),
                    "source marker",
                )
            ) {
                return null
            }
            val metadata = metadataPath.readJsonObject()
            if (metadata.requiredString("id") != version) return null
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
                val downloadsElement = library["downloads"]
                val downloads = downloadsElement
                    ?.takeUnless { it === JsonNull }
                    ?.jsonObject
                    ?: return@forEachIndexed
                val candidates = buildList {
                    downloads["artifact"]?.let {
                        add("artifact" to it.jsonObject)
                    }
                    downloads["classifiers"]?.jsonObject?.forEach { (name, value) ->
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
        val sha1 = validateSha1(
            value.requiredString("sha1"),
            context,
        )
        val size = value.requiredLong("size")
        require(size >= 0) { "$context has a negative size" }
        val url = value.requiredString("url")
        require(url.isNotEmpty()) { "$context has an empty URL" }
        val path = if (requirePath) {
            value.requiredString("path").also {
                require(it.isNotEmpty()) {
                    "$context has an empty path"
                }
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
