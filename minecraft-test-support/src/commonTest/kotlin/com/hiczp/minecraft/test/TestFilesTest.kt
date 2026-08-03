package com.hiczp.minecraft.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.uuid.Uuid

class TestFilesTest {
    @Test
    fun runtimeDirectoriesUseVersionAndUuidLayers() {
        val layout = MinecraftTestSupport.layout
        val first = layout.newRuntimeDirectory(
            MinecraftRuntimeKind.OFFICIAL_SERVER,
        )
        val second = layout.newRuntimeDirectory(
            MinecraftRuntimeKind.OFFICIAL_SERVER,
        )
        try {
            assertNotEquals(first, second)
            assertEquals(layout.minecraftVersion, first.parent?.name)
            assertEquals("official-server", first.parent?.parent?.name)
            assertEquals("runtimes", first.parent?.parent?.parent?.name)
            Uuid.parse(first.name)
            Uuid.parse(second.name)
            assertTrue(first.isBelow(layout.repositoryBuildDirectory))
            assertTrue(second.isBelow(layout.repositoryBuildDirectory))
        } finally {
            first.deleteTree()
            second.deleteTree()
        }
    }

    @Test
    fun publicFilePathsStayInsideTheRepositoryBuildDirectory() {
        val layout = MinecraftTestSupport.layout
        val firstReport = MinecraftTestSupport.reportFile(
            "fixtures/report.json",
        )
        val secondReport = MinecraftTestSupport.reportFile(
            "fixtures/report.json",
        )

        assertNotEquals(firstReport, secondReport)
        assertTrue(firstReport.isBelow(layout.repositoryBuildDirectory))
        assertTrue(secondReport.isBelow(layout.repositoryBuildDirectory))
        assertFailsWith<IllegalArgumentException> {
            MinecraftTestSupport.reportFile("../../outside.json")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftTestSupport.temporaryFile("../../outside.bin")
        }
    }

    @Test
    fun repositoryRootIsFoundFromCurrentDirectory() {
        val currentDirectory = SystemFileSystem.resolve(Path("."))
        val root = discoverRepositoryRoot(currentDirectory)
        assertNotNull(root)
        val markerFile = Path(root, ".minecraft-protocol-root")
        assertTrue(markerFile.isRegularFile())
    }

    @Test
    fun repositoryRootRejectsWrongMagic() {
        val parentDir = MinecraftTestSupport.newScratchDirectory()
        val subDir = Path(parentDir, "sub")
        subDir.ensureDirectory()
        val badMarker = Path(parentDir, ".minecraft-protocol-root")
        try {
            badMarker.atomicWriteText("wrong-magic\n")
            assertFailsWith<IllegalStateException> {
                discoverRepositoryRoot(subDir)
            }
        } finally {
            parentDir.deleteTree()
        }
    }

    @Test
    fun resourceCloseIsIdempotentAndCleanupIsAsynchronous() = runTest {
        val directory = MinecraftTestSupport.newScratchDirectory()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val resource = MinecraftTestSupport.manageTestResource(directory) {
            cleanupStarted.complete(Unit)
            allowCleanup.await()
        }

        resource.close()
        resource.close()
        cleanupStarted.await()
        assertTrue(directory.isDirectory())

        allowCleanup.complete(Unit)
        MinecraftTestSupport.awaitCleanup()
        assertFalse(directory.exists())
    }

    @Test
    fun reportWriterProducesParseableStructuredJson() {
        val report = MinecraftTestSupport.temporaryFile(
            "unit/structured.json",
        )
        report.writeJsonReport(
            buildJsonObject {
                put("text", "quote=\" newline=\n")
                put("count", 2)
            },
        )

        assertTrue(report.isRegularFile())
        val document = report.readJsonObject()
        assertEquals(
            "quote=\" newline=\n",
            document.requiredString("text"),
        )
        assertEquals(2, document.requiredInt("count"))
    }

    private fun kotlinx.io.files.Path.isBelow(
        ancestor: kotlinx.io.files.Path,
    ): Boolean = generateSequence(parent) { it.parent }.any { it == ancestor }
}
