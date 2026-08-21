package com.hiczp.minecraft.world.io

internal fun com.hiczp.minecraft.world.format.CompressedChunk?.bytesOrNull(): ByteArray? = this?.toByteArray()
