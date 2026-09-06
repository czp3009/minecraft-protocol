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
) {
    /** Relative object path beneath the asset download root or an application's objects directory. */
    val path: String get() = path(hash)

    companion object {
        /** Derives a relative asset path from a lowercased hash without validation or filesystem access. */
        fun path(hash: String): String {
            val normalizedSha1 = hash.lowercase()
            return "${normalizedSha1.take(SHA1_PATH_PREFIX_LENGTH)}/$normalizedSha1"
        }
    }
}

internal fun minecraftAssetUrl(hash: String): String = URLBuilder(
    protocol = URLProtocol.HTTPS,
    host = MINECRAFT_ASSET_OBJECT_HOST,
).apply {
    appendPathSegments(MinecraftAssetObject.path(hash))
}.buildString()

private const val MINECRAFT_ASSET_OBJECT_HOST = "resources.download.minecraft.net"
private const val SHA1_PATH_PREFIX_LENGTH = 2
