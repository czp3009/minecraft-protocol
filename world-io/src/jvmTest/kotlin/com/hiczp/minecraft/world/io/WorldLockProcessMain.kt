package com.hiczp.minecraft.world.io

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

object WorldLockProcessMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1 || arguments.size == 2 && arguments[1] == "probe")
        val directory = Path.of(arguments[0])
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("session.lock"), CREATE, WRITE)
            .use { fileChannel ->
                if (arguments.size == 2) {
                    fileChannel.tryLock().use { fileLock ->
                        println(if (fileLock == null) "LOCKED" else "UNLOCKED")
                    }
                    return
                }
                val marker = ByteBuffer.wrap(
                    "☃".encodeToByteArray(),
                )
                fileChannel.write(marker)
                fileChannel.force(true)
                fileChannel.lock().use {
                    System.out.write("LOCKED\n".encodeToByteArray())
                    System.out.flush()
                    check(System.`in`.read() >= 0)
                }
            }
    }
}

internal fun startWorldLockProcess(directory: Path, probeOnly: Boolean = false): Process {
    val classpath = listOf(WorldLockProcessMain::class.java, Unit::class.java)
        .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
        .distinct()
        .joinToString(File.pathSeparator)
    val arguments = listOf("java", "-cp", classpath, WorldLockProcessMain::class.java.name, directory.toString())
    return ProcessBuilder(if (probeOnly) arguments + "probe" else arguments).redirectErrorStream(true).start()
}
