package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadlessClientWorkspaceTest {
    @Test
    fun immutableClientInputsAreLinkedAndMutableTemplateStateIsCopied() {
        val root = Path(
            Files.createTempDirectory("headless-client-workspace-").toString(),
        )
        try {
            val minecraftDirectory = Path(root, "source", "minecraft")
            val launcher = Path(root, "source", "headlessmc-launcher.jar")
            val modsDirectory = Path(root, "source", "mods")
            val templateDirectory = Path(root, "source", "template")
            val processedModsDirectory = Path(
                templateDirectory,
                ".fabric",
                "processedMods",
            )
            Path(minecraftDirectory, "libraries", "library.jar")
                .writeText("library")
            launcher.writeText("launcher")
            Path(modsDirectory, "hmc-specifics.jar").writeText("mod")
            Path(processedModsDirectory, "mixinextras.jar").writeText("cache")
            Path(templateDirectory, "mods", "hmc-specifics.jar")
                .writeText("template copy must be excluded")
            Path(templateDirectory, "options.txt").writeText("options")
            Path(templateDirectory, "logs").ensureDirectory()

            val probeSource = Path(root, "probe-source")
            val probeDestination = Path(root, "probe-destination")
            probeSource.writeText("probe")
            val hardLinksSupported = probeSource.linkFileTo(probeDestination)

            val installation = HeadlessClientInstallation(
                minecraftVersion = "test",
                fabricProfileId = "fabric-loader-test",
                minecraftDirectory = minecraftDirectory,
                launcher = launcher,
                modsDirectory = modsDirectory,
                processedModsDirectory = processedModsDirectory,
                templateDirectory = templateDirectory,
            )
            val workDirectory = Path(root, "default-work")
            val privateInstallation = prepareHeadlessClientRuntime(
                installation = installation,
                workDirectory = workDirectory,
            )
            val gameDirectory = Path(workDirectory, "game")
            prepareHeadlessClientWorkspace(
                installation = privateInstallation,
                gameDirectory = gameDirectory,
                useTemplate = true,
            )

            val privateLibrary = Path(
                privateInstallation.minecraftDirectory,
                "libraries",
                "library.jar",
            )
            val privateMod = Path(gameDirectory, "mods", "hmc-specifics.jar")
            val privateCache = Path(
                gameDirectory,
                ".fabric",
                "processedMods",
                "mixinextras.jar",
            )
            val privateOptions = Path(gameDirectory, "options.txt")
            assertEquals("library", privateLibrary.readText())
            assertEquals("launcher", privateInstallation.launcher.readText())
            assertEquals("mod", privateMod.readText())
            assertEquals("cache", privateCache.readText())
            assertEquals("options", privateOptions.readText())
            assertTrue(Path(gameDirectory, "logs").isDirectory())
            assertFalse(
                Files.isSameFile(
                    Path(templateDirectory, "options.txt").toNioPath(),
                    privateOptions.toNioPath(),
                ),
            )
            privateOptions.writeText("private options")
            assertEquals(
                "options",
                Path(templateDirectory, "options.txt").readText(),
            )

            if (hardLinksSupported) {
                assertTrue(
                    Files.isSameFile(
                        Path(minecraftDirectory, "libraries", "library.jar")
                            .toNioPath(),
                        privateLibrary.toNioPath(),
                    ),
                )
                assertTrue(
                    Files.isSameFile(
                        launcher.toNioPath(),
                        privateInstallation.launcher.toNioPath(),
                    ),
                )
                assertTrue(
                    Files.isSameFile(
                        Path(modsDirectory, "hmc-specifics.jar").toNioPath(),
                        privateMod.toNioPath(),
                    ),
                )
                assertTrue(
                    Files.isSameFile(
                        Path(processedModsDirectory, "mixinextras.jar")
                            .toNioPath(),
                        privateCache.toNioPath(),
                    ),
                )
            }

            val freshGameDirectory = Path(root, "fresh-game")
            prepareHeadlessClientWorkspace(
                installation = privateInstallation,
                gameDirectory = freshGameDirectory,
                useTemplate = false,
            )
            assertFalse(Path(freshGameDirectory, "options.txt").exists())
            assertEquals(
                "mod",
                Path(freshGameDirectory, "mods", "hmc-specifics.jar")
                    .readText(),
            )
            assertEquals(
                "cache",
                Path(
                    freshGameDirectory,
                    ".fabric",
                    "processedMods",
                    "mixinextras.jar",
                ).readText(),
            )
        } finally {
            root.deleteTree()
        }
    }
}
