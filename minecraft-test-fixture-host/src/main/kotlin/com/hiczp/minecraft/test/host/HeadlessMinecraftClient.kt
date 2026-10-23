package com.hiczp.minecraft.test.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal data class HeadlessClientInstallation(
    val minecraftVersion: String,
    val fabricProfileId: String,
    val minecraftDirectory: Path,
    val agent: Path,
    val launchManifest: JsonObject,
    val modsDirectory: Path,
    val processedModsDirectory: Path?,
    val templateDirectory: Path,
)

internal object HeadlessClientPreparation {
    fun prepare(minecraftTestLayout: MinecraftTestLayout): HeadlessClientInstallation {
        val root = minecraftTestLayout.headlessClientRootDirectory
        val manifestPath = root.resolve("manifest.json")
        check(root.isDirectory() && manifestPath.isRegularFile()) {
            "The HeadlessMC client fixture is missing; run this test through its standard Gradle test task"
        }
        val manifest = testJson.decodeFromString<JsonObject>(manifestPath.readText())
        val minecraftVersion = manifest.getValue("minecraft_version").jsonPrimitive.content
        val minecraftDirectory = root.safeResolve(
            manifest.getValue("relative_minecraft_directory").jsonPrimitive.content,
        )
        val agent = root.safeResolve(
            manifest.getValue("relative_headlessmc_agent").jsonPrimitive.content,
        )
        val modsDirectory = root.safeResolve(
            manifest.getValue("relative_mods_directory").jsonPrimitive.content,
        )
        val processedModsDirectory = root.safeResolve(
            manifest.getValue("relative_processed_mods_directory").jsonPrimitive.content,
        )
        val templateDirectory = root.safeResolve(
            manifest.getValue("relative_template_directory").jsonPrimitive.content,
        )
        check(
            minecraftDirectory.isDirectory() &&
                    agent.isRegularFile() &&
                    modsDirectory.isDirectory() &&
                    processedModsDirectory.isDirectory() &&
                    templateDirectory.isDirectory(),
        ) {
            "The HeadlessMC client fixture manifest describes missing prepared inputs"
        }
        return HeadlessClientInstallation(
            minecraftVersion = minecraftVersion,
            fabricProfileId = manifest.getValue("fabric_profile_id").jsonPrimitive.content,
            minecraftDirectory = minecraftDirectory,
            agent = agent,
            launchManifest = testJson.decodeFromString<JsonObject>(
                root.safeResolve(manifest.getValue("relative_launch_manifest").jsonPrimitive.content).readText(),
            ),
            modsDirectory = modsDirectory,
            processedModsDirectory = processedModsDirectory,
            templateDirectory = templateDirectory,
        )
    }
}
