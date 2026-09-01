package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.Serializable

@Serializable
data class MinecraftVersionMetadata(
    val arguments: MinecraftArguments,
    val assetIndex: MinecraftAssetIndexReference,
    val assets: String,
    val complianceLevel: Int,
    val downloads: MinecraftVersionDownloads,
    val id: String,
    val javaVersion: MinecraftJavaVersion,
    val libraries: List<MinecraftLibrary>,
    val logging: MinecraftLoggingConfiguration,
    val mainClass: String,
    val minimumLauncherVersion: Int,
    val releaseTime: String,
    val time: String,
    val type: String,
)

@Serializable
data class MinecraftVersionDownloads(
    val client: MinecraftDownload,
    val server: MinecraftDownload,
)

@Serializable
data class MinecraftJavaVersion(
    val component: String,
    val majorVersion: Int,
)

@Serializable
data class MinecraftLibrary(
    val downloads: MinecraftLibraryDownloads,
    val name: String,
    val rules: List<MinecraftRule> = emptyList(),
)

@Serializable
data class MinecraftLibraryDownloads(
    val artifact: MinecraftLibraryDownload,
)

@Serializable
data class MinecraftLibraryDownload(
    val path: String,
    val sha1: String,
    val size: Long,
    val url: String,
)

@Serializable
data class MinecraftLoggingConfiguration(
    val client: MinecraftClientLoggingConfiguration,
)

@Serializable
data class MinecraftClientLoggingConfiguration(
    val argument: String,
    val file: MinecraftLoggingFile,
    val type: String,
)

@Serializable
data class MinecraftLoggingFile(
    val id: String,
    val sha1: String,
    val size: Long,
    val url: String,
)

@Serializable
data class MinecraftRule(
    val action: String,
    val features: Map<String, Boolean>? = null,
    val os: MinecraftOperatingSystemRule? = null,
)

@Serializable
data class MinecraftOperatingSystemRule(
    val name: String? = null,
    val arch: String? = null,
    val versionRange: MinecraftOperatingSystemVersionRange? = null,
)

@Serializable
data class MinecraftOperatingSystemVersionRange(
    val min: String? = null,
    val max: String? = null,
)
