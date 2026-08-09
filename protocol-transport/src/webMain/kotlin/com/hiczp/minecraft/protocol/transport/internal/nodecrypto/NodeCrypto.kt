@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.hiczp.minecraft.protocol.transport.internal.nodecrypto

import org.khronos.webgl.Uint8Array

internal external interface NodeCipher : JsAny {
    fun update(input: Uint8Array): Uint8Array
}

internal expect fun createCipheriv(
    algorithm: String,
    key: Uint8Array,
    initializationVector: Uint8Array,
): NodeCipher

internal expect fun createDecipheriv(
    algorithm: String,
    key: Uint8Array,
    initializationVector: Uint8Array,
): NodeCipher
