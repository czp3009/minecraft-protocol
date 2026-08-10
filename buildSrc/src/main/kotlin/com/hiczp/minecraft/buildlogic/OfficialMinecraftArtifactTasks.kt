package com.hiczp.minecraft.buildlogic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipFile
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

@CacheableTask
abstract class DownloadOfficialMinecraftServerTask :
    MinecraftProtocolToolTask() {
    @get:Internal
    abstract val offline: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionMetadata: RegularFileProperty

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
        val metadata = versionMetadata.asFile.get().toPath()
            .readJsonObject()
        check(metadata.requiredString("id") == version) {
            "Version metadata identifies a different release"
        }
        val server = metadata.requiredObject("downloads")
            .requiredObject("server")
        val serverUrl = server.requiredString("url")
        val javaMajor = metadata["javaVersion"]
            ?.jsonObject
            ?.requiredInt("majorVersion")
            ?: 0

        val destination = serverJar.asFile.get().toPath()
        val metadataPath = metadataFile.asFile.get().toPath()
        runBlocking {
            ProtocolHttp.download(
                url = serverUrl,
                destination = destination,
                offline = offline.get(),
            )

            val target = destination.readMinecraftProtocolTarget(version)
            check(target.javaMajorVersion == javaMajor) {
                "Official server version.json and Mojang metadata disagree on the required Java version"
            }
            metadataPath.writeJson(
                jsonObjectOf(
                    "schema_version" to jsonNumber(1),
                    "minecraft_version" to jsonString(version),
                    "server_url" to jsonString(serverUrl),
                    "java_major_version" to jsonNumber(javaMajor),
                ),
                sortKeys = true,
            )
            logger.lifecycle(
                "Downloaded Mojang server: $destination",
            )
        }
    }
}

@CacheableTask
abstract class AnalyzeOfficialMinecraftTargetTask :
    MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val downloadMetadata: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun analyze() {
        val server = serverJar.asFile.get().toPath()
        val target = server.readMinecraftProtocolTarget(
            minecraftVersion.get(),
        )
        val metadata = downloadMetadata.asFile.get().toPath()
            .readJsonObject()
        check(
            metadata.requiredString("minecraft_version") ==
                    target.minecraftVersion,
        ) {
            "Official server metadata targets a different Minecraft release"
        }
        val report = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString(target.minecraftVersion),
            "protocol_version" to jsonNumber(target.protocolVersion),
            "java_major_version" to jsonNumber(target.javaMajorVersion),
        )
        val output = outputFile.asFile.get().toPath()
        output.writeJson(report, sortKeys = true)
        logger.lifecycle(
            "Analyzed Minecraft ${target.minecraftVersion} target: $output",
        )
    }
}

@CacheableTask
abstract class AnalyzeOfficialMinecraftReportsTask :
    MinecraftProtocolToolTask() {
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
                    target.minecraftVersion,
        ) {
            "Official server metadata targets a different Minecraft release"
        }
        val outputDirectory = outputDirectory.asFile.get().toPath()
        val workDirectory = createIsolatedTemporaryDirectory("reports")
        val reports = try {
            generateReports(serverJar, target, workDirectory)
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
        target: MinecraftProtocolTarget,
        workDirectory: Path,
    ): Map<String, JsonObject> {
        val generatorOutput = workDirectory.resolve("generated")
        val packetsReport =
            generatorOutput.resolve("reports/packets.json")

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
        val result = runProcess(command, workDirectory)
        check(result.exitCode == 0) {
            """
            Vanilla data generator exited with ${result.exitCode}:
            ${result.output.lineSequence().toList().takeLast(80).joinToString("\n")}
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
            source.readJsonObject()
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
                    it.jsonObject.requiredInt("protocol_id")
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
    MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val bundle = serverJar.asFile.get().toPath()
        val version = minecraftVersion.get()
        val output = outputDirectory.asFile.get().toPath()

        output.deleteTree()
        output.createDirectories()

        ZipFile(bundle.toFile()).use { archive ->
            // Read version manifest
            val versionFields = archive.getInputStream(
                archive.getEntry("META-INF/versions.list"),
            ).use { it.readBytes().decodeToString().trim().split('\t') }
            check(versionFields.size == 3)
            check(versionFields[1] == version)

            // Extract implementation JAR
            val impl = archive.getInputStream(
                archive.getEntry("META-INF/versions/${versionFields[2]}"),
            ).use { it.readBytes() }
            val implJar = output.resolve("server.jar")
            implJar.atomicWrite(impl)

            // Extract libraries
            archive.getInputStream(
                archive.getEntry("META-INF/libraries.list"),
            ).use { input ->
                input.readBytes().decodeToString()
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
        logger.lifecycle(
            "Extracted official server runtime for $version: $output",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CompileOfficialCodecOracleTask
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Compiles the `OfficialCodecOracle.java` bridge against the extracted server
 * runtime.  Tests only load the pre-compiled class; no compilation happens at
 * test time.
 */
@CacheableTask
abstract class CompileOfficialCodecOracleTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun compile() {
        val source = sourceFile.asFile.get().toPath()
        check(source.isRegularFile()) {
            "Official codec bridge source is missing: $source"
        }
        val runtime = runtimeDirectory.asFile.get().toPath()
        val implJar = runtime.resolve("server.jar")
        check(implJar.isRegularFile()) {
            "Server runtime implementation JAR is missing: $implJar"
        }
        val libsDir = runtime.resolve("libraries")
        val libraries = if (libsDir.isDirectory()) {
            Files.walk(libsDir).use { walk ->
                walk.filter { Files.isRegularFile(it) }
                    .map { it.toFile() }
                    .toList()
            }
        } else {
            emptyList()
        }

        val classes = outputDirectory.asFile.get().toPath()
        classes.deleteTree()
        classes.createDirectories()

        val compiler = checkNotNull(
            ToolProvider.getSystemJavaCompiler(),
        ) { "Codec oracle compilation requires a full JDK" }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(
            diagnostics,
            Locale.ROOT,
            StandardCharsets.UTF_8,
        ).use { fileManager ->
            fileManager.setLocationFromPaths(
                StandardLocation.CLASS_OUTPUT,
                listOf(classes),
            )
            val classpath = buildList {
                add(implJar.toFile())
                addAll(libraries)
            }.joinToString(File.pathSeparator)
            val units = fileManager.getJavaFileObjectsFromPaths(
                listOf(source),
            )
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf(
                    "--release",
                    BuildVersions.JAVA_VERSION.toString(),
                    "-classpath",
                    classpath,
                ),
                null,
                units,
            ).call()
            check(success) {
                diagnostics.diagnostics.joinToString(
                    prefix = "Codec oracle bridge compilation failed:\n",
                    separator = "\n",
                )
            }
        }
        logger.lifecycle("Compiled official codec oracle bridge: $classes")
    }
}
