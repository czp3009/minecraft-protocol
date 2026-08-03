package com.hiczp.minecraft.test

private external interface NodeProcess {
    fun once(event: String, listener: () -> Unit): NodeProcess
}

internal actual fun installMinecraftTestShutdownHook(
    closeResources: () -> Unit,
    @Suppress("UNUSED_PARAMETER") awaitCleanup: suspend () -> Unit,
) {
    nodeProcess().once("beforeExit", closeResources)
}

private fun nodeProcess(): NodeProcess =
    js("eval('require')('process')")
