@file:JsModule("js-xxhash")
@file:JsNonModule

package com.hiczp.minecraft.world.format.internal.jsxxhash

import org.khronos.webgl.Uint8Array

internal actual external fun xxHash32(
    input: Uint8Array,
    seed: Int,
): Double
