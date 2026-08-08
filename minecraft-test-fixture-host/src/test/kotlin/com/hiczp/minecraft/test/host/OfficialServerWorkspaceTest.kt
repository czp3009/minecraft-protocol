package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class OfficialServerWorkspaceTest {
    @Test
    fun immutableServerLibrariesUseOneDirectorySymbolicLink() {
        val root = Path(
            Files.createTempDirectory("official-server-workspace-").toString(),
        )
        try {
            val runtimeDirectory = Path(root, "source", "runtime")
            val serverJar = Path(runtimeDirectory, "server.jar")
            val library = Path(
                runtimeDirectory,
                "libraries",
                "example",
                "library.jar",
            )
            val versionJar = Path(
                runtimeDirectory,
                "versions",
                "test",
                "server-test.jar",
            )
            val templateDirectory = Path(root, "source", "template")
            serverJar.writeText("server")
            library.writeText("library")
            versionJar.writeText("version")
            templateDirectory.ensureDirectory()

            val directoryProbeSource = Path(root, "directory-probe-source")
            val directoryProbeDestination = Path(
                root,
                "directory-probe-destination",
            )
            directoryProbeSource.ensureDirectory()
            val symbolicLinksSupported = directoryProbeSource.linkDirectoryTo(
                directoryProbeDestination,
            )
            val workDirectory = Path(root, "work")

            val privateArtifact = prepareOfficialServerWorkspace(
                preparedArtifact = OfficialServerArtifact(
                    runtimeDirectory = runtimeDirectory,
                    jar = serverJar,
                    templateDirectory = templateDirectory,
                ),
                workDirectory = workDirectory,
                configuration = OfficialMinecraftServerConfiguration(
                    properties = mapOf("level-name" to "fresh"),
                ),
            )

            assertEquals(
                symbolicLinksSupported,
                Files.isSymbolicLink(
                    Path(workDirectory, "libraries").toNioPath(),
                ),
            )
            assertEquals("server", privateArtifact.jar.readText())
            assertEquals(
                "library",
                Path(workDirectory, "libraries", "example", "library.jar")
                    .readText(),
            )
            assertEquals(
                "version",
                Path(
                    workDirectory,
                    "versions",
                    "test",
                    "server-test.jar",
                ).readText(),
            )

            workDirectory.deleteTree()

            assertEquals("library", library.readText())
            assertEquals("version", versionJar.readText())
        } finally {
            root.deleteTree()
        }
    }
}
