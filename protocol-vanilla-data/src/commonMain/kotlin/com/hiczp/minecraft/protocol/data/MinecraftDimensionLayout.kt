package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * The vertical layout and lighting context needed to encode/decode chunks in
 * one synchronized dimension type.
 */
data class MinecraftDimensionLayout(
    val id: Identifier,
    val registryId: Int,
    val minY: Int,
    val height: Int,
    val hasSkyLight: Boolean,
) {
    val sectionCount: Int
        get() = height / SECTION_HEIGHT

    init {
        require(registryId >= 0)
        require(height > 0 && height % SECTION_HEIGHT == 0) {
            "Dimension height must be a positive multiple of $SECTION_HEIGHT"
        }
        require(minY % SECTION_HEIGHT == 0) {
            "Dimension minimum Y must be a multiple of $SECTION_HEIGHT"
        }
    }

    companion object {
        private val dimensionTypeRegistry = Identifier("dimension_type")

        fun from(
            data: ProtocolDataSet,
            id: Identifier,
        ): MinecraftDimensionLayout =
            from(data.completeRegistryPackets(), id)

        fun from(
            registries: List<RegistryDataPacket>,
            id: Identifier,
        ): MinecraftDimensionLayout {
            val registry = registries.requireRegistry(dimensionTypeRegistry)
            val registryId = registry.entries.indexOfFirst { it.id == id }
            check(registryId >= 0) {
                "Dimension type $id is absent from $dimensionTypeRegistry"
            }
            return from(registry, registryId)
        }

        fun from(
            registries: List<RegistryDataPacket>,
            registryId: Int,
        ): MinecraftDimensionLayout =
            from(
                registries.requireRegistry(dimensionTypeRegistry),
                registryId,
            )

        private fun from(
            registry: RegistryDataPacket,
            registryId: Int,
        ): MinecraftDimensionLayout {
            val entry = registry.entries.getOrNull(registryId)
                ?: error(
                    "Dimension-type registry ID $registryId is absent from " +
                            dimensionTypeRegistry,
                )
            val value = entry.data as? NbtCompound
                ?: error(
                    "Dimension type ${entry.id} has no compound registry data",
                )
            return MinecraftDimensionLayout(
                id = entry.id,
                registryId = registryId,
                minY = value.requireInt("min_y"),
                height = value.requireInt("height"),
                hasSkyLight = value.requireBoolean("has_skylight"),
            )
        }

        private const val SECTION_HEIGHT: Int = 16
    }
}

fun ProtocolDataSet.completeRegistryPackets(): List<RegistryDataPacket> =
    registryPackets(emptyList())

fun ProtocolDataSet.registry(id: Identifier): RegistryDataPacket? =
    completeRegistryPackets().firstOrNull { it.registryId == id }

fun ProtocolDataSet.requireRegistry(id: Identifier): RegistryDataPacket =
    registry(id) ?: error("Synchronized registry $id does not exist")

fun ProtocolDataSet.registryEntry(
    registry: Identifier,
    entry: Identifier,
): RegistryEntry? =
    registry(registry)?.entries?.firstOrNull { it.id == entry }

fun ProtocolDataSet.registryId(
    registry: Identifier,
    entry: Identifier,
): Int? =
    registry(registry)?.entries?.indexOfFirst { it.id == entry }
        ?.takeIf { it >= 0 }

fun List<RegistryDataPacket>.registry(id: Identifier): RegistryDataPacket? =
    firstOrNull { it.registryId == id }

fun List<RegistryDataPacket>.requireRegistry(
    id: Identifier,
): RegistryDataPacket =
    registry(id) ?: error("Synchronized registry $id does not exist")

fun List<RegistryDataPacket>.registryEntry(
    registry: Identifier,
    entry: Identifier,
): RegistryEntry? =
    registry(registry)?.entries?.firstOrNull { it.id == entry }

fun List<RegistryDataPacket>.registryId(
    registry: Identifier,
    entry: Identifier,
): Int? =
    registry(registry)?.entries?.indexOfFirst { it.id == entry }
        ?.takeIf { it >= 0 }

private fun NbtCompound.requireInt(name: String): Int =
    (value[name] as? NbtInt)?.value
        ?: error("Dimension type has no integer '$name'")

private fun NbtCompound.requireBoolean(name: String): Boolean =
    (value[name] as? NbtByte)?.value?.toInt()?.let {
        when (it) {
            0 -> false
            1 -> true
            else -> error("Dimension type '$name' is not a Boolean byte")
        }
    } ?: error("Dimension type has no Boolean '$name'")
