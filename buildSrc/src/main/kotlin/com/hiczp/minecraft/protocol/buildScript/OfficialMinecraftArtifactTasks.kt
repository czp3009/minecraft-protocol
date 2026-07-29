package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

abstract class DownloadOfficialMinecraftServerTask :
    MinecraftProtocolToolTask() {
    @TaskAction
    fun download() {
        val target = repository.readMinecraftProtocolTarget()
        val metadata = resolveServerDownload(target.minecraftVersion)
        val versionDirectory = officialServerDirectory(target.minecraftVersion)
        val serverJar = versionDirectory.resolve("server.jar")
        val changed = ProtocolHttp.ensureDownload(
            url = metadata.requiredString("server_url"),
            destination = serverJar,
            expectedSize = metadata.requiredLong("server_size"),
            expectedSha1 = metadata.requiredString("server_sha1"),
        )
        versionDirectory.resolve("download-metadata.json")
            .writeJson(metadata, sortKeys = true)
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

    private fun officialServerDirectory(version: String): Path {
        val root = repository.resolve("build/protocol-reference/mojang")
            .toAbsolutePath()
            .normalize()
        val directory = root.resolve(version).normalize()
        check(directory.parent == root) {
            "Resolved Minecraft cache directory escaped its parent"
        }
        return directory
    }
}

abstract class GenerateOfficialMinecraftReportsTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val javaExecutable: Property<String>

    @TaskAction
    fun generate() {
        val target = repository.readMinecraftProtocolTarget()
        val versionDirectory = officialServerDirectory(target.minecraftVersion)
        val serverJar = versionDirectory.resolve("server.jar")
        val metadata = versionDirectory.resolve("download-metadata.json")
            .readJsonObject()
        check(serverJar.isRegularFile()) {
            "Official server is missing; run downloadOfficialMinecraftServer"
        }
        val outputDirectory = versionDirectory.resolve("generated")
        val packetsReport = outputDirectory.resolve("reports/packets.json")
        val marker = versionDirectory.resolve("reports-metadata.json")
        val markerValue = jsonObjectOf(
            "minecraft_version" to
                    jsonString(metadata.requiredString("minecraft_version")),
            "server_sha1" to
                    jsonString(metadata.requiredString("server_sha1")),
        )
        if (
            packetsReport.isRegularFile() &&
            marker.isRegularFile() &&
            marker.readJsonObject() == markerValue
        ) {
            logger.lifecycle(
                "Official packet report is current: $packetsReport",
            )
            return
        }

        validateAnalysisJava(
            javaExecutable.get(),
            metadata.requiredInt("java_major_version"),
            target.minecraftVersion,
        )
        outputDirectory.deleteTree()
        outputDirectory.createDirectories()
        val command = listOf(
            javaExecutable.get(),
            "-DbundlerMainClass=net.minecraft.data.Main",
            "-jar",
            serverJar.toString(),
            "--reports",
            "--output",
            outputDirectory.toString(),
        )
        logger.lifecycle(
            "Running vanilla data generator for protocol reports...",
        )
        val result = runProcess(command, versionDirectory)
        check(result.exitCode == 0) {
            "Vanilla data generator exited with ${result.exitCode}:\n" +
                    result.output.lineSequence().toList().takeLast(80)
                        .joinToString("\n")
        }
        check(packetsReport.isRegularFile()) {
            val candidates = Files.walk(outputDirectory).use { paths ->
                paths.filter {
                    Files.isRegularFile(it) &&
                            it.fileName.toString().contains(
                                "packet",
                                ignoreCase = true,
                            ) &&
                            it.fileName.toString().endsWith(".json")
                }.map { outputDirectory.relativize(it).toString() }
                    .sorted()
                    .toList()
            }
            "Vanilla data generator did not create reports/packets.json; " +
                    "packet-like outputs: " +
                    candidates.ifEmpty { listOf("none") }.joinToString()
        }
        marker.writeJson(markerValue, sortKeys = true)
        logger.lifecycle(
            "Generated official packet report: $packetsReport",
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

    private fun officialServerDirectory(version: String): Path =
        repository.resolve("build/protocol-reference/mojang")
            .resolve(version)
            .toAbsolutePath()
            .normalize()
}

abstract class UnpackOfficialMinecraftServerTask :
    MinecraftProtocolToolTask() {
    @TaskAction
    fun unpack() {
        val target = repository.readMinecraftProtocolTarget()
        val versionDirectory = repository
            .resolve("build/protocol-reference/mojang")
            .resolve(target.minecraftVersion)
            .toAbsolutePath()
            .normalize()
        val bundle = versionDirectory.resolve("server.jar")
        val output = versionDirectory.resolve("server-inner.jar")
        val nested: ByteArray
        val expectedSha256: String
        ZipFile(bundle.toFile()).use { archive ->
            val versionsEntry = archive.getEntry("META-INF/versions.list")
                ?: error("Server bundle has no META-INF/versions.list")
            val fields = archive.getInputStream(versionsEntry).use {
                it.readBytes().toString(StandardCharsets.UTF_8).trim()
            }.split('\t')
            check(fields.size == 3) {
                "META-INF/versions.list has an unexpected shape"
            }
            expectedSha256 = fields[0].lowercase()
            check(fields[1] == target.minecraftVersion) {
                "Server bundle contains ${fields[1]}, expected " +
                        target.minecraftVersion
            }
            check(expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
                "Nested server SHA-256 is malformed"
            }
            val entryName = "META-INF/versions/${fields[2]}"
            val entry = archive.getEntry(entryName)
                ?: error("Server bundle has no $entryName")
            nested = archive.getInputStream(entry).use { it.readBytes() }
        }
        val actualSha256 = nested.sha256()
        check(actualSha256 == expectedSha256) {
            "Nested server JAR failed its bundle SHA-256 verification: " +
                    "expected $expectedSha256, got $actualSha256"
        }
        if (output.isRegularFile() && output.sha256() == actualSha256) {
            logger.lifecycle(
                "Official implementation JAR is current: $output",
            )
            return
        }
        output.atomicWrite(nested)
        logger.lifecycle(
            "Extracted verified official implementation JAR: $output",
        )
    }
}

abstract class PrepareOfficialMinecraftClientTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val offline: Property<Boolean>

    @get:Input
    abstract val workers: Property<Int>

    init {
        offline.convention(false)
        workers.convention(12)
    }

    @TaskAction
    fun prepare() {
        val target = repository.readMinecraftProtocolTarget()
        val workerCount = workers.get()
        require(workerCount > 0) { "workers must be positive" }
        prepareClient(target.minecraftVersion, offline.get(), workerCount)
    }

    private fun prepareClient(
        version: String,
        offline: Boolean,
        workerCount: Int,
    ) {
        val expectedParent = repository
            .resolve("build/protocol-reference/mojang-client")
            .toAbsolutePath()
            .normalize()
        val clientRoot = expectedParent.resolve(version).normalize()
        check(clientRoot.parent == expectedParent) {
            "Client cache escaped its expected parent"
        }
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
