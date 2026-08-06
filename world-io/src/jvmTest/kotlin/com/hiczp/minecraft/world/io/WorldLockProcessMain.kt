package com.hiczp.minecraft.world.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

object WorldLockProcessMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1)
        val directory = Path.of(arguments.single())
        java.nio.file.Files.createDirectories(directory)
        FileChannel.open(directory.resolve("session.lock"), CREATE, WRITE)
            .use { channel ->
                val marker = ByteBuffer.wrap(
                    "☃".encodeToByteArray(),
                )
                channel.position(0)
                while (marker.hasRemaining()) channel.write(marker)
                channel.force(true)
                channel.lock().use {
                    System.out.write("LOCKED\n".encodeToByteArray())
                    System.out.flush()
                    check(System.`in`.read() >= 0)
                }
            }
    }
}
