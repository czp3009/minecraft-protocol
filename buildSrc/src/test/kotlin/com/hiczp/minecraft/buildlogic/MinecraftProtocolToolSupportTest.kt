package com.hiczp.minecraft.buildlogic

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftProtocolToolSupportTest {
    @Test
    fun deleteTreeDoesNotFollowDirectorySymbolicLinks() {
        val root = Files.createTempDirectory("build-logic-delete-tree-")
        try {
            val sourceFile = root.resolve("source/nested/source.txt")
            val workDirectory = root.resolve("work")
            Files.createDirectories(sourceFile.parent)
            Files.createDirectories(workDirectory)
            Files.writeString(sourceFile, "source")
            if (!createSymbolicLink(
                    workDirectory.resolve("live-link"),
                    root.resolve("source"),
                )
            ) {
                return
            }
            Files.createSymbolicLink(
                workDirectory.resolve("dangling-link"),
                root.resolve("missing"),
            )

            workDirectory.deleteTree()

            assertFalse(
                Files.exists(workDirectory, LinkOption.NOFOLLOW_LINKS),
            )
            assertTrue(Files.isRegularFile(sourceFile))
        } finally {
            root.deleteTree()
        }
    }

    private fun createSymbolicLink(link: Path, target: Path): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
