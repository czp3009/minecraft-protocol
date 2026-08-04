@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hiczp.minecraft.test

internal actual fun platformEnvironmentVariable(name: String): String? =
    nodeEnvironment(name)

@JsFun("(name) => typeof process === 'undefined' ? null : process.env[name] || null")
private external fun nodeEnvironment(name: String): String?
