package com.hiczp.minecraft.buildlogic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

@CacheableTask
abstract class DownloadOfficialMinecraftServerTask :
    DefaultTask() {
    @get:Internal
    abstract val offline: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionMetadata: RegularFileProperty

    @get:OutputFile
    abstract val serverJar: RegularFileProperty

    init {
        offline.convention(false)
    }

    @TaskAction
    fun download() {
        val metadata = protocolJson.decodeFromString<JsonObject>(
            versionMetadata.asFile.get().toPath().readText(),
        )
        val server = metadata.getValue("downloads").jsonObject.getValue("server").jsonObject
        val serverUrl = server.getValue("url").jsonPrimitive.content

        val destination = serverJar.asFile.get().toPath()
        runBlocking {
            ProtocolHttp.download(
                url = serverUrl,
                destination = destination,
                offline = offline.get(),
            )
            logger.lifecycle(
                "Downloaded Mojang server: $destination",
            )
        }
    }
}

@CacheableTask
abstract class AnalyzeOfficialMinecraftTargetTask :
    DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun analyze() {
        val server = serverJar.asFile.get().toPath()
        val minecraftProtocolTarget = server.readMinecraftProtocolTarget()
        val output = outputFile.asFile.get().toPath()
        output.writeJson(minecraftProtocolTarget.toOfficialMinecraftTargetReportJson(), sortKeys = true)
        logger.lifecycle(
            "Analyzed Minecraft ${minecraftProtocolTarget.minecraftVersion} target: $output",
        )
    }
}

@CacheableTask
abstract class AnalyzeOfficialMinecraftReportsTask :
    DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val serverJar = serverJar.asFile.get().toPath()
        val minecraftProtocolTarget = serverJar.readMinecraftProtocolTarget()
        val outputDirectory = outputDirectory.asFile.get().toPath()
        val workDirectory = createIsolatedTemporaryDirectory("reports")
        val reports = try {
            generateReports(serverJar, minecraftProtocolTarget, workDirectory)
        } finally {
            workDirectory.deleteTree()
        }
        outputDirectory.deleteTree()
        reports.forEach { (name, report) ->
            outputDirectory.resolve("reports/$name")
                .writeJson(report, sortKeys = true)
        }
        logger.lifecycle(
            "Generated official protocol reports: ${outputDirectory.resolve("reports")}",
        )
    }

    private fun generateReports(
        serverJar: Path,
        minecraftProtocolTarget: MinecraftProtocolTarget,
        workDirectory: Path,
    ): Map<String, JsonObject> {
        val generatorOutput = workDirectory.resolve("generated")
        val packetsReport = generatorOutput.resolve("reports/packets.json")

        val command = listOf(
            "java",
            JvmProcessArguments.ENABLE_NATIVE_ACCESS_ALL_UNNAMED,
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
        val processResult = runProcess(command, workDirectory)
        check(processResult.exitCode == 0) {
            """
            Vanilla data generator exited with ${processResult.exitCode}:
            ${processResult.output.lineSequence().toList().takeLast(80).joinToString("\n")}
            """.trimIndent()
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
            "Vanilla data generator did not create reports/packets.json; packet-like outputs: ${
                candidates.ifEmpty {
                    listOf(
                        "none"
                    )
                }.joinToString()
            }"
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
            protocolJson.decodeFromString<JsonObject>(source.readText())
        }
        validateReports(reports)
        return reports
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
                    it.jsonObject.getValue("protocol_id").jsonPrimitive.int
                }
                check(ids.isNotEmpty() && ids.all { it >= 0 }) {
                    "Official packets report has invalid entries for $state/$direction"
                }
                check(ids.distinct().size == ids.size) {
                    "Official packets report has duplicate IDs for $state/$direction"
                }
            }
        }

        val registries = reports.getValue("registries.json")
        check(registries.isNotEmpty()) {
            "Official registries report is empty"
        }
        registries.forEach { (registry, registryElement) ->
            val entries = registryElement.jsonObject.getValue("entries").jsonObject
            check(entries.isNotEmpty()) {
                "Official registry $registry has no entries"
            }
            check(entries.values.all {
                it.jsonObject.getValue("protocol_id").jsonPrimitive.int >= 0
            }) {
                "Official registry $registry has a negative protocol ID"
            }
        }

        val blocks = reports.getValue("blocks.json")
        check(blocks.isNotEmpty()) {
            "Official blocks report is empty"
        }
        blocks.forEach { (block, blockElement) ->
            val states = blockElement.jsonObject.getValue("states").jsonArray
            check(states.isNotEmpty()) {
                "Official block $block has no states"
            }
            check(states.all {
                it.jsonObject.getValue("id").jsonPrimitive.int >= 0
            }) {
                "Official block $block has a negative state ID"
            }
        }
    }

}

// ═══════════════════════════════════════════════════════════════════════════════
// ExtractOfficialServerRuntimeTask
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Extracts the real Minecraft server implementation JAR and its libraries from
 * the official server bundle (which is a fat JAR with a versioned manifest).
 * The extracted runtime is what `OfficialCodecOracle` loads at test time.
 */
@CacheableTask
abstract class ExtractOfficialServerRuntimeTask :
    DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val bundle = serverJar.asFile.get().toPath()
        val output = outputDirectory.asFile.get().toPath()

        output.deleteTree()
        output.createDirectories()

        ZipFile(bundle.toFile()).use { archive ->
            // Read version manifest
            val versionFields = archive.getInputStream(
                archive.getEntry("META-INF/versions.list"),
            ).use { it.readBytes().decodeToString().trim().split('\t') }
            check(versionFields.size == 3)

            // Extract implementation JAR
            val impl = archive.getInputStream(
                archive.getEntry("META-INF/versions/${versionFields[2]}"),
            ).use { it.readBytes() }
            val implJar = output.resolve("server.jar")
            implJar.atomicWrite(impl)

            // Extract libraries
            archive.getInputStream(
                archive.getEntry("META-INF/libraries.list"),
            ).use { inputStream ->
                inputStream.readBytes().decodeToString()
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .forEach { line ->
                        val fields = line.split('\t')
                        check(fields.size == 3)
                        val relative = fields[2]
                        val content = archive.getInputStream(
                            archive.getEntry("META-INF/libraries/$relative"),
                        ).use { it.readBytes() }
                        output.resolve("libraries").resolve(relative)
                            .atomicWrite(content)
                    }
            }
        }
        logger.lifecycle("Extracted official server runtime: $output")
    }
}
