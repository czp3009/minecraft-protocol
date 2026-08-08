package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path

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
    fun prepare(layout: MinecraftTestLayout): HeadlessClientInstallation {
        val root = layout.headlessClientRootDirectory
        val manifestPath = Path(root, "manifest.json")
        check(root.isDirectory() && manifestPath.isRegularFile()) {
            "The HeadlessMC client fixture is missing; run this test through its standard Gradle test task"
        }
        val manifest = manifestPath.readJsonObject()
        val minecraftVersion = manifest.requiredString("minecraft_version")
        check(minecraftVersion == layout.minecraftVersion) {
            "The HeadlessMC client fixture belongs to a different Minecraft release"
        }
        val minecraftDirectory = root.safeResolve(
            manifest.requiredString("relative_minecraft_directory"),
        )
        val launcher = root.safeResolve(
            manifest.requiredString("relative_headlessmc_launcher"),
        )
        val modsDirectory = root.safeResolve(
            manifest.requiredString("relative_mods_directory"),
        )
        val processedModsDirectory = root.safeResolve(
            manifest.requiredString("relative_processed_mods_directory"),
        )
        val templateDirectory = root.safeResolve(
            manifest.requiredString("relative_template_directory"),
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
            fabricProfileId = manifest.requiredString("fabric_profile_id"),
            minecraftDirectory = minecraftDirectory,
            launcher = launcher,
            modsDirectory = modsDirectory,
            processedModsDirectory = processedModsDirectory,
            templateDirectory = templateDirectory,
        )
    }
}
