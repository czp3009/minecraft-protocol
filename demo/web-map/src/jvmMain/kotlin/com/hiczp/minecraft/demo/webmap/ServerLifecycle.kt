package com.hiczp.minecraft.demo.webmap

internal actual fun installShutdownHook() {
    val mainThread = Thread.currentThread()
    Runtime.getRuntime().addShutdownHook(Thread({
        shutdownRequested.complete(Unit)
        // JVM shutdown does not wait for ordinary threads. Keep the hook alive through the main flow's cleanup.
        mainThread.join()
    }, "MinecraftWebMapShutdown"))
}
