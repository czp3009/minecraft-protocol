package com.hiczp.minecraft.test.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class TestFilesTest {
    init {
        configureHostedTestSupportForJvmTests()
    }

    @Test
    fun runtimeDirectoriesUseVersionAndUuidLayers() {
        val minecraftTestLayout = HostedMinecraftTestSupport.minecraftTestLayout
        val first = minecraftTestLayout.newRuntimeDirectory(
            MinecraftRuntimeKind.OFFICIAL_SERVER,
        )
        val second = minecraftTestLayout.newRuntimeDirectory(
            MinecraftRuntimeKind.OFFICIAL_SERVER,
        )
        try {
            assertNotEquals(first, second)
            assertEquals(minecraftTestLayout.minecraftVersion, first.parent?.fileName?.toString())
            assertEquals("official-server", first.parent?.parent?.fileName?.toString())
            assertEquals("runtimes", first.parent?.parent?.parent?.fileName?.toString())
            assertTrue(first.fileName.toString().startsWith("run-"))
            assertTrue(second.fileName.toString().startsWith("run-"))
            assertTrue(first.isBelow(minecraftTestLayout.hostWorkRoot))
            assertTrue(second.isBelow(minecraftTestLayout.hostWorkRoot))
        } finally {
            first.deleteTree()
            second.deleteTree()
        }
    }

    @Test
    fun treeCopiesCanExcludeImmutableSubtrees() {
        val root = Files.createTempDirectory("fixture-tree-copy-")
        try {
            val source = root.resolve("source")
            val destination = root.resolve("destination")
            source.createDirectories()
            source.resolve("options.txt").writeText("original")
            source.resolve("mods").createDirectories()
            source.resolve("mods").resolve("fixture.jar").writeText("mod")
            source.resolve(".fabric").resolve("processedMods").createDirectories()
            source.resolve(".fabric").resolve("processedMods").resolve("nested.jar")
                .writeText("cache")
            source.resolve("logs").createDirectories()

            source.copyTreeTo(
                destination = destination,
                excludedRelativePaths = setOf(
                    ".fabric/processedMods",
                    "mods",
                ),
            )

            assertEquals("original", destination.resolve("options.txt").readText())
            assertTrue(destination.resolve("logs").isDirectory())
            assertFalse(destination.resolve("mods").exists())
            assertFalse(destination.resolve(".fabric").resolve("processedMods").exists())
            destination.resolve("options.txt").writeText("private")
            assertEquals("original", source.resolve("options.txt").readText())
        } finally {
            root.deleteTree()
        }
    }

    @Test
    fun singleFilesUseHardLinksWithCopyFallback() {
        val root = Files.createTempDirectory("fixture-file-link-")
        try {
            val source = root.resolve("source.jar")
            val linked = root.resolve("linked.jar")
            val occupied = root.resolve("occupied.jar")
            source.writeText("immutable")
            occupied.writeText("old")

            val usedHardLink = source.linkFileTo(linked)
            val usedFallback = source.linkFileTo(occupied)

            assertEquals(
                usedHardLink,
                Files.isSameFile(source, linked),
            )
            assertFalse(usedFallback)
            assertFalse(Files.isSameFile(source, occupied))
            assertEquals("immutable", linked.readText())
            assertEquals("immutable", occupied.readText())
        } finally {
            root.deleteTree()
        }
    }

    @Test
    fun immutableDirectoriesUseSymbolicLinksWithSafeTreeFallback() {
        val root = Files.createTempDirectory("fixture-directory-link-")
        try {
            val source = root.resolve("source")
            val destination = root.resolve("destination")
            val sourceFile = source.resolve("nested").resolve("fixture.jar")
            sourceFile.parent.createDirectories()
            sourceFile.writeText("immutable")

            val usedSymbolicLink = source.linkDirectoryTo(destination)

            assertEquals(
                usedSymbolicLink,
                Files.isSymbolicLink(destination),
            )
            assertEquals(
                "immutable",
                destination.resolve("nested").resolve("fixture.jar").readText(),
            )

            destination.deleteTree()

            assertFalse(
                Files.exists(
                    destination,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ),
            )
            assertEquals("immutable", sourceFile.readText())
        } finally {
            root.deleteTree()
        }
    }

    @Test
    fun resourceCloseIsIdempotentAndCleanupIsAsynchronous() = runTest {
        val directory = HostedMinecraftTestSupport.newScratchDirectory()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val cleanupCompleted = CompletableDeferred<Unit>()
        val managedMinecraftTestResource = HostedMinecraftTestSupport.manageTestResource(directory) {
            cleanupStarted.complete(Unit)
            allowCleanup.await()
        }
        managedMinecraftTestResource.invokeOnCleanupCompletion {
            cleanupCompleted.complete(Unit)
        }

        managedMinecraftTestResource.close()
        managedMinecraftTestResource.close()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            managedMinecraftTestResource.awaitCleanup()
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

    @Test
    fun resourceDirectoryIsDeletedWhenCleanupFails() = runTest {
        val directory = HostedMinecraftTestSupport.newScratchDirectory()
        val expectedFailure = IllegalStateException("cleanup failed")
        val managedMinecraftTestResource = HostedMinecraftTestSupport.manageTestResource(directory) {
            throw expectedFailure
        }

        managedMinecraftTestResource.close()
        val actualFailure = assertFailsWith<IllegalStateException> {
            managedMinecraftTestResource.awaitCleanup()
        }
        HostedMinecraftTestSupport.awaitCleanup()

        assertEquals(expectedFailure.message, actualFailure.message)
        assertFalse(directory.exists())
    }

    private fun Path.isBelow(ancestor: Path): Boolean =
        generateSequence(parent) { it.parent }.any { it == ancestor }
}
