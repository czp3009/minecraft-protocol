package com.hiczp.minecraft.buildlogic

import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

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
        val officialMinecraftTarget = serverJarPath.readOfficialMinecraftTarget()

        check(packetsReportPath.isRegularFile()) {
            "Official packets report is missing: $packetsReportPath"
        }
        val officialPacketIds = OfficialPacketIds.fromReport(
            buildLogicJson.decodeFromString<JsonObject>(packetsReportPath.readText()),
        )
        val workDirectory = createIsolatedTemporaryDirectory("configuration")
        val vanillaConfigurationCaptureResult = try {
            OfficialVanillaConfigurationCapture.capture(
                serverJar = serverJarPath,
                workDirectory = workDirectory,
                officialMinecraftTarget = officialMinecraftTarget,
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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dataPackManifestFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val vanillaConfigurationCaptureResult = VanillaConfigurationCaptureResult.fromAnalysisJson(
            buildLogicJson.decodeFromString<JsonObject>(configurationFile.asFile.get().toPath().readText()),
        )
        val dataPackManifest = buildLogicJson.decodeFromString<JsonObject>(
            dataPackManifestFile.asFile.get().toPath().readText(),
        )
        check(dataPackManifest.getValue("schema_version").jsonPrimitive.int == 1) {
            "Unsupported official data-pack manifest schema"
        }
        val dataPackFormat = dataPackManifest.getValue("data_pack_format").jsonArray.map { it.jsonPrimitive.int }
        check(dataPackFormat.size == 2 && dataPackFormat.all { it >= 0 }) { "Invalid official data-pack format" }
        val generatedSource = vanillaConfigurationCaptureResult.renderKotlin(dataPackFormat[0], dataPackFormat[1]).toString()
        val outputFilePath = outputFile.asFile.get().toPath()
        outputFilePath.atomicWriteText(generatedSource)
        logger.lifecycle(
            "Generated vanilla Configuration source from analysis data: $outputFilePath",
        )
    }

}
