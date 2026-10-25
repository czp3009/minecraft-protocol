package com.hiczp.minecraft.demo.nbtreader

import kotlin.system.exitProcess

internal actual fun exitWithError(message: String): Nothing {
    System.err.write("$message\n".encodeToByteArray())
    System.err.flush()
    exitProcess(1)
}

internal actual fun printOutputLine(message: String) {
    System.out.write("$message\n".encodeToByteArray())
    System.out.flush()
}
