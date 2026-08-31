package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBiomeIds
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.DimensionId

/**
 * The validated protocol registries and vertical layout active for one world dimension.
 *
 * This is the shared handoff before an application chooses the semantic block and biome defaults required by
 * [MinecraftChunkContext]. Custom packet decoders may use the registry and layout values directly.
 */
data class MinecraftDimensionContext(
    val dimensionId: DimensionId,
    val minecraftDimensionLayout: MinecraftDimensionLayout,
    val protocolRegistryContext: ProtocolRegistryContext,
) {
    init {
        val dimensionTypeRawId = minecraftDimensionLayout.dimensionTypeRawId
        val dimensionTypeRegistry = protocolRegistryContext.requireRegistry(
            MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY,
        )
        val dimensionTypeRegistryEntry = dimensionTypeRegistry[dimensionTypeRawId]
        require(dimensionTypeRegistryEntry?.id == minecraftDimensionLayout.dimensionTypeId) {
            val actual = dimensionTypeRegistryEntry?.id ?: "missing"
            "Raw ID $dimensionTypeRawId resolves to $actual, not ${minecraftDimensionLayout.dimensionTypeId}"
        }
        require(protocolRegistryContext.chunkSectionCount == minecraftDimensionLayout.sectionCount) {
            val dimensionTypeId = minecraftDimensionLayout.dimensionTypeId
            "Context has ${protocolRegistryContext.chunkSectionCount} Chunk Sections; $dimensionTypeId requires ${minecraftDimensionLayout.sectionCount}"
        }
    }

    val chunkLayout: ChunkLayout
        get() = minecraftDimensionLayout.chunkLayout

    fun createMinecraftChunkContext(
        defaultBlock: Identifier = MinecraftBlockIds.AIR,
        defaultBiome: Identifier = MinecraftBiomeIds.PLAINS,
    ): MinecraftChunkContext = MinecraftChunkContext.create(
        minecraftDimensionContext = this,
        defaultBlock = defaultBlock,
        defaultBiome = defaultBiome,
    )

    companion object {
        fun create(
            dimensionId: DimensionId,
            minecraftDimensionLayout: MinecraftDimensionLayout,
            protocolRegistryContext: ProtocolRegistryContext,
        ): MinecraftDimensionContext {
            val sectionCount = minecraftDimensionLayout.sectionCount
            val activeProtocolRegistryContext = when (protocolRegistryContext.chunkSectionCount) {
                null -> protocolRegistryContext.withChunkSectionCount(sectionCount)
                sectionCount -> protocolRegistryContext
                else -> {
                    val actualSectionCount = protocolRegistryContext.chunkSectionCount
                    val dimensionTypeId = minecraftDimensionLayout.dimensionTypeId
                    throw IllegalArgumentException(
                        "Context has $actualSectionCount Chunk Sections; $dimensionTypeId requires $sectionCount",
                    )
                }
            }
            return MinecraftDimensionContext(
                dimensionId = dimensionId,
                minecraftDimensionLayout = minecraftDimensionLayout,
                protocolRegistryContext = activeProtocolRegistryContext,
            )
        }
    }
}
