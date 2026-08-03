package com.hiczp.minecraft.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
            Uuid.parse(first.name)
            Uuid.parse(second.name)
            assertTrue(first.isBelow(layout.moduleBuildDirectory))
            assertTrue(second.isBelow(layout.moduleBuildDirectory))
        } finally {
            first.deleteTree()
            second.deleteTree()
        }
    }

    @Test
    fun publicFilePathsStayInsideTheOwningModuleBuildDirectory() {
        val layout = MinecraftTestSupport.layout
        val firstReport = MinecraftTestSupport.reportFile(
            "fixtures/report.json",
        )
        val secondReport = MinecraftTestSupport.reportFile(
            "fixtures/report.json",
        )

        assertNotEquals(firstReport, secondReport)
        assertTrue(firstReport.isBelow(layout.moduleBuildDirectory))
        assertTrue(secondReport.isBelow(layout.moduleBuildDirectory))
        assertFailsWith<IllegalArgumentException> {
            MinecraftTestSupport.reportFile("../../outside.json")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftTestSupport.temporaryFile("../../outside.bin")
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
