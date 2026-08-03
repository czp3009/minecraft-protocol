package com.hiczp.minecraft.test

import kotlinx.coroutines.runBlocking

internal actual fun installMinecraftTestShutdownHook(
    closeResources: () -> Unit,
    awaitCleanup: suspend () -> Unit,
) {
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                closeResources()
                runBlocking { awaitCleanup() }
            },
            "minecraft-test-resource-shutdown",
        ),
    )
}
