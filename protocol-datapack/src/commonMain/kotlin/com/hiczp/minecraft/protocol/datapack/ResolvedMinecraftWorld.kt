package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBiomeIds
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
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
 * Resolves persisted level stems into semantic Chunk contexts against this exact registry order.
 *
 * Referenced types come from this protocol data, while inline types provide their layout directly. This path requires
 * no Play Login dimension-type raw ID and is therefore suitable for disk tools as well as custom endpoint composition.
 */
fun ResolvedProtocolData.resolveMinecraftChunkContexts(
    worldGenSettingsData: WorldGenSettingsData,
    defaultBlock: Identifier = MinecraftBlockIds.AIR,
    defaultBiome: Identifier = MinecraftBiomeIds.PLAINS,
): Map<DimensionId, MinecraftChunkContext> {
    val resolvedDimensionLayouts = resolveDimensionLayouts(worldGenSettingsData, allowInlineDimensionTypes = true)
    val chunkDataRegistries = completeProtocolRegistryContext.toChunkDataRegistries(defaultBlock, defaultBiome)
    return resolvedDimensionLayouts.mapValues { (dimensionId, resolvedDimensionLayout) ->
        MinecraftChunkContext.create(
            dimensionId = dimensionId,
            dimensionTypeLayout = resolvedDimensionLayout.dimensionTypeLayout,
            protocolRegistryContext = completeProtocolRegistryContext,
            chunkDataRegistries = chunkDataRegistries,
            defaultBlock = defaultBlock,
            defaultBiome = defaultBiome,
        )
    }
}

/**
 * Resolves persisted level stems into the Chunk contexts and synchronized identities required by a vanilla server.
 *
 * Inline dimension types cannot enter this path because Play Login requires a synchronized dimension-type raw ID. Use
 * [resolveMinecraftChunkContexts] when no server-negotiable dimension identity is required.
 */
fun ResolvedProtocolData.resolveMinecraftWorld(
    worldGenSettingsData: WorldGenSettingsData,
    defaultBlock: Identifier = MinecraftBlockIds.AIR,
    defaultBiome: Identifier = MinecraftBiomeIds.PLAINS,
): ResolvedMinecraftWorld {
    val resolvedDimensionLayouts = resolveDimensionLayouts(worldGenSettingsData, allowInlineDimensionTypes = false)
    val chunkDataRegistries = completeProtocolRegistryContext.toChunkDataRegistries(defaultBlock, defaultBiome)
    val dimensions = resolvedDimensionLayouts.mapValues { (dimensionId, resolvedDimensionLayout) ->
        val minecraftDimensionContext = MinecraftDimensionContext.create(
            dimensionId = dimensionId,
            minecraftDimensionLayout = requireNotNull(resolvedDimensionLayout.minecraftDimensionLayout),
            protocolRegistryContext = completeProtocolRegistryContext,
        )
        MinecraftChunkContext.create(
            minecraftDimensionContext = minecraftDimensionContext,
            chunkDataRegistries = chunkDataRegistries,
            defaultBlock = defaultBlock,
            defaultBiome = defaultBiome,
        )
    }
    return ResolvedMinecraftWorld(this, dimensions)
}

private fun ResolvedProtocolData.resolveDimensionLayouts(
    worldGenSettingsData: WorldGenSettingsData,
    allowInlineDimensionTypes: Boolean,
): Map<DimensionId, ResolvedDimensionLayout> {
    val dimensionTypeRegistryPacket = registryPacket(MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY)
    val sortedDimensions = worldGenSettingsData.dimensions.entries.sortedBy { (dimensionId) -> dimensionId.toString() }
    val resolvedDimensionLayouts = linkedMapOf<DimensionId, ResolvedDimensionLayout>()
    val failures = linkedMapOf<DimensionId, String>()

    sortedDimensions.forEach { (dimensionId, worldGenDimension) ->
        when (val worldGenDimensionType = worldGenDimension.type) {
            is WorldGenDimensionType.Inline -> {
                if (!allowInlineDimensionTypes) {
                    failures[dimensionId] = "inline dimension type has no synchronized registry raw ID"
                    return@forEach
                }
                val dimensionTypeLayout = try {
                    DimensionTypeLayout.fromNbt(worldGenDimensionType.dimensionTypeData)
                } catch (failure: DimensionTypeFormatException) {
                    failures[dimensionId] = "inline dimension type is invalid: ${failure.message}"
                    return@forEach
                }
                resolvedDimensionLayouts[dimensionId] = ResolvedDimensionLayout(dimensionTypeLayout)
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
                resolvedDimensionLayouts[dimensionId] = ResolvedDimensionLayout(
                    dimensionTypeLayout = dimensionTypeLayout,
                    minecraftDimensionLayout = MinecraftDimensionLayout(
                        dimensionTypeId = dimensionTypeId,
                        dimensionTypeRawId = dimensionTypeRawId,
                        dimensionTypeLayout = dimensionTypeLayout,
                    ),
                )
            }
        }
    }
    if (failures.isNotEmpty()) throw MinecraftWorldResolutionException(failures)
    return resolvedDimensionLayouts
}

private data class ResolvedDimensionLayout(
    val dimensionTypeLayout: DimensionTypeLayout,
    val minecraftDimensionLayout: MinecraftDimensionLayout? = null,
)
