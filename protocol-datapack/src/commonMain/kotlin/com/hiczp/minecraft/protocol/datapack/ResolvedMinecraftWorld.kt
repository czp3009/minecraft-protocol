package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeFormatException
import com.hiczp.minecraft.world.format.DimensionTypeLayout
import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData

/** A complete server-side world projection with one ready-to-use Chunk context per declared dimension. */
class ResolvedMinecraftWorld internal constructor(
    val protocolData: ResolvedProtocolData,
    dimensions: Map<DimensionId, MinecraftChunkContext>,
) {
    val dimensions: Map<DimensionId, MinecraftChunkContext> = dimensions.toMap()

    init {
        require(this.dimensions.all { (dimensionId, minecraftChunkContext) ->
            dimensionId == minecraftChunkContext.dimensionId
        }) {
            "Resolved world dimension keys must match their Chunk contexts"
        }
    }

    fun dimension(dimensionId: DimensionId): MinecraftChunkContext =
        requireNotNull(dimensions[dimensionId]) { "World does not declare dimension $dimensionId" }
}

/** All dimension-specific failures found before any [MinecraftChunkContext] is constructed. */
class MinecraftWorldResolutionException(
    failures: Map<DimensionId, String>,
) : IllegalStateException(
    "Unable to resolve Minecraft world dimensions: ${failures.entries.joinToString { (dimensionId, reason) -> "$dimensionId: $reason" }}",
) {
    val failures: Map<DimensionId, String> = failures.toMap()

    init {
        require(this.failures.isNotEmpty()) { "A world-resolution failure must identify at least one dimension" }
    }
}

/**
 * Resolves persisted level stems against this exact Configuration registry order.
 *
 * Inline dimension types remain usable through the lower-level world-format codecs but cannot enter this path because
 * Play Login requires a synchronized dimension-type raw ID.
 */
fun ResolvedProtocolData.resolveMinecraftWorld(
    worldGenSettingsData: WorldGenSettingsData,
    defaultBlock: Identifier = Identifier("air"),
    defaultBiome: Identifier = Identifier("plains"),
): ResolvedMinecraftWorld {
    val dimensionTypeRegistryPacket = registryPacket(MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY)
    val sortedDimensions = worldGenSettingsData.dimensions.entries.sortedBy { (dimensionId) -> dimensionId.toString() }
    val minecraftDimensionLayouts = linkedMapOf<DimensionId, MinecraftDimensionLayout>()
    val failures = linkedMapOf<DimensionId, String>()

    sortedDimensions.forEach { (dimensionId, worldGenDimension) ->
        when (val worldGenDimensionType = worldGenDimension.type) {
            is WorldGenDimensionType.Inline -> {
                failures[dimensionId] = "inline dimension type has no synchronized registry raw ID"
            }

            is WorldGenDimensionType.Reference -> {
                val dimensionTypeId = Identifier.parse(worldGenDimensionType.dimensionTypeId.toString())
                if (dimensionTypeRegistryPacket == null) {
                    failures[dimensionId] = "referenced dimension type $dimensionTypeId is missing"
                    return@forEach
                }
                val dimensionTypeRawId = dimensionTypeRegistryPacket.entries.indexOfFirst { registryEntry ->
                    registryEntry.id == dimensionTypeId
                }
                if (dimensionTypeRawId < 0) {
                    failures[dimensionId] = "referenced dimension type $dimensionTypeId is missing"
                    return@forEach
                }
                val dimensionTypeData = dimensionTypeRegistryPacket.entries[dimensionTypeRawId].data as? NbtCompound
                if (dimensionTypeData == null) {
                    failures[dimensionId] = "referenced dimension type $dimensionTypeId has no compound data"
                    return@forEach
                }
                val dimensionTypeLayout = try {
                    DimensionTypeLayout.fromNbt(dimensionTypeData)
                } catch (failure: DimensionTypeFormatException) {
                    failures[dimensionId] =
                        "referenced dimension type $dimensionTypeId is invalid: ${failure.message}"
                    return@forEach
                }
                minecraftDimensionLayouts[dimensionId] = MinecraftDimensionLayout(
                    dimensionTypeId = dimensionTypeId,
                    dimensionTypeRawId = dimensionTypeRawId,
                    dimensionTypeLayout = dimensionTypeLayout,
                )
            }
        }
    }
    if (failures.isNotEmpty()) throw MinecraftWorldResolutionException(failures)

    val chunkDataRegistries = completeProtocolRegistryContext.toChunkDataRegistries(defaultBlock, defaultBiome)
    val dimensions = minecraftDimensionLayouts.mapValues { (dimensionId, minecraftDimensionLayout) ->
        MinecraftChunkContext.create(
            dimensionId = dimensionId,
            minecraftDimensionLayout = minecraftDimensionLayout,
            protocolRegistryContext = completeProtocolRegistryContext,
            chunkDataRegistries = chunkDataRegistries,
            defaultBlock = defaultBlock,
            defaultBiome = defaultBiome,
        )
    }
    return ResolvedMinecraftWorld(this, dimensions)
}
