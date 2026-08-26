package com.hiczp.minecraft.world.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import kotlin.system.exitProcess

object LiveFileSharingProcessMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        try {
            when (arguments.firstOrNull()) {
                "HOLD_DSYNC" -> holdDsync(Path.of(arguments[1]))
                "REPLACE" -> replace(
                    target = Path.of(arguments[1]),
                    replacement = Path.of(arguments[2]),
                )

                else -> error("Unknown live file-sharing process mode")
            }
        } catch (failure: Throwable) {
            protocol(
                "FAILED",
                failure::class.simpleName,
                failure.message,
            )
            exitProcess(1)
        }
    }

    private fun holdDsync(path: Path) {
        FileChannel.open(path, CREATE, READ, WRITE, DSYNC).use { fileChannel ->
            protocol("READY")
            while (true) {
                when (readlnOrNull()) {
                    "WRITE" -> {
                        fileChannel.write(ByteBuffer.wrap(byteArrayOf(9)), 0L)
                        fileChannel.force(true)
                        protocol("WRITE_OK")
                    }

                    "CLOSE", null -> {
                        protocol("CLOSE_OK")
                        return
                    }

                    else -> error("Unknown holder command")
                }
            }
        }
    }

    private fun replace(target: Path, replacement: Path) {
        Files.move(replacement, target, REPLACE_EXISTING)
        protocol("REPLACE_OK")
    }

    private fun protocol(vararg fields: String?) {
        val line = fields.joinToString("\t") { field ->
            field.toString()
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
        }
        System.out.write("$line\n".encodeToByteArray())
        System.out.flush()
    }
}
