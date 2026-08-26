package com.hiczp.minecraft.test.host

import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadlessClientWorkspaceTest {
    @Test
    fun immutableClientInputsAreLinkedAndMutableTemplateStateIsCopied() {
        val root = Files.createTempDirectory("headless-client-workspace-")
        try {
            val sourceDirectory = root.resolve("source")
            val minecraftDirectory = sourceDirectory.resolve("minecraft")
            val launcher = sourceDirectory.resolve("headlessmc-launcher.jar")
            val modsDirectory = sourceDirectory.resolve("mods")
            val templateDirectory = sourceDirectory.resolve("template")
            val processedModsDirectory = templateDirectory.resolve(".fabric").resolve("processedMods")
            minecraftDirectory.resolve("libraries").createDirectories()
            minecraftDirectory.resolve("libraries").resolve("library.jar")
                .writeText("library")
            launcher.writeText("launcher")
            modsDirectory.createDirectories()
            modsDirectory.resolve("hmc-specifics.jar").writeText("mod")
            processedModsDirectory.createDirectories()
            processedModsDirectory.resolve("mixinextras.jar").writeText("cache")
            templateDirectory.resolve("mods").createDirectories()
            templateDirectory.resolve("mods").resolve("hmc-specifics.jar")
                .writeText("template copy must be excluded")
            templateDirectory.resolve("options.txt").writeText("options")
            templateDirectory.resolve("logs").createDirectories()

            val probeSource = root.resolve("probe-source")
            val probeDestination = root.resolve("probe-destination")
            probeSource.writeText("probe")
            val hardLinksSupported = probeSource.linkFileTo(probeDestination)
            val directoryProbeSource = root.resolve("directory-probe-source")
            val directoryProbeDestination = root.resolve("directory-probe-destination")
            directoryProbeSource.createDirectories()
            val symbolicLinksSupported = directoryProbeSource.linkDirectoryTo(
                directoryProbeDestination,
            )

            val headlessClientInstallation = HeadlessClientInstallation(
                minecraftVersion = "test",
                fabricProfileId = "fabric-loader-test",
                minecraftDirectory = minecraftDirectory,
                launcher = launcher,
                modsDirectory = modsDirectory,
                processedModsDirectory = processedModsDirectory,
                templateDirectory = templateDirectory,
            )
            val workDirectory = root.resolve("default-work")
            val privateInstallation = prepareHeadlessClientRuntime(
                headlessClientInstallation = headlessClientInstallation,
                workDirectory = workDirectory,
            )
            val gameDirectory = workDirectory.resolve("game")
            prepareHeadlessClientWorkspace(
                headlessClientInstallation = privateInstallation,
                gameDirectory = gameDirectory,
                useTemplate = true,
            )

            val privateLibrary = privateInstallation.minecraftDirectory.resolve("libraries").resolve("library.jar")
            val privateMod = gameDirectory.resolve("mods").resolve("hmc-specifics.jar")
            val privateCache = gameDirectory.resolve(".fabric").resolve("processedMods").resolve("mixinextras.jar")
            val privateOptions = gameDirectory.resolve("options.txt")
            assertEquals(
                symbolicLinksSupported,
                Files.isSymbolicLink(
                    privateInstallation.minecraftDirectory,
                ),
            )
            assertEquals("library", privateLibrary.readText())
            assertEquals("launcher", privateInstallation.launcher.readText())
            assertEquals("mod", privateMod.readText())
            assertEquals("cache", privateCache.readText())
            assertEquals("options", privateOptions.readText())
            assertTrue(gameDirectory.resolve("logs").isDirectory())
            assertFalse(
                Files.isSameFile(
                    templateDirectory.resolve("options.txt"),
                    privateOptions,
                ),
            )
            privateOptions.writeText("private options")
            assertEquals(
                "options",
                templateDirectory.resolve("options.txt").readText(),
            )

            if (hardLinksSupported) {
                assertTrue(
                    Files.isSameFile(
                        minecraftDirectory.resolve("libraries").resolve("library.jar"),
                        privateLibrary,
                    ),
                )
                assertTrue(
                    Files.isSameFile(
                        launcher,
                        privateInstallation.launcher,
                    ),
                )
                assertTrue(
                    Files.isSameFile(
                        modsDirectory.resolve("hmc-specifics.jar"),
                        privateMod,
                    ),
                )
                assertTrue(
                    Files.isSameFile(
                        processedModsDirectory.resolve("mixinextras.jar"),
                        privateCache,
                    ),
                )
            }

            val freshGameDirectory = root.resolve("fresh-game")
            prepareHeadlessClientWorkspace(
                headlessClientInstallation = privateInstallation,
                gameDirectory = freshGameDirectory,
                useTemplate = false,
            )
            assertFalse(freshGameDirectory.resolve("options.txt").exists())
            assertEquals(
                "mod",
                freshGameDirectory.resolve("mods").resolve("hmc-specifics.jar")
                    .readText(),
            )
            assertEquals(
                "cache",
                freshGameDirectory.resolve(".fabric").resolve("processedMods").resolve("mixinextras.jar").readText(),
            )

            workDirectory.deleteTree()
            assertEquals(
                "library",
                minecraftDirectory.resolve("libraries").resolve("library.jar")
                    .readText(),
            )
        } finally {
            root.deleteTree()
        }
    }
}
