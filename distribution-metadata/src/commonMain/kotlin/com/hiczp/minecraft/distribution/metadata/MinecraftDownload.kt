package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.Serializable

@Serializable
data class MinecraftDownload(
    val sha1: String,
    val size: Long,
    val url: String,
)

/** Copies the download fields unchanged; the library path remains on the original descriptor. */
fun MinecraftLibraryDownload.toDownload(): MinecraftDownload = MinecraftDownload(
    sha1 = sha1,
    size = size,
    url = url,
)

/** Copies the download fields unchanged; the logging file ID remains on the original descriptor. */
fun MinecraftLoggingFile.toDownload(): MinecraftDownload = MinecraftDownload(
    sha1 = sha1,
    size = size,
    url = url,
)

fun MinecraftAssetObject.toDownload(): MinecraftDownload = MinecraftDownload(
    sha1 = hash.lowercase(),
    size = size,
    url = minecraftAssetUrl(hash),
)
