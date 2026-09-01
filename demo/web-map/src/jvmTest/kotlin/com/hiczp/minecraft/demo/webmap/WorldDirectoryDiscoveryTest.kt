package com.hiczp.minecraft.demo.webmap

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldDirectoryDiscoveryTest {
    @Test
    fun explicitRelativeDirectoryHasPriority() {
        val fileSystem = FakeFileSystem()
        val workingDirectory = "/workspace/nested".toPath()
        val worldDirectory = workingDirectory / "chosen-world"
        fileSystem.createDirectories(worldDirectory)
        fileSystem.write(worldDirectory / "level.dat") { writeByte(0) }

        val discoveredWorldDirectory = discoverWorldDirectory(
            fileSystem = fileSystem,
            currentWorkingDirectory = workingDirectory,
            explicitWorldDirectory = "chosen-world",
            minecraftVersion = "selected-release",
        )

        assertEquals(fileSystem.canonicalize(worldDirectory), discoveredWorldDirectory.path)
        assertEquals(WorldDirectorySource.ENVIRONMENT, discoveredWorldDirectory.source)
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun projectDiscoveryWalksToMarkerAndChoosesFirstSortedWorld() {
        val fileSystem = FakeFileSystem()
        val projectRoot = "/workspace/project".toPath()
        val workingDirectory = projectRoot / "demo" / "web-map"
        fileSystem.createDirectories(workingDirectory)
        fileSystem.write(projectRoot / ".minecraft-protocol-root") { writeUtf8("marker") }
        val savesDirectory = projectRoot / "demo" / "launcher" / "minecraft" / "selected-release" / "saves"
        listOf("z-world", "a-world").forEach { name ->
            val worldDirectory = savesDirectory / name
            fileSystem.createDirectories(worldDirectory)
            fileSystem.write(worldDirectory / "level.dat") { writeByte(0) }
        }

        val discoveredWorldDirectory = discoverWorldDirectory(
            fileSystem = fileSystem,
            currentWorkingDirectory = workingDirectory,
            explicitWorldDirectory = null,
            minecraftVersion = "selected-release",
        )

        assertEquals(fileSystem.canonicalize(savesDirectory / "a-world"), discoveredWorldDirectory.path)
        assertEquals(WorldDirectorySource.PROJECT_DISCOVERY, discoveredWorldDirectory.source)
        fileSystem.checkNoOpenFiles()
    }
}
