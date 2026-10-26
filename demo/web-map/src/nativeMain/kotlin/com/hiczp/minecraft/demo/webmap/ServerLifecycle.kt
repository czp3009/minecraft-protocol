package com.hiczp.minecraft.demo.webmap

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.signal

@OptIn(ExperimentalForeignApi::class)
internal actual fun installShutdownHook() {
    // Ktor installs its hook in start(). Replace it afterwards: blocking stop() inside a Native signal handler
    // can deadlock (KTOR-9308/KTOR-9309). Wake the main flow and let it stop the server and close WebMapRuntime.
    val shutdownHandler = staticCFunction<Int, Unit> { shutdownRequested.complete(Unit) }
    signal(SIGINT, shutdownHandler)
    signal(SIGTERM, shutdownHandler)
}
