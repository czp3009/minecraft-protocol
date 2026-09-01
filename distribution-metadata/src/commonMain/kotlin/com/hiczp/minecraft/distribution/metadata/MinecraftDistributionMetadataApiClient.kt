package com.hiczp.minecraft.distribution.metadata

import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.*

class MinecraftDistributionMetadataApiClient(
    httpClient: HttpClient,
    pistonMetaBaseUrl: String = PISTON_META_BASE_URL,
) : MinecraftDistributionMetadataApi by createMinecraftDistributionMetadataApi(httpClient, pistonMetaBaseUrl)

private fun createMinecraftDistributionMetadataApi(
    httpClient: HttpClient,
    pistonMetaBaseUrl: String,
): MinecraftDistributionMetadataApi = Ktorfit.Builder()
    .baseUrl(pistonMetaBaseUrl)
    .httpClient(httpClient)
    .build()
    .createMinecraftDistributionMetadataApi()

private const val PISTON_META_BASE_URL = "https://piston-meta.mojang.com/"
