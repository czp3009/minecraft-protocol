package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.CompressedChunk

internal fun CompressedChunk?.bytesOrNull(): ByteArray? = this?.toByteArray()
