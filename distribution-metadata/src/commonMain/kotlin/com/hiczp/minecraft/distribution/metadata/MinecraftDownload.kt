package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.Serializable

@Serializable
data class MinecraftDownload(
    val sha1: String,
    val size: Long,
    val url: String,
)
