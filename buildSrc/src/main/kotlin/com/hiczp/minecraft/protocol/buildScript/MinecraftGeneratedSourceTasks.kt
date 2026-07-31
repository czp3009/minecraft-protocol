package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonObject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.nio.file.Path

@CacheableTask
abstract class GenerateMinecraftProtocolSourceTask :
    MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val target = serverJar.asFile.get().toPath()
            .readMinecraftProtocolTarget(minecraftVersion.get())
        val source = """
            |package com.hiczp.minecraft.protocol.model
            |
            |/**
            | * The single protocol revision implemented by this build.
            | *
            | * Generated from version.json in the matching official server JAR.
            | */
            |object MinecraftProtocol {
            |    const val MINECRAFT_VERSION: String = "${escapeKotlin(target.minecraftVersion)}"
            |    const val PROTOCOL_VERSION: Int = ${target.protocolVersion}
            |}
            |
        """.trimMargin()
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle(
            "Generated Minecraft ${target.minecraftVersion} protocol " +
                    "${target.protocolVersion}: $output",
        )
    }

    private fun escapeKotlin(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Builds the complete, reviewable protocol evidence tree under build/.
 *
 * The public refresh task is a Sync from this directory into
 * protocol-specification/generated, so ordinary compilation never mutates the
 * source tree.
 */
@CacheableTask
abstract class GenerateProtocolSpecificationTask :
    MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val downloadMetadata: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packetsReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val registriesReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val blocksReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverPropertiesReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configurationReport: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val serverJarPath = serverJar.asFile.get().toPath()
        val target = serverJarPath.readMinecraftProtocolTarget(
            minecraftVersion.get(),
        )
        val output = outputDirectory.asFile.get().toPath()
        output.deleteTree()
        output.toFile().mkdirs()

        val download = downloadMetadata.asFile.get().toPath()
            .readJsonObject()
        check(download.requiredString("minecraft_version") == target.minecraftVersion)
        check(download.requiredString("server_sha1") == serverJarPath.sha1()) {
            "Official server JAR does not match its Mojang metadata"
        }
        val targetReport = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString(target.minecraftVersion),
            "protocol_version" to jsonNumber(target.protocolVersion),
            "official_java_version" to jsonNumber(target.javaMajorVersion),
            "official_server_sha1" to jsonString(serverJarPath.sha1()),
            "official_server_sha256" to jsonString(serverJarPath.sha256()),
            "version_metadata_sha1" to jsonString(
                download.requiredString("version_metadata_sha1"),
            ),
        )
        output.resolve("target.json").writeJson(
            targetReport,
            sortKeys = true,
        )
        copyCanonicalJson(
            packetsReport.asFile.get().toPath(),
            output.resolve("packets.json"),
        )
        copyCanonicalJson(
            registriesReport.asFile.get().toPath(),
            output.resolve("registries.json"),
        )
        copyCanonicalJson(
            blocksReport.asFile.get().toPath(),
            output.resolve("blocks.json"),
        )
        copyCanonicalJson(
            configurationReport.asFile.get().toPath(),
            output.resolve("configuration.json"),
        )
        copyCanonicalJson(
            serverPropertiesReport.asFile.get().toPath(),
            output.resolve("server-properties.json"),
        )
        logger.lifecycle(
            "Generated protocol specification for Minecraft " +
                    "${target.minecraftVersion}: $output",
        )
    }

    private fun copyCanonicalJson(source: Path, destination: Path) {
        val document: JsonObject = source.readJsonObject()
        destination.writeJson(document, sortKeys = true)
    }
}
