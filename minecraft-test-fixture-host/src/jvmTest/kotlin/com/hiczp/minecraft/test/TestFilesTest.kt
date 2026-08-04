package com.hiczp.minecraft.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class TestFilesTest {
    init {
        configureHostedTestSupportForJvmTests()
    }

    @Test
    fun runtimeDirectoriesUseVersionAndUuidLayers() {
        val layout = HostedMinecraftTestSupport.layout
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
            assertTrue(first.name.startsWith("run-"))
            assertTrue(second.name.startsWith("run-"))
            assertTrue(first.isBelow(layout.fixtureWorkRoot))
            assertTrue(second.isBelow(layout.fixtureWorkRoot))
        } finally {
            first.deleteTree()
            second.deleteTree()
        }
    }

    @Test
    fun hostPathsStayInsideFixtureWorkRoot() {
        val layout = HostedMinecraftTestSupport.layout
        val firstReport = HostedMinecraftTestSupport.reportFile(
            "fixtures/report.json",
        )
        val secondReport = HostedMinecraftTestSupport.reportFile(
            "fixtures/report.json",
        )

        assertNotEquals(firstReport, secondReport)
        assertTrue(firstReport.isBelow(layout.fixtureWorkRoot))
        assertTrue(secondReport.isBelow(layout.fixtureWorkRoot))
        assertFailsWith<IllegalArgumentException> {
            HostedMinecraftTestSupport.reportFile("../../outside.json")
        }
    }

    @Test
    fun resourceCloseIsIdempotentAndCleanupIsAsynchronous() = runTest {
        val directory = HostedMinecraftTestSupport.newScratchDirectory()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val cleanupCompleted = CompletableDeferred<Unit>()
        val resource = HostedMinecraftTestSupport.manageTestResource(directory) {
            cleanupStarted.complete(Unit)
            allowCleanup.await()
        }
        resource.invokeOnCleanupCompletion {
            cleanupCompleted.complete(Unit)
        }

        resource.close()
        resource.close()
        cleanupStarted.await()
        assertTrue(directory.isDirectory())
        assertFalse(cleanupCompleted.isCompleted)

        allowCleanup.complete(Unit)
        HostedMinecraftTestSupport.awaitCleanup()
        cleanupCompleted.await()
        assertFalse(directory.exists())
    }

    @Test
    fun reportWriterProducesParseableStructuredJson() {
        val scratch = HostedMinecraftTestSupport.newScratchDirectory()
        try {
            val report = Path(scratch, "structured.json")
            report.writeJson(
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
        } finally {
            scratch.deleteTree()
        }
    }

    private fun Path.isBelow(ancestor: Path): Boolean =
        generateSequence(parent) { it.parent }.any { it == ancestor }
}
