@file:JsModule("js-xxhash")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hiczp.minecraft.world.format.internal.jsxxhash

import org.khronos.webgl.Uint8Array

internal actual external fun xxHash32(
    input: Uint8Array,
    seed: Int,
): Double
