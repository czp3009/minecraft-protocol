@file:JsModule("lz4-lite")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hiczp.minecraft.world.format.internal.lz4lite

import org.khronos.webgl.Uint8Array

internal actual external fun compressBlock(input: Uint8Array): Uint8Array

internal actual external fun decompressBlock(
    input: Uint8Array,
    expectedSize: Int,
): Uint8Array
