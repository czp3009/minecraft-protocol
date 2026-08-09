package com.hiczp.minecraft.world.format.internal.jsxxhash

import org.khronos.webgl.Uint8Array

internal expect fun xxHash32(input: Uint8Array, seed: Int): Double
