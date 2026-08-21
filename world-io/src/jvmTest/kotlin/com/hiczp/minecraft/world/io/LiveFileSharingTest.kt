package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.CompressedChunk
import com.hiczp.minecraft.world.format.Compression
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.use
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveFileSharingTest {
    @Test
    fun liveReaderOpensWhileWorldDirectoryLockIsHeld() = runTest {
        val temporaryDirectory = Files.createTempDirectory(
            "world-io-live-lock-",
        )
        val root = temporaryDirectory.toOkioPath()
        val position = ChunkPosition(0, 0)
        try {
            MinecraftWorldAccess.open(root).use { minecraftWorldAccess ->
                minecraftWorldAccess.openRegion(position.region).use { regionHandle ->
                    regionHandle.writeCompressedChunk(position, sharingChunk(5))
                }
                val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root)
                assertContentEquals(
                    byteArrayOf(5),
                    liveMinecraftWorldAccess.openRegion(position.region).readCompressedChunk(position).bytesOrNull(),
                )
            }
            assertFalse(MinecraftWorldAccess.isLocked(root))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun separateLiveRegionReadsObserveFreshHeaders() = runTest {
        val temporaryDirectory = Files.createTempDirectory(
            "world-io-live-header-",
        )
        val root = temporaryDirectory.toOkioPath()
        val paths = MinecraftWorldPaths(root)
        val first = ChunkPosition(0, 0)
        val second = ChunkPosition(1, 0)
        try {
            val initial = RegionStorage(paths)
            try {
                initial.writeCompressedChunk(first, sharingChunk(1))
            } finally {
                initial.close()
            }

            val reader = LiveMinecraftWorldAccess.open(root)
            val liveRegionHandle = reader.openRegion(first.region)
            assertContentEquals(
                byteArrayOf(1),
                liveRegionHandle.readCompressedChunk(first).bytesOrNull(),
            )

            val updater = RegionStorage(paths)
            try {
                updater.writeCompressedChunk(first, sharingChunk(2))
                updater.writeCompressedChunk(second, sharingChunk(3))
            } finally {
                updater.close()
            }

            assertContentEquals(
                byteArrayOf(2),
                liveRegionHandle.readCompressedChunk(first).bytesOrNull(),
            )
            assertContentEquals(
                byteArrayOf(3),
                liveRegionHandle.readCompressedChunk(second).bytesOrNull(),
            )
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun liveHandleCoexistsWithOfficialStyleDsyncInBothOpenOrders() {
        val temporaryDirectory = Files.createTempDirectory(
            "world-io-live-dsync-",
        )
        val target = temporaryDirectory.resolve("r.0.0.mca")
        try {
            Files.write(target, byteArrayOf(1))
            val serverFirst = HeldDsyncProcess.start(target)
            try {
                FileSystem.SYSTEM.openLiveReadOnly(target.toOkioPath())
                    .use { handle ->
                        serverFirst.writeAndForce()
                        assertContentEquals(
                            byteArrayOf(9),
                            handle.readBytes(0L, 1),
                        )
                    }
            } finally {
                serverFirst.close()
            }

            Files.write(target, byteArrayOf(2))
            FileSystem.SYSTEM.openLiveReadOnly(target.toOkioPath())
                .use { handle ->
                    val readerFirst = HeldDsyncProcess.start(target)
                    try {
                        readerFirst.writeAndForce()
                        assertContentEquals(
                            byteArrayOf(9),
                            handle.readBytes(0L, 1),
                        )
                    } finally {
                        readerFirst.close()
                    }
                }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(
                temporaryDirectory.toOkioPath(),
                mustExist = false,
            )
        }
    }

    @Test
    fun liveHandleDoesNotBlockOfficialStyleSidecarReplacement() {
        val temporaryDirectory = Files.createTempDirectory(
            "world-io-live-replace-",
        )
        val target = temporaryDirectory.resolve("c.0.0.mcc")
        val replacement = temporaryDirectory.resolve("replacement.tmp")
        try {
            Files.write(target, byteArrayOf(4))
            Files.write(replacement, byteArrayOf(7))
            FileSystem.SYSTEM.openLiveReadOnly(target.toOkioPath())
                .use { handle ->
                    val process = ProcessBuilder(
                        "java",
                        "-cp",
                        liveSharingClasspath(),
                        LIVE_SHARING_MAIN_CLASS,
                        "REPLACE",
                        target.toString(),
                        replacement.toString(),
                    ).redirectErrorStream(true).start()
                    val response = process.inputReader().use(BufferedReader::readLine)
                    assertEquals("REPLACE_OK", response)
                    assertEquals(0, process.waitFor())
                    assertContentEquals(
                        byteArrayOf(4),
                        handle.readBytes(0L, 1),
                    )
                }
            assertContentEquals(byteArrayOf(7), Files.readAllBytes(target))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(
                temporaryDirectory.toOkioPath(),
                mustExist = false,
            )
        }
    }
}

private class HeldDsyncProcess private constructor(
    private val process: Process,
    private val reader: BufferedReader,
    private val writer: BufferedWriter,
) {
    fun writeAndForce() {
        command("WRITE", "WRITE_OK")
    }

    fun close() {
        if (!process.isAlive) return
        try {
            command("CLOSE", "CLOSE_OK")
            check(process.waitFor() == 0) {
                "DSYNC holder exited with ${process.exitValue()}"
            }
        } finally {
            writer.close()
            reader.close()
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }

    private fun command(command: String, expected: String) {
        writer.write(command)
        writer.newLine()
        writer.flush()
        val response = reader.readLine()
        check(response == expected) {
            "Expected $expected from DSYNC holder, received $response"
        }
    }

    companion object {
        fun start(target: Path): HeldDsyncProcess {
            val process = ProcessBuilder(
                "java",
                "-cp",
                liveSharingClasspath(),
                LIVE_SHARING_MAIN_CLASS,
                "HOLD_DSYNC",
                target.toString(),
            ).redirectErrorStream(true).start()
            val reader = process.inputReader().buffered()
            val writer = process.outputWriter().buffered()
            val readiness = reader.readLine()
            check(readiness == "READY") {
                "DSYNC holder failed before readiness: $readiness"
            }
            return HeldDsyncProcess(process, reader, writer)
        }
    }
}

private fun FileHandle.readBytes(offset: Long, byteCount: Int): ByteArray {
    val buffer = Buffer()
    val read = read(offset, buffer, byteCount.toLong())
    return if (read < 0L) ByteArray(0) else buffer.readByteArray()
}

private fun liveSharingClasspath(): String = listOf(
    LiveFileSharingProcessMain::class.java,
    Unit::class.java,
).map { type ->
    File(type.protectionDomain.codeSource.location.toURI()).absolutePath
}.distinct().joinToString(File.pathSeparator)

private fun sharingChunk(value: Int): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = byteArrayOf(value.toByte()),
)

private const val LIVE_SHARING_MAIN_CLASS = "com.hiczp.minecraft.world.io.LiveFileSharingProcessMain"
