package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.world.io.forWorldFiles
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSource

/** Selective NBT readers skip all fields outside these projections, including packed block/biome arrays. */
object ChunkInspection {
    private val nbtFormat = NbtFormat(NbtFormatConfiguration(ignoreUnknownKeys = true)).forWorldFiles()
    fun terrain(source: BufferedSource): TerrainInspection =
        nbtFormat.decodeFromSource(source.asKotlinxIoRawSource().buffered(), TerrainInspection.serializer())

    fun poi(source: BufferedSource): PoiInspection =
        nbtFormat.decodeFromSource(source.asKotlinxIoRawSource().buffered(), PoiInspection.serializer())
}

@Serializable
data class TerrainInspection(
    @SerialName("DataVersion") val dataVersion: Int,
    @SerialName("Status") val status: String,
    @SerialName("LastUpdate") val lastUpdate: Long = 0,
    val sections: List<SectionInspection> = emptyList(),
)

@Serializable
data class SectionInspection(
    @SerialName("Y") val y: Byte,
    @SerialName("block_states") val blocks: PaletteInspection? = null,
    val biomes: PaletteInspection? = null,
)

@Serializable
data class PaletteInspection(val palette: List<NbtTag>)

@Serializable
data class PoiInspection(@SerialName("Sections") val sections: Map<String, PoiSectionInspection> = emptyMap())

@Serializable
data class PoiSectionInspection(@SerialName("Valid") val valid: Boolean = false)
