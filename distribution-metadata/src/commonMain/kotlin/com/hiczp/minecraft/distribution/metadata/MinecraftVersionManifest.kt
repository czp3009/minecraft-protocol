package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.Serializable

@Serializable
data class MinecraftVersionManifest(
    val latest: MinecraftLatestVersions,
    val versions: List<MinecraftVersionReference>,
)

@Serializable
data class MinecraftLatestVersions(
    val release: String,
    val snapshot: String,
)

@Serializable
data class MinecraftVersionReference(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String,
    val complianceLevel: Int,
)
