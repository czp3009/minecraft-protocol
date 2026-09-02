package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBiomeIds
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.DimensionId

/**
 * The protocol registries and vertical layout active for one world dimension.
 *
 * This is the shared handoff before an application chooses the semantic block and biome defaults required by
 * [MinecraftChunkContext]. Custom packet decoders may use the registry and layout values directly.
 */
class MinecraftDimensionContext private constructor(
    val dimensionId: DimensionId,
    val minecraftDimensionLayout: MinecraftDimensionLayout,
    val protocolRegistryContext: ProtocolRegistryContext,
) {
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

    override fun equals(other: Any?): Boolean =
        other is MinecraftDimensionContext &&
                dimensionId == other.dimensionId &&
                minecraftDimensionLayout == other.minecraftDimensionLayout &&
                protocolRegistryContext == other.protocolRegistryContext

    override fun hashCode(): Int {
        var result = dimensionId.hashCode()
        result = 31 * result + minecraftDimensionLayout.hashCode()
        return 31 * result + protocolRegistryContext.hashCode()
    }

    override fun toString(): String =
        "MinecraftDimensionContext(dimensionId=$dimensionId, minecraftDimensionLayout=$minecraftDimensionLayout, protocolRegistryContext=$protocolRegistryContext)"

    companion object {
        fun create(
            dimensionId: DimensionId,
            minecraftDimensionLayout: MinecraftDimensionLayout,
            protocolRegistryContext: ProtocolRegistryContext,
        ): MinecraftDimensionContext {
            val sectionCount = minecraftDimensionLayout.sectionCount
            val activeProtocolRegistryContext = if (protocolRegistryContext.chunkSectionCount == sectionCount) {
                protocolRegistryContext
            } else {
                protocolRegistryContext.withChunkSectionCount(sectionCount)
            }
            return MinecraftDimensionContext(
                dimensionId = dimensionId,
                minecraftDimensionLayout = minecraftDimensionLayout,
                protocolRegistryContext = activeProtocolRegistryContext,
            )
        }
    }
}
