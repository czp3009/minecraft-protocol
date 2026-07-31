package com.hiczp.minecraft.protocol.buildScript

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/**
 * Generates the portable vanilla Configuration snapshot directly from the
 * matching official server.
 *
 * This is deliberately a buildSrc task rather than runtime code: its inputs
 * are non-code artifacts, and none of its capture or rendering machinery is
 * published with protocol-serialization or protocol-vanilla-data.
 */
@CacheableTask
abstract class GenerateVanillaConfigurationDataTask :
    MinecraftProtocolToolTask() {
    @get:Internal
    abstract val javaExecutable: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packetsReport: RegularFileProperty

    @get:OutputFile
    abstract val generatedKotlin: RegularFileProperty

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val version = minecraftVersion.get()
        val server = serverJar.asFile.get().toPath()
        val reports = packetsReport.asFile.get().toPath()
        val source = generatedKotlin.asFile.get().toPath()
        val report = manifest.asFile.get().toPath()
        val target = server.readMinecraftProtocolTarget(version)

        check(reports.isRegularFile()) {
            "Official packets report is missing: $reports"
        }
        val packetIds = OfficialPacketIds.fromReport(
            reports.readJsonObject(),
        )
        val result = OfficialVanillaConfigurationCapture.capture(
            javaExecutable = javaExecutable.get(),
            serverJar = server,
            workDirectory = temporaryDir.toPath().resolve("capture"),
            target = target,
            packetIds = packetIds,
        )

        val renderedSource = result.renderKotlin(target)
        val renderedManifest = result.renderManifest(target, server)
        validateGeneratedSource(renderedSource, version)
        validateManifest(renderedManifest, version, server.sha256())

        source.parent.createDirectories()
        report.parent.createDirectories()
        source.atomicWriteText(renderedSource)
        report.writeJson(renderedManifest, sortKeys = true)
        logger.lifecycle(
            "Generated verified vanilla Configuration data: $source",
        )
    }

    private fun validateGeneratedSource(
        source: String,
        expectedVersion: String,
    ) {
        check(
            source.contains("package com.hiczp.minecraft.protocol.data") &&
                    source.contains(
                        "internal object VanillaConfigurationPayloads",
                    ) &&
                    source.contains(
                        "const val minecraftVersion: String = " +
                                quoteKotlin(expectedVersion),
                    ),
        ) {
            "Vanilla Configuration capture generated an unexpected Kotlin source"
        }
    }

    private fun validateManifest(
        report: kotlinx.serialization.json.JsonObject,
        expectedVersion: String,
        expectedServerSha256: String,
    ) {
        check(report.requiredInt("schema_version") == 1) {
            "Vanilla Configuration manifest has an unsupported schema"
        }
        check(
            report.requiredString("minecraft_version") == expectedVersion,
        ) {
            "Vanilla Configuration manifest targets a different Minecraft release"
        }
        check(
            report.requiredString("official_server_sha256") ==
                    expectedServerSha256,
        ) {
            "Vanilla Configuration manifest describes a different server JAR"
        }
        check(report.requiredArray("known_packs").isNotEmpty()) {
            "Official server offered no Known Packs"
        }
        check(report.requiredArray("registries").isNotEmpty()) {
            "Official server produced no Configuration registries"
        }
        check(report.requiredArray("tags").isNotEmpty()) {
            "Official server produced no Configuration tags"
        }
    }
}

internal fun quoteKotlin(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
        }
    }
    append('"')
}
