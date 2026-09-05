package com.hiczp.minecraft.distribution.metadata

import io.ktor.client.statement.*

/** Prepares a streaming download of an asset object from its hash, without checking the hash or response bytes. */
suspend fun MinecraftDistributionMetadataApi.downloadAsset(hash: String): HttpStatement =
    download(minecraftAssetUrl(hash))

/** Prepares a streaming download using only the descriptor's URL; its SHA-1 and size are not checked. */
suspend fun MinecraftDistributionMetadataApi.download(minecraftDownload: MinecraftDownload): HttpStatement =
    download(minecraftDownload.url)

/** Prepares a streaming download using the asset object's hash, without integrity validation. */
suspend fun MinecraftDistributionMetadataApi.download(minecraftAssetObject: MinecraftAssetObject): HttpStatement =
    downloadAsset(minecraftAssetObject.hash)
