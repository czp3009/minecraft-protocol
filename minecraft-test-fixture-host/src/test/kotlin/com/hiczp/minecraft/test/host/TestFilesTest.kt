package com.hiczp.minecraft.test.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
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
            assertTrue(first.isBelow(layout.hostWorkRoot))
            assertTrue(second.isBelow(layout.hostWorkRoot))
        } finally {
            first.deleteTree()
            second.deleteTree()
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
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            resource.awaitCleanup()
        }
        cleanupStarted.await()
        assertTrue(directory.isDirectory())
        assertFalse(cleanupCompleted.isCompleted)
        assertFalse(cleanup.isCompleted)

        allowCleanup.complete(Unit)
        HostedMinecraftTestSupport.awaitCleanup()
        cleanup.await()
        cleanupCompleted.await()
        assertFalse(directory.exists())
    }

    private fun Path.isBelow(ancestor: Path): Boolean =
        generateSequence(parent) { it.parent }.any { it == ancestor }
}
