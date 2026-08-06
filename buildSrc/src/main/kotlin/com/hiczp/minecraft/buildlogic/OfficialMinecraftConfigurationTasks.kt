package com.hiczp.minecraft.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import kotlin.io.path.isRegularFile

/**
 * Captures the complete portable vanilla Configuration snapshot from the
 * matching official server. This is part of the root official-analysis layer;
 * no source generator needs access to the server JAR.
 */
@CacheableTask
abstract class AnalyzeOfficialMinecraftConfigurationTask :
    MinecraftProtocolToolTask() {
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
        val version = minecraftVersion.get()
        val server = serverJar.asFile.get().toPath()
        val reports = packetsReport.asFile.get().toPath()
        val target = server.readMinecraftProtocolTarget(version)

        check(reports.isRegularFile()) {
            "Official packets report is missing: $reports"
        }
        val packetIds = OfficialPacketIds.fromReport(
            reports.readJsonObject(),
        )
        val workDirectory = createIsolatedTemporaryDirectory("configuration")
        val result = try {
            OfficialVanillaConfigurationCapture.capture(
                serverJar = server,
                workDirectory = workDirectory,
                target = target,
                packetIds = packetIds,
            )
        } finally {
            workDirectory.deleteTree()
        }
        val output = outputFile.asFile.get().toPath()
        output.writeJson(
            result.toAnalysisJson(target, server.sha256()),
            sortKeys = true,
        )
        logger.lifecycle(
            "Analyzed official vanilla Configuration data: $output",
        )
    }
}

/** Renders Kotlin solely from official-analysis JSON inputs. */
@CacheableTask
abstract class GenerateVanillaConfigurationSourceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configurationFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val targetReport = targetFile.asFile.get().toPath()
            .readOfficialMinecraftTargetReport()
        val result = VanillaConfigurationCaptureResult.fromAnalysisJson(
            configurationFile.asFile.get().toPath().readJsonObject(),
            expectedTarget = targetReport,
        )
        val source = result.renderKotlin(targetReport.target).toString()
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle(
            "Generated vanilla Configuration source from analysis data: $output",
        )
    }

}
