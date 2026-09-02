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
    val launcher: Path,
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
        val launcher = root.safeResolve(
            manifest.getValue("relative_headlessmc_launcher").jsonPrimitive.content,
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
                    launcher.isRegularFile() &&
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
            launcher = launcher,
            modsDirectory = modsDirectory,
            processedModsDirectory = processedModsDirectory,
            templateDirectory = templateDirectory,
        )
    }
}
