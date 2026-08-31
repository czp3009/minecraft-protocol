@file:JsModule("node:crypto")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hiczp.minecraft.protocol.transport.internal.nodecrypto

import org.khronos.webgl.Uint8Array

internal actual external fun createCipheriv(
    algorithm: String,
    key: Uint8Array,
    initializationVector: Uint8Array,
): NodeCipher

internal actual external fun createDecipheriv(
    algorithm: String,
    key: Uint8Array,
    initializationVector: Uint8Array,
): NodeCipher
