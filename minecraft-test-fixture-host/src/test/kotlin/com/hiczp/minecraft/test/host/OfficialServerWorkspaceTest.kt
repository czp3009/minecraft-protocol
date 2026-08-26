package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class OfficialServerWorkspaceTest {
    @Test
    fun immutableServerLibrariesUseOneDirectorySymbolicLink() {
        val root = Files.createTempDirectory("official-server-workspace-")
        try {
            val runtimeDirectory = root.resolve("source").resolve("runtime")
            val serverJar = runtimeDirectory.resolve("server.jar")
            val library = runtimeDirectory.resolve("libraries").resolve("example").resolve("library.jar")
            val versionJar = runtimeDirectory.resolve("versions").resolve("test").resolve("server-test.jar")
            val templateDirectory = root.resolve("source").resolve("template")
            runtimeDirectory.createDirectories()
            serverJar.writeText("server")
            library.parent.createDirectories()
            library.writeText("library")
            versionJar.parent.createDirectories()
            versionJar.writeText("version")
            templateDirectory.createDirectories()

            val directoryProbeSource = root.resolve("directory-probe-source")
            val directoryProbeDestination = root.resolve("directory-probe-destination")
            directoryProbeSource.createDirectories()
            val symbolicLinksSupported = directoryProbeSource.linkDirectoryTo(
                directoryProbeDestination,
            )
            val workDirectory = root.resolve("work")

            val privateArtifact = prepareOfficialServerWorkspace(
                preparedArtifact = OfficialServerArtifact(
                    runtimeDirectory = runtimeDirectory,
                    jar = serverJar,
                    templateDirectory = templateDirectory,
                ),
                workDirectory = workDirectory,
                officialMinecraftServerConfiguration = OfficialMinecraftServerConfiguration(
                    properties = mapOf("level-name" to "fresh"),
                ),
            )

            assertEquals(
                symbolicLinksSupported,
                Files.isSymbolicLink(
                    workDirectory.resolve("libraries"),
                ),
            )
            assertEquals("server", privateArtifact.jar.readText())
            assertEquals(
                "library",
                workDirectory.resolve("libraries").resolve("example").resolve("library.jar")
                    .readText(),
            )
            assertEquals(
                "version",
                workDirectory.resolve("versions").resolve("test").resolve("server-test.jar").readText(),
            )

            workDirectory.deleteTree()

            assertEquals("library", library.readText())
            assertEquals("version", versionJar.readText())
        } finally {
            root.deleteTree()
        }
    }
}
