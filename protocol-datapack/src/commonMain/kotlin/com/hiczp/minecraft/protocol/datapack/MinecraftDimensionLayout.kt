package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
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
            require(dimensionTypeRawId >= 0) {
                "Dimension type $dimensionTypeId is absent from $DIMENSION_TYPE_REGISTRY"
            }
            return from(dimensionTypeRegistryPacket, dimensionTypeRawId)
        }

        fun from(
            synchronizedRegistryPackets: List<RegistryDataPacket>,
            dimensionTypeRawId: Int,
        ): MinecraftDimensionLayout =
            from(
                synchronizedRegistryPackets.requireRegistryPacket(DIMENSION_TYPE_REGISTRY),
                dimensionTypeRawId,
            )

        /**
         * Resolves the active dimension selected by [playLoginPacket] from the Configuration registry snapshot.
         *
         * The synchronized dimension-type entry is authoritative when it contains NBT. When Known Packs caused the
         * server to omit that NBT, [protocolData] supplies the matching known entry instead.
         */
        fun from(
            playLoginPacket: PlayLoginPacket,
            synchronizedRegistryPackets: List<RegistryDataPacket>,
            protocolData: ProtocolData,
        ): MinecraftDimensionLayout {
            require(playLoginPacket.spawnInfo.dimension in playLoginPacket.levels) {
                val dimensionId = playLoginPacket.spawnInfo.dimension
                "Play Login selected dimension $dimensionId, but it is absent from the advertised levels"
            }
            val dimensionTypeRegistryPacket = requireNotNull(
                synchronizedRegistryPackets.singleOrNull { registryDataPacket ->
                    registryDataPacket.registryId == DIMENSION_TYPE_REGISTRY
                },
            ) {
                "Configuration must provide exactly one synchronized registry $DIMENSION_TYPE_REGISTRY"
            }
            val dimensionTypeRawId = playLoginPacket.spawnInfo.dimensionTypeId
            val dimensionTypeRegistryEntry = requireNotNull(
                dimensionTypeRegistryPacket.entries.getOrNull(dimensionTypeRawId),
            ) {
                "Play Login selected absent dimension-type registry ID $dimensionTypeRawId"
            }
            return if (dimensionTypeRegistryEntry.data == null) {
                from(protocolData, dimensionTypeRegistryEntry.id)
            } else {
                from(listOf(dimensionTypeRegistryPacket), dimensionTypeRawId)
            }
        }

        private fun from(
            dimensionTypeRegistryPacket: RegistryDataPacket,
            dimensionTypeRawId: Int,
        ): MinecraftDimensionLayout {
            val dimensionTypeRegistryEntry = requireNotNull(
                dimensionTypeRegistryPacket.entries.getOrNull(dimensionTypeRawId),
            ) {
                "Dimension-type registry ID $dimensionTypeRawId is absent from $DIMENSION_TYPE_REGISTRY"
            }
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
    }
}

fun ProtocolData.completeSynchronizedRegistryPackets(): List<RegistryDataPacket> =
    synchronizedRegistryPackets(emptyList())

fun ProtocolData.registryPacket(registryId: Identifier): RegistryDataPacket? =
    completeSynchronizedRegistryPackets().firstOrNull { registryDataPacket ->
        registryDataPacket.registryId == registryId
    }

fun ProtocolData.requireRegistryPacket(registryId: Identifier): RegistryDataPacket =
    registryPacket(registryId) ?: error("Synchronized registry $registryId does not exist")

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
): RegistryDataPacket = registryPacket(registryId) ?: error("Synchronized registry $registryId does not exist")

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
