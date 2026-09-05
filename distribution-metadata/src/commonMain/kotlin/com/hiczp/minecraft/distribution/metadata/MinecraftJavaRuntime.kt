@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
value class MinecraftJavaRuntimeCatalog(
    val platforms: Map<String, Map<String, List<MinecraftJavaRuntimeEntry>>>,
)

@Serializable
data class MinecraftJavaRuntimeEntry(
    val availability: MinecraftJavaRuntimeAvailability,
    val manifest: MinecraftDownload,
    val version: MinecraftJavaRuntimeVersion,
)

@Serializable
data class MinecraftJavaRuntimeAvailability(
    val group: Int,
    val progress: Int,
)

@Serializable
data class MinecraftJavaRuntimeVersion(
    val name: String,
    val released: String,
)

@Serializable
data class MinecraftJavaRuntimeManifest(
    val files: Map<String, MinecraftJavaRuntimeFile>,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface MinecraftJavaRuntimeFile {
    @Serializable
    @SerialName("file")
    data class File(
        val downloads: MinecraftJavaRuntimeFileDownloads,
        val executable: Boolean,
    ) : MinecraftJavaRuntimeFile

    @Serializable
    @SerialName("directory")
    data object Directory : MinecraftJavaRuntimeFile

    @Serializable
    @SerialName("link")
    data class Link(
        val target: String,
    ) : MinecraftJavaRuntimeFile
}

@Serializable
data class MinecraftJavaRuntimeFileDownloads(
    val raw: MinecraftDownload,
    val lzma: MinecraftDownload? = null,
)
