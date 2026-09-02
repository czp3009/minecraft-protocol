package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Captures the complete portable vanilla Configuration snapshot from the
 * matching official server. This is part of the root official-analysis layer;
 * no source generator needs access to the server JAR.
 */
@CacheableTask
abstract class AnalyzeOfficialMinecraftConfigurationTask :
    DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packetsReport: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun analyze() {
        val serverJarPath = serverJar.asFile.get().toPath()
        val packetsReportPath = packetsReport.asFile.get().toPath()
        val minecraftProtocolTarget = serverJarPath.readMinecraftProtocolTarget()

        check(packetsReportPath.isRegularFile()) {
            "Official packets report is missing: $packetsReportPath"
        }
        val officialPacketIds = OfficialPacketIds.fromReport(
            protocolJson.decodeFromString<JsonObject>(packetsReportPath.readText()),
        )
        val workDirectory = createIsolatedTemporaryDirectory("configuration")
        val vanillaConfigurationCaptureResult = try {
            OfficialVanillaConfigurationCapture.capture(
                serverJar = serverJarPath,
                workDirectory = workDirectory,
                minecraftProtocolTarget = minecraftProtocolTarget,
                officialPacketIds = officialPacketIds,
            )
        } finally {
            workDirectory.deleteTree()
        }
        val outputFilePath = outputFile.asFile.get().toPath()
        outputFilePath.writeJson(
            vanillaConfigurationCaptureResult.toAnalysisJson(),
            sortKeys = true,
        )
        logger.lifecycle(
            "Analyzed official vanilla Configuration data: $outputFilePath",
        )
    }
}

/** Renders Kotlin solely from official-analysis JSON inputs. */
@CacheableTask
abstract class GenerateVanillaConfigurationPacketPayloadSourceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configurationFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val vanillaConfigurationCaptureResult = VanillaConfigurationCaptureResult.fromAnalysisJson(
            protocolJson.decodeFromString<JsonObject>(configurationFile.asFile.get().toPath().readText()),
        )
        val generatedSource = vanillaConfigurationCaptureResult.renderKotlin().toString()
        val outputFilePath = outputFile.asFile.get().toPath()
        outputFilePath.atomicWriteText(generatedSource)
        logger.lifecycle(
            "Generated vanilla Configuration source from analysis data: $outputFilePath",
        )
    }

}
