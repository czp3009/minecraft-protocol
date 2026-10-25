package com.hiczp.minecraft.demo.nbtreader

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fputs
import platform.posix.stderr
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
internal actual fun exitWithError(message: String): Nothing {
    fputs("$message\n", stderr)
    exitProcess(1)
}

internal actual fun printOutputLine(message: String) {
    println(message)
}
