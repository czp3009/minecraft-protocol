@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.minecraft.test

import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.runBlocking
import platform.posix.atexit

private var closeMinecraftTestResources: (() -> Unit)? = null
private var awaitMinecraftTestCleanup: (suspend () -> Unit)? = null

internal actual fun installMinecraftTestShutdownHook(
    closeResources: () -> Unit,
    awaitCleanup: suspend () -> Unit,
) {
    closeMinecraftTestResources = closeResources
    awaitMinecraftTestCleanup = awaitCleanup
    check(atexit(staticCFunction(::runMinecraftTestShutdownHook)) == 0) {
        "Could not register the Minecraft test-resource shutdown hook"
    }
}

private fun runMinecraftTestShutdownHook() {
    closeMinecraftTestResources?.invoke()
    runBlocking {
        awaitMinecraftTestCleanup?.invoke()
    }
}
