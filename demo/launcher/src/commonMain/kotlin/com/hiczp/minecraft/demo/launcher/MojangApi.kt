package com.hiczp.minecraft.demo.launcher

import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Streaming
import de.jensklingenberg.ktorfit.http.Url
import io.ktor.client.*
import io.ktor.client.statement.*

internal interface MojangApi {
    @GET("mc/game/version_manifest_v2.json")
    suspend fun versionManifest(): VersionManifest

    @GET("")
    suspend fun versionMetadata(@Url url: String): VersionMetadata

    @Streaming
    @GET("")
    suspend fun download(@Url url: String): HttpStatement
}

internal fun createMojangApi(httpClient: HttpClient): MojangApi = Ktorfit.Builder()
    .baseUrl(MOJANG_METADATA_BASE_URL)
    .httpClient(httpClient)
    .build()
    .createMojangApi()

private const val MOJANG_METADATA_BASE_URL = "https://piston-meta.mojang.com/"
