package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.RegistryEntry
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.DimensionTypeFormatException
import com.hiczp.minecraft.world.format.DimensionTypeLayout

/**
 * The vertical layout and lighting context needed to encode/decode chunks in
 * one synchronized dimension type.
 */
data class MinecraftDimensionLayout(
    val dimensionTypeId: Identifier,
    val dimensionTypeRawId: Int,
    val dimensionTypeLayout: DimensionTypeLayout,
) {
    val minY: Int
        get() = dimensionTypeLayout.minY

    val height: Int
        get() = dimensionTypeLayout.height

    val logicalHeight: Int
        get() = dimensionTypeLayout.logicalHeight

    val logicalBlockYRange: IntRange
        get() = dimensionTypeLayout.logicalBlockYRange

    val hasSkyLight: Boolean
        get() = dimensionTypeLayout.hasSkyLight

    val hasCeiling: Boolean
        get() = dimensionTypeLayout.hasCeiling

    val chunkLayout: ChunkLayout
        get() = dimensionTypeLayout.chunkLayout

    val sectionCount: Int
        get() = chunkLayout.sectionCount

    init {
        require(dimensionTypeRawId >= 0) { "Dimension-type raw ID must be non-negative" }
    }

    companion object {
        val DIMENSION_TYPE_REGISTRY: Identifier = Identifier("dimension_type")

        fun from(
            protocolData: ProtocolData,
            dimensionTypeId: Identifier,
        ): MinecraftDimensionLayout =
            from(protocolData.completeSynchronizedRegistryPackets(), dimensionTypeId)

        fun from(
            synchronizedRegistryPackets: List<RegistryDataPacket>,
            dimensionTypeId: Identifier,
        ): MinecraftDimensionLayout {
            val dimensionTypeRegistryPacket = synchronizedRegistryPackets.requireRegistryPacket(
                DIMENSION_TYPE_REGISTRY,
            )
            val dimensionTypeRawId = dimensionTypeRegistryPacket.entries.indexOfFirst { registryEntry ->
                registryEntry.id == dimensionTypeId
            }
            val dimensionTypeRegistryEntry = dimensionTypeRegistryPacket.entries.getOrNull(dimensionTypeRawId)
                ?: throw IllegalArgumentException("Dimension type $dimensionTypeId is absent from $DIMENSION_TYPE_REGISTRY")
            return from(dimensionTypeRegistryEntry, dimensionTypeRawId)
        }

        fun from(
            synchronizedRegistryPackets: List<RegistryDataPacket>,
            dimensionTypeRawId: Int,
        ): MinecraftDimensionLayout {
            val dimensionTypeRegistryPacket = synchronizedRegistryPackets.requireRegistryPacket(DIMENSION_TYPE_REGISTRY)
            return from(dimensionTypeRegistryPacket.requireDimensionTypeEntry(dimensionTypeRawId), dimensionTypeRawId)
        }

        /**
         * Resolves one dimension-type raw ID from the Configuration registry snapshot.
         *
         * The synchronized dimension-type entry is authoritative when it contains NBT. When Known Packs caused the
         * server to omit that NBT, [protocolData] supplies the matching known entry instead.
         */
        fun from(
            dimensionTypeRawId: Int,
            synchronizedRegistryPackets: List<RegistryDataPacket>,
            protocolData: ProtocolData,
        ): MinecraftDimensionLayout {
            val dimensionTypeRegistryPacket = synchronizedRegistryPackets.requireRegistryPacket(DIMENSION_TYPE_REGISTRY)
            val dimensionTypeRegistryEntry = dimensionTypeRegistryPacket.requireDimensionTypeEntry(dimensionTypeRawId)
            return if (dimensionTypeRegistryEntry.data == null) {
                from(protocolData, dimensionTypeRegistryEntry.id).copy(dimensionTypeRawId = dimensionTypeRawId)
            } else {
                from(dimensionTypeRegistryEntry, dimensionTypeRawId)
            }
        }

        private fun from(
            dimensionTypeRegistryEntry: RegistryEntry,
            dimensionTypeRawId: Int,
        ): MinecraftDimensionLayout {
            val dimensionTypeData = dimensionTypeRegistryEntry.data as? NbtCompound
                ?: throw DimensionTypeFormatException(
                    "Dimension type ${dimensionTypeRegistryEntry.id} has no compound registry data",
                )
            return MinecraftDimensionLayout(
                dimensionTypeId = dimensionTypeRegistryEntry.id,
                dimensionTypeRawId = dimensionTypeRawId,
                dimensionTypeLayout = DimensionTypeLayout.fromNbt(dimensionTypeData),
            )
        }

        private fun RegistryDataPacket.requireDimensionTypeEntry(dimensionTypeRawId: Int): RegistryEntry =
            requireNotNull(entries.getOrNull(dimensionTypeRawId)) {
                "Dimension-type registry ID $dimensionTypeRawId is absent from $DIMENSION_TYPE_REGISTRY"
            }
    }
}

fun ProtocolData.completeSynchronizedRegistryPackets(): List<RegistryDataPacket> =
    synchronizedRegistryPackets(emptyList())

fun ProtocolData.registryPacket(registryId: Identifier): RegistryDataPacket? =
    completeSynchronizedRegistryPackets().firstOrNull { registryDataPacket ->
        registryDataPacket.registryId == registryId
    }

fun ProtocolData.requireRegistryPacket(registryId: Identifier): RegistryDataPacket =
    requireNotNull(registryPacket(registryId)) { "Synchronized registry $registryId does not exist" }

fun ProtocolData.registryEntry(
    registryId: Identifier,
    registryEntryId: Identifier,
): RegistryEntry? = registryPacket(registryId)?.entries?.firstOrNull { registryEntry ->
    registryEntry.id == registryEntryId
}

fun ProtocolData.registryRawId(
    registryId: Identifier,
    registryEntryId: Identifier,
): Int? =
    registryPacket(registryId)?.entries?.indexOfFirst { registryEntry -> registryEntry.id == registryEntryId }
        ?.takeIf { it >= 0 }

fun List<RegistryDataPacket>.registryPacket(registryId: Identifier): RegistryDataPacket? =
    firstOrNull { registryDataPacket -> registryDataPacket.registryId == registryId }

fun List<RegistryDataPacket>.requireRegistryPacket(
    registryId: Identifier,
): RegistryDataPacket = requireNotNull(registryPacket(registryId)) {
    "Synchronized registry $registryId does not exist"
}

fun List<RegistryDataPacket>.registryEntry(
    registryId: Identifier,
    registryEntryId: Identifier,
): RegistryEntry? = registryPacket(registryId)?.entries?.firstOrNull { registryEntry ->
    registryEntry.id == registryEntryId
}

fun List<RegistryDataPacket>.registryRawId(
    registryId: Identifier,
    registryEntryId: Identifier,
): Int? =
    registryPacket(registryId)?.entries?.indexOfFirst { registryEntry -> registryEntry.id == registryEntryId }
        ?.takeIf { it >= 0 }
