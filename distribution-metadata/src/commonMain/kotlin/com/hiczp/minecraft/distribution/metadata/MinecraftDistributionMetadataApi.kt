package com.hiczp.minecraft.distribution.metadata

import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Streaming
import de.jensklingenberg.ktorfit.http.Url
import io.ktor.client.statement.*

interface MinecraftDistributionMetadataApi {
    @GET("mc/game/version_manifest_v2.json")
    suspend fun versionManifest(): MinecraftVersionManifest

    @GET("")
    suspend fun versionMetadata(
        @Url url: String,
    ): MinecraftVersionMetadata

    @GET("")
    suspend fun assetIndex(
        @Url url: String,
    ): MinecraftAssetIndex

    @GET("v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json")
    suspend fun javaRuntimeCatalog(): MinecraftJavaRuntimeCatalog

    @GET("")
    suspend fun javaRuntimeManifest(
        @Url url: String,
    ): MinecraftJavaRuntimeManifest

    /** Prepares a GET without sending it. Consume the response stream within [HttpStatement.execute]'s block. */
    @Streaming
    @GET("")
    suspend fun download(
        @Url url: String,
    ): HttpStatement
}
