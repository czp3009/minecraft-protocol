package com.hiczp.minecraft.distribution.metadata

import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class MinecraftAssetIndexReference(
    val id: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long,
    val url: String,
)

@Serializable
data class MinecraftAssetIndex(
    val objects: Map<String, MinecraftAssetObject>,
)

@Serializable
data class MinecraftAssetObject(
    val hash: String,
    val size: Long,
)

/** Derives the asset object's relative path from its lowercased hash, without validating it or accessing a filesystem. */
fun minecraftAssetPath(hash: String): String {
    val normalizedSha1 = hash.lowercase()
    return "${normalizedSha1.take(SHA1_PATH_PREFIX_LENGTH)}/$normalizedSha1"
}

internal fun minecraftAssetUrl(hash: String): String = URLBuilder(
    protocol = URLProtocol.HTTPS,
    host = MINECRAFT_ASSET_OBJECT_HOST,
).apply {
    appendPathSegments(minecraftAssetPath(hash))
}.buildString()

private const val MINECRAFT_ASSET_OBJECT_HOST = "resources.download.minecraft.net"
private const val SHA1_PATH_PREFIX_LENGTH = 2
