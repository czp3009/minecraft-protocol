package com.hiczp.minecraft.test.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
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
    fun treeCopiesCanExcludeImmutableSubtrees() {
        val root = Path(Files.createTempDirectory("fixture-tree-copy-").toString())
        try {
            val source = Path(root, "source")
            val destination = Path(root, "destination")
            Path(source, "options.txt").writeText("original")
            Path(source, "mods", "fixture.jar").writeText("mod")
            Path(source, ".fabric", "processedMods", "nested.jar")
                .writeText("cache")
            Path(source, "logs").ensureDirectory()

            source.copyTreeTo(
                destination = destination,
                excludedRelativePaths = setOf(
                    ".fabric/processedMods",
                    "mods",
                ),
            )

            assertEquals("original", Path(destination, "options.txt").readText())
            assertTrue(Path(destination, "logs").isDirectory())
            assertFalse(Path(destination, "mods").exists())
            assertFalse(Path(destination, ".fabric", "processedMods").exists())
            Path(destination, "options.txt").writeText("private")
            assertEquals("original", Path(source, "options.txt").readText())
        } finally {
            root.deleteTree()
        }
    }

    @Test
    fun singleFilesUseHardLinksWithCopyFallback() {
        val root = Path(Files.createTempDirectory("fixture-file-link-").toString())
        try {
            val source = Path(root, "source.jar")
            val linked = Path(root, "linked.jar")
            val occupied = Path(root, "occupied.jar")
            source.writeText("immutable")
            occupied.writeText("old")

            val usedHardLink = source.linkFileTo(linked)
            val usedFallback = source.linkFileTo(occupied)

            assertEquals(
                usedHardLink,
                Files.isSameFile(source.toNioPath(), linked.toNioPath()),
            )
            assertFalse(usedFallback)
            assertFalse(Files.isSameFile(source.toNioPath(), occupied.toNioPath()))
            assertEquals("immutable", linked.readText())
            assertEquals("immutable", occupied.readText())
        } finally {
            root.deleteTree()
        }
    }

    @Test
    fun immutableDirectoriesUseSymbolicLinksWithSafeTreeFallback() {
        val root = Path(
            Files.createTempDirectory("fixture-directory-link-").toString(),
        )
        try {
            val source = Path(root, "source")
            val destination = Path(root, "destination")
            val sourceFile = Path(source, "nested", "fixture.jar")
            sourceFile.writeText("immutable")

            val usedSymbolicLink = source.linkDirectoryTo(destination)

            assertEquals(
                usedSymbolicLink,
                Files.isSymbolicLink(destination.toNioPath()),
            )
            assertEquals(
                "immutable",
                Path(destination, "nested", "fixture.jar").readText(),
            )

            destination.deleteTree()

            assertFalse(
                Files.exists(
                    destination.toNioPath(),
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
