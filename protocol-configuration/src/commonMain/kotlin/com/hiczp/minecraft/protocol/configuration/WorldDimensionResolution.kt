package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData

/** Supplies referenced dimension layouts from complete Configuration evidence; inline types remain world data. */
fun ConfigurationData.resolveWorldChunkContexts(
    worldGenSettingsData: WorldGenSettingsData,
    defaultBlockState: BlockState,
    defaultBiome: BiomeId,
): WorldChunkContexts {
    val dimensionTypeRegistryPacket = registryPacket(MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY)
    return worldGenSettingsData.resolveWorldChunkContexts(defaultBlockState, defaultBiome) { dimensionTypeId ->
        val registryEntry = dimensionTypeRegistryPacket?.entries?.firstOrNull {
            it.id == Identifier.parse(dimensionTypeId.toString())
        }
        registryEntry?.let {
            val data = it.data as? NbtCompound
                ?: throw DimensionTypeFormatException("Referenced dimension type $dimensionTypeId has no compound data")
            DimensionTypeLayout.fromNbt(data)
        }
    }
}

/** Resolves the synchronized dimension identities required by a server's Play Login, collecting every failure. */
fun ConfigurationData.resolveMinecraftDimensions(
    worldGenSettingsData: WorldGenSettingsData,
): Map<DimensionId, MinecraftDimensionContext> {
    val dimensionTypeRegistryPacket = registryPacket(MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY)
    val resolved = linkedMapOf<DimensionId, MinecraftDimensionContext>()
    val failures = linkedMapOf<DimensionId, String>()
    worldGenSettingsData.dimensions.entries.sortedBy { (dimensionId) -> dimensionId.toString() }
        .forEach { (dimensionId, worldGenDimension) ->
            val reference = worldGenDimension.type as? WorldGenDimensionType.Reference
            if (reference == null) {
                failures[dimensionId] = "Inline dimension type has no synchronized registry raw ID"
                return@forEach
            }
            val dimensionTypeId = Identifier.parse(reference.dimensionTypeId.toString())
            val rawId = dimensionTypeRegistryPacket?.entries?.indexOfFirst { it.id == dimensionTypeId } ?: -1
            if (rawId < 0) {
                failures[dimensionId] = "Referenced dimension type $dimensionTypeId is missing"
                return@forEach
            }
            val data = dimensionTypeRegistryPacket?.entries?.get(rawId)?.data as? NbtCompound
            if (data == null) {
                failures[dimensionId] = "Referenced dimension type $dimensionTypeId has no compound data"
                return@forEach
            }
            val layout = try {
                DimensionTypeLayout.fromNbt(data)
            } catch (failure: DimensionTypeFormatException) {
                failures[dimensionId] = "Referenced dimension type $dimensionTypeId is invalid: ${failure.message}"
                return@forEach
            }
            resolved[dimensionId] = MinecraftDimensionContext(
                dimensionId,
                MinecraftDimensionLayout(dimensionTypeId, rawId, layout),
                completePacketCodecContext,
            )
        }
    if (failures.isNotEmpty()) throw WorldChunkContextResolutionException(failures)
    return resolved
}
