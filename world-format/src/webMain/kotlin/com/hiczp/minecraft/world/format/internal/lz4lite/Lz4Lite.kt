package com.hiczp.minecraft.world.format.internal.lz4lite

import org.khronos.webgl.Uint8Array

internal expect fun compressBlock(input: Uint8Array): Uint8Array

internal expect fun decompressBlock(
    input: Uint8Array,
    expectedSize: Int,
): Uint8Array
