package com.hiczp.minecraft.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Publishes [artifact] from the root project as the `<name>Elements`
 * consumable configuration consumed by [officialMinecraftAnalysisFile] and
 * [officialMinecraftAnalysisDirectory].
 */
fun Project.publishOfficialMinecraftAnalysis(
    name: String,
    artifact: Any,
    builtBy: Any,
    directory: Boolean = false,
) {
    val elements = configurations.consumable("${name}Elements") {
        description = "Official Minecraft analysis: $name"
    }
    artifacts.add(elements.name, artifact) {
        it.builtBy(builtBy)
        if (directory) {
            it.type = "directory"
        }
    }
}

/**
 * Consumes the single file published by the root project's
 * `<name>Elements` official-Minecraft analysis configuration.
 *
 * The returned provider carries the producing task's dependency, so consumers
 * never need manual `builtBy` wiring.
 */
fun Project.officialMinecraftAnalysisFile(
    name: String,
): Provider<RegularFile> =
    layout.file(officialMinecraftAnalysis(name).elements.map {
        it.single().asFile
    })

/**
 * Consumes the directory published by the root project's
 * `<name>Elements` official-Minecraft analysis configuration.
 */
fun Project.officialMinecraftAnalysisDirectory(
    name: String,
): Provider<Directory> =
    layout.dir(officialMinecraftAnalysis(name).elements.map {
        it.single().asFile
    })

private fun Project.officialMinecraftAnalysis(name: String): Configuration {
    val configuration = configurations.create(name) {
        it.isCanBeConsumed = false
        it.isCanBeResolved = true
        it.isTransitive = false
        it.description = "Official Minecraft analysis: $name"
    }
    dependencies.add(
        configuration.name,
        dependencies.project(
            mapOf(
                "path" to ":",
                "configuration" to "${configuration.name}Elements",
            ),
        ),
    )
    return configuration
}

/**
 * Publishes the official codec oracle Java source from the
 * owning fixture-host module so the root project can consume it as
 * an input to [CompileOfficialCodecOracleTask].
 */
fun Project.publishCodecOracleSource() {
    val elements = configurations.consumable("codecOracleSourceElements") {
        description = "Official codec oracle Java source"
    }
    artifacts.add(
        elements.name,
        layout.projectDirectory.file(
            "src/main/resources/com/hiczp/minecraft/test/oracle/OfficialCodecOracle.java",
        ),
    )
}
