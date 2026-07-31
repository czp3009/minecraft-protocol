package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

internal const val VERSION_MANIFEST_URL =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

@CacheableTask
abstract class DownloadOfficialMinecraftServerTask :
    MinecraftProtocolToolTask() {
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
        val metadataPath = metadataFile.asFile.get().toPath()
        metadataPath.parent.resolve(".test-support.lock")
            .withExclusiveFileLock {
                downloadLocked(version, metadataPath)
            }
    }

    private fun downloadLocked(
        version: String,
        metadataPath: Path,
    ) {
        val metadata =
            if (offline.get()) {
                check(metadataPath.isRegularFile()) {
                    "Official download metadata is absent in offline mode: " +
                            metadataPath
                }
                metadataPath.readJsonObject()
            } else {
                resolveServerDownload(version)
            }
        validateDownloadMetadata(metadata, version)
        val serverJar = serverJar.asFile.get().toPath()
        val changed = ProtocolHttp.ensureDownload(
            url = metadata.requiredString("server_url"),
            destination = serverJar,
            expectedSize = metadata.requiredLong("server_size"),
            expectedSha1 = metadata.requiredString("server_sha1"),
            offline = offline.get(),
        )
        val target = serverJar.readMinecraftProtocolTarget(version)
        check(
            target.javaMajorVersion ==
                    metadata.requiredInt("java_major_version"),
        ) {
            "Official server version.json and Mojang metadata disagree on " +
                    "the required Java version"
        }
        if (!offline.get()) {
            metadataPath.writeJson(metadata, sortKeys = true)
        }
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
        val versionMetadataBytes = ProtocolHttp.getBytes(metadataUrl)
        check(versionMetadataBytes.sha1() == entry.requiredString("sha1")) {
            "Mojang version metadata failed its manifest SHA-1"
        }
        val versionMetadata = versionMetadataBytes.decodeJsonObject(
            metadataUrl,
        )
        check(versionMetadata.requiredString("id") == version) {
            "Mojang version metadata identifies a different release"
        }
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

    private fun validateDownloadMetadata(
        metadata: JsonObject,
        expectedVersion: String,
    ) {
        check(
            metadata.requiredString("minecraft_version") == expectedVersion,
        ) {
            "Official download metadata targets a different Minecraft release"
        }
        listOf("version_metadata_url", "server_url").forEach { key ->
            val url = metadata.requiredString(key)
            check(url.startsWith("https://")) {
                "Official download metadata has an unsafe $key"
            }
        }
        listOf("version_metadata_sha1", "server_sha1").forEach { key ->
            check(
                metadata.requiredString(key)
                    .matches(Regex("[0-9a-f]{40}")),
            ) {
                "Official download metadata has an invalid $key"
            }
        }
        check(metadata.requiredLong("server_size") > 0) {
            "Official download metadata has an invalid server size"
        }
        check(metadata.requiredInt("java_major_version") > 0) {
            "Official download metadata has no Java requirement"
        }
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
        val serverJar = serverJar.asFile.get().toPath()
        val target = serverJar.readMinecraftProtocolTarget(
            minecraftVersion.get(),
        )
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
        check(entries.isNotEmpty()) {
            "Official server generated no properties"
        }
        val propertyNames = entries.map {
            it.requiredString("name")
        }.toSet()
        check(
            setOf(
                "online-mode",
                "server-port",
                "level-name",
                "network-compression-threshold",
            ).all(propertyNames::contains),
        ) {
            "Official server.properties is missing baseline properties"
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
        val serverJar = serverJar.asFile.get().toPath()
        val target = serverJar.readMinecraftProtocolTarget(
            minecraftVersion.get(),
        )
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
        val reports = listOf(
            "packets.json",
            "registries.json",
            "blocks.json",
        ).associateWith { name ->
            val source = generatorOutput.resolve("reports/$name")
            check(source.isRegularFile()) {
                "Vanilla data generator did not create reports/$name"
            }
            source.readJsonObject()
        }
        validateReports(reports)
        outputDirectory.deleteTree()
        reports.forEach { (name, report) ->
            outputDirectory.resolve("reports/$name")
                .writeJson(report, sortKeys = true)
        }
        logger.lifecycle(
            "Generated official protocol reports: " +
                    outputDirectory.resolve("reports"),
        )
    }

    private fun validateReports(reports: Map<String, JsonObject>) {
        val packets = reports.getValue("packets.json")
        check(
            setOf(
                "handshake",
                "status",
                "login",
                "configuration",
                "play",
            ).all(packets::containsKey),
        ) {
            "Official packets report is missing protocol states"
        }
        packets.forEach { (state, stateElement) ->
            val directions = stateElement.jsonObject
            check(directions.isNotEmpty()) {
                "Official packets report has no directions for $state"
            }
            directions.forEach { (direction, directionElement) ->
                val ids = directionElement.jsonObject.values.map {
                    it.jsonObject.requiredInt("protocol_id")
                }
                check(ids.isNotEmpty() && ids.all { it >= 0 }) {
                    "Official packets report has invalid entries for " +
                            "$state/$direction"
                }
                check(ids.distinct().size == ids.size) {
                    "Official packets report has duplicate IDs for " +
                            "$state/$direction"
                }
            }
        }

        val registries = reports.getValue("registries.json")
        check(registries.isNotEmpty()) {
            "Official registries report is empty"
        }
        registries.forEach { (registry, registryElement) ->
            val entries = registryElement.jsonObject
                .requiredObject("entries")
            check(entries.isNotEmpty()) {
                "Official registry $registry has no entries"
            }
            check(entries.values.all {
                it.jsonObject.requiredInt("protocol_id") >= 0
            }) {
                "Official registry $registry has a negative protocol ID"
            }
        }

        val blocks = reports.getValue("blocks.json")
        check(blocks.isNotEmpty()) {
            "Official blocks report is empty"
        }
        blocks.forEach { (block, blockElement) ->
            val states = blockElement.jsonObject.requiredArray("states")
            check(states.isNotEmpty()) {
                "Official block $block has no states"
            }
            check(states.all {
                it.jsonObject.requiredInt("id") >= 0
            }) {
                "Official block $block has a negative state ID"
            }
        }
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
