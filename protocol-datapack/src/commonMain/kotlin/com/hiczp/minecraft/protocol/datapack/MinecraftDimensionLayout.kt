package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.RegistryEntry

/**
 * The vertical layout and lighting context needed to encode/decode chunks in
 * one synchronized dimension type.
 */
data class MinecraftDimensionLayout(
    val dimensionTypeId: Identifier,
    val dimensionTypeRawId: Int,
    val minY: Int,
    val height: Int,
    val hasSkyLight: Boolean,
) {
    val sectionCount: Int
        get() = height / SECTION_HEIGHT

    init {
        require(dimensionTypeRawId >= 0)
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
            protocolData: ProtocolData,
            dimensionTypeId: Identifier,
        ): MinecraftDimensionLayout =
            from(protocolData.completeSynchronizedRegistryPackets(), dimensionTypeId)

        fun from(
            synchronizedRegistryPackets: List<RegistryDataPacket>,
            dimensionTypeId: Identifier,
        ): MinecraftDimensionLayout {
            val dimensionTypeRegistryPacket = synchronizedRegistryPackets.requireRegistryPacket(
                dimensionTypeRegistry,
            )
            val dimensionTypeRawId = dimensionTypeRegistryPacket.entries.indexOfFirst { registryEntry ->
                registryEntry.id == dimensionTypeId
            }
            check(dimensionTypeRawId >= 0) {
                "Dimension type $dimensionTypeId is absent from $dimensionTypeRegistry"
            }
            return from(dimensionTypeRegistryPacket, dimensionTypeRawId)
        }

        fun from(
            synchronizedRegistryPackets: List<RegistryDataPacket>,
            dimensionTypeRawId: Int,
        ): MinecraftDimensionLayout =
            from(
                synchronizedRegistryPackets.requireRegistryPacket(dimensionTypeRegistry),
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
                    registryDataPacket.registryId == dimensionTypeRegistry
                },
            ) {
                "Configuration must provide exactly one synchronized registry $dimensionTypeRegistry"
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
            val dimensionTypeRegistryEntry = dimensionTypeRegistryPacket.entries.getOrNull(dimensionTypeRawId)
                ?: error(
                    "Dimension-type registry ID $dimensionTypeRawId is absent from $dimensionTypeRegistry",
                )
            val dimensionTypeData = dimensionTypeRegistryEntry.data as? NbtCompound
                ?: error(
                    "Dimension type ${dimensionTypeRegistryEntry.id} has no compound registry data",
                )
            return MinecraftDimensionLayout(
                dimensionTypeId = dimensionTypeRegistryEntry.id,
                dimensionTypeRawId = dimensionTypeRawId,
                minY = dimensionTypeData.requireInt("min_y"),
                height = dimensionTypeData.requireInt("height"),
                hasSkyLight = dimensionTypeData.requireBoolean("has_skylight"),
            )
        }

        private const val SECTION_HEIGHT: Int = 16
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
