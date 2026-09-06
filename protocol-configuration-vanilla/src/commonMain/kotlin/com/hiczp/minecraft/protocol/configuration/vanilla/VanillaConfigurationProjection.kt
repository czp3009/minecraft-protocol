package com.hiczp.minecraft.protocol.configuration.vanilla

import com.hiczp.minecraft.protocol.configuration.DataPackConfigurationProjector
import com.hiczp.minecraft.protocol.configuration.DataPackRegistryProjector
import com.hiczp.minecraft.protocol.configuration.ResolvedConfigurationData
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.datapack.DataPackFormatVersion
import com.hiczp.minecraft.world.format.datapack.DataPackId
import com.hiczp.minecraft.world.format.datapack.DataPackStack
import com.hiczp.minecraft.world.format.datapack.ResolvedDataPackStack

/**
 * Projects an in-memory stack on top of the exact generated vanilla protocol defaults.
 *
 * The release-matched [VanillaConfigurationData.dataPackRegistryProjectors] cover ordinary vanilla resources.
 * Caller-supplied projectors replace matching defaults by registry ID and add projectors for custom registries.
 */
fun DataPackStack.toVanillaConfigurationData(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector> = emptyList(),
    dataPackFormatVersion: DataPackFormatVersion? = VanillaConfigurationData.dataPackFormatVersion,
    enabledFeatureFlags: Set<Identifier> = VanillaConfigurationData.enabledFeatureFlags,
): ResolvedConfigurationData =
    VanillaConfigurationData.dataPackConfigurationProjector(dataPackRegistryProjectorOverrides, enabledFeatureFlags)
        .project(this, dataPackFormatVersion)

/**
 * Projects an already resolved stack on top of the exact generated vanilla protocol defaults.
 *
 * The release-matched [VanillaConfigurationData.dataPackRegistryProjectors] cover ordinary vanilla resources.
 * Caller-supplied projectors replace matching defaults by registry ID and add projectors for custom registries.
 */
fun ResolvedDataPackStack.toVanillaConfigurationData(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector> = emptyList(),
    enabledFeatureFlags: Set<Identifier> = VanillaConfigurationData.enabledFeatureFlags,
): ResolvedConfigurationData = VanillaConfigurationData
    .dataPackConfigurationProjector(dataPackRegistryProjectorOverrides, enabledFeatureFlags)
    .project(this)

/**
 * Creates the bridge from parsed vanilla-based resources to Configuration protocol data.
 *
 * Caller-supplied projectors replace matching [VanillaConfigurationData.dataPackRegistryProjectors] by registry ID and
 * add custom registries. Construct [DataPackConfigurationProjector] directly when every default should be replaced.
 */
fun VanillaConfigurationData.dataPackConfigurationProjector(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector> = emptyList(),
    enabledFeatureFlags: Set<Identifier> = this.enabledFeatureFlags,
): DataPackConfigurationProjector = DataPackConfigurationProjector(
    baseConfigurationData = this,
    dataPackRegistryProjectors = dataPackRegistryProjectors.withOverrides(dataPackRegistryProjectorOverrides),
    preprojectedDataPackIds = setOf(DataPackId("vanilla")),
    enabledFeatureFlags = enabledFeatureFlags,
)

private fun List<DataPackRegistryProjector>.withOverrides(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector>,
): List<DataPackRegistryProjector> {
    val overrideRegistryIds = dataPackRegistryProjectorOverrides.map(DataPackRegistryProjector::registryId)
    require(overrideRegistryIds.distinct().size == overrideRegistryIds.size) {
        "Vanilla data-pack protocol projector has duplicate registry overrides"
    }
    val overridesByRegistryId = dataPackRegistryProjectorOverrides.associateBy(DataPackRegistryProjector::registryId)
    val defaultRegistryIds = mapTo(mutableSetOf(), DataPackRegistryProjector::registryId)
    return buildList {
        this@withOverrides.forEach { dataPackRegistryProjector ->
            add(overridesByRegistryId[dataPackRegistryProjector.registryId] ?: dataPackRegistryProjector)
        }
        dataPackRegistryProjectorOverrides.filterTo(this) { dataPackRegistryProjector ->
            dataPackRegistryProjector.registryId !in defaultRegistryIds
        }
    }
}
