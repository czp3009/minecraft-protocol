package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData

/** Shared dimension facts, without registries, raw IDs, persistence metadata or codecs. */
data class WorldChunkContexts(val dimensions: Map<DimensionId, ChunkContext>) {
    fun dimension(dimensionId: DimensionId): ChunkContext =
        requireNotNull(dimensions[dimensionId]) { "World does not declare dimension $dimensionId" }
}

class WorldChunkContextResolutionException(val failures: Map<DimensionId, String>) : IllegalStateException(
    "Unable to resolve world dimensions: ${failures.entries.joinToString { (dimensionId, reason) -> "$dimensionId: $reason" }}",
) {
    init {
        require(failures.isNotEmpty()) { "A world-resolution failure must identify at least one dimension" }
    }
}

/**
 * Resolves persisted level stems. Inline layouts are read locally; [dimensionTypeLayout] supplies referenced types.
 * All failures are collected before returning a complete map. Defaults are domain values supplied by the caller.
 */
fun WorldGenSettingsData.resolveWorldChunkContexts(
    defaultBlockState: BlockState,
    defaultBiome: BiomeId,
    dimensionTypeLayout: (DimensionTypeId) -> DimensionTypeLayout?,
): WorldChunkContexts {
    val failures = linkedMapOf<DimensionId, String>()
    val resolved = linkedMapOf<DimensionId, ChunkContext>()
    dimensions.forEach { (dimensionId, worldGenDimension) ->
        val layout = try {
            when (val type = worldGenDimension.type) {
                is WorldGenDimensionType.Inline -> DimensionTypeLayout.fromNbt(type.dimensionTypeData)
                is WorldGenDimensionType.Reference -> dimensionTypeLayout(type.dimensionTypeId)
                    ?: throw DimensionTypeFormatException("Referenced dimension type ${type.dimensionTypeId} is missing")
            }
        } catch (failure: DimensionTypeFormatException) {
            failures[dimensionId] = failure.message.orEmpty()
            return@forEach
        }
        resolved[dimensionId] = ChunkContext(dimensionId, layout, defaultBlockState, defaultBiome)
    }
    if (failures.isNotEmpty()) throw WorldChunkContextResolutionException(failures)
    return WorldChunkContexts(resolved)
}
