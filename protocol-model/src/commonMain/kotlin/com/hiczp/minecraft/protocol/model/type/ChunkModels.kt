package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongArraySerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = HeightmapTypeSerializer::class)
enum class HeightmapType {
    WORLD_SURFACE_WG,
    WORLD_SURFACE,
    OCEAN_FLOOR_WG,
    OCEAN_FLOOR,
    MOTION_BLOCKING,
    MOTION_BLOCKING_NO_LEAVES,
}

@Serializable
data class BlockEntityInfo(
    val packedXZ: Byte,
    val y: Short,
    @VarInt
    val typeId: Int,
    @NbtEndOptional
    @NetworkNbt
    val tag: NbtCompound?,
) {
    init {
        require(typeId >= 0) { "A block-entity type ID must be non-negative" }
    }

    /** Block-entity X inside its packet Chunk. */
    val localX: Int
        get() = packedXZ.toInt().ushr(LOCAL_COORDINATE_BITS) and LOCAL_COORDINATE_MASK

    /** Block-entity Z inside its packet Chunk. */
    val localZ: Int
        get() = packedXZ.toInt() and LOCAL_COORDINATE_MASK

    companion object {
        /** Packs the logical local coordinates into the packet representation. */
        fun fromLocalCoordinates(
            localX: Int,
            y: Int,
            localZ: Int,
            typeId: Int,
            tag: NbtCompound?,
        ): BlockEntityInfo {
            require(localX in 0..LOCAL_COORDINATE_MASK) { "Local block-entity X must be in 0..15" }
            require(y in Short.MIN_VALUE..Short.MAX_VALUE) { "Block-entity Y $y does not fit a Short" }
            require(localZ in 0..LOCAL_COORDINATE_MASK) { "Local block-entity Z must be in 0..15" }
            return BlockEntityInfo(
                packedXZ = ((localX shl LOCAL_COORDINATE_BITS) or localZ).toByte(),
                y = y.toShort(),
                typeId = typeId,
                tag = tag,
            )
        }

        private const val LOCAL_COORDINATE_BITS: Int = 4
        private const val LOCAL_COORDINATE_MASK: Int = 0x0F
    }
}

@Serializable
data class ChunkSection(
    @UnsignedShort
    val nonAirBlockCount: Int,
    @UnsignedShort
    val fluidCount: Int,
    @Paletted(PaletteKind.BLOCK_STATES)
    val blockStates: PalettedContainer,
    @Paletted(PaletteKind.BIOMES)
    val biomes: PalettedContainer,
) {
    /** Whether the packet-reported Section contains only air block states. */
    val hasOnlyAir: Boolean
        get() = nonAirBlockCount == 0

    /** Whether the packet-reported Section contains at least one non-empty fluid state. */
    val hasFluid: Boolean
        get() = fluidCount > 0

    init {
        require(nonAirBlockCount in 0..BLOCK_COUNT) {
            "A chunk section contains $nonAirBlockCount non-air blocks"
        }
        require(fluidCount in 0..BLOCK_COUNT) {
            "A chunk section contains $fluidCount fluid blocks"
        }
    }

    companion object {
        const val BLOCK_COUNT: Int = 16 * 16 * 16
        const val BIOME_COUNT: Int = 4 * 4 * 4
    }
}

/**
 * The three protocol palette representations. [Indirect.data] contains local
 * palette indices; [Direct.data] contains IDs from the synchronized global
 * registry. Packed entries never cross a Long boundary.
 */
@Serializable
sealed interface PalettedContainer {
    @Serializable
    data class Single(val valueId: Int) : PalettedContainer {
        init {
            require(valueId >= 0) { "A palette value ID must be non-negative" }
        }
    }

    @Serializable
    data class Indirect(
        val bitsPerEntry: Int,
        val palette: List<Int>,
        val data: PackedLongArray,
    ) : PalettedContainer {
        init {
            require(bitsPerEntry in 1..31) {
                "Indirect palette bits per entry must be in 1..31"
            }
            require(palette.size.toLong() <= 1L.shl(bitsPerEntry)) {
                "Palette has ${palette.size} values but only $bitsPerEntry bits per entry"
            }
            require(palette.all { it >= 0 }) {
                "Palette value IDs must be non-negative"
            }
        }
    }

    @Serializable
    data class Direct(val data: PackedLongArray) : PalettedContainer
}

/** Immutable, content-equality storage for a paletted container's packed Longs. */
@Serializable(with = PackedLongArraySerializer::class)
class PackedLongArray(values: LongArray) {
    private val storage: LongArray = values.copyOf()

    val size: Int
        get() = storage.size

    operator fun get(index: Int): Long = storage[index]

    fun toLongArray(): LongArray = storage.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PackedLongArray && storage.contentEquals(other.storage)

    override fun hashCode(): Int = storage.contentHashCode()

    override fun toString(): String = storage.contentToString()
}

internal object PackedLongArraySerializer : KSerializer<PackedLongArray> {
    private val delegate = LongArraySerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: PackedLongArray) {
        encoder.encodeSerializableValue(delegate, value.toLongArray())
    }

    override fun deserialize(decoder: Decoder): PackedLongArray =
        PackedLongArray(decoder.decodeSerializableValue(delegate))
}

@Serializable
data class ChunkData(
    val heightmaps: Map<HeightmapType, LongArray>,
    @ByteLengthPrefixed(MAX_SECTION_BYTES)
    @ChunkSectionCount
    val sections: List<ChunkSection>,
    val blockEntities: List<BlockEntityInfo>,
) {
    companion object {
        const val MAX_SECTION_BYTES: Int = 2_097_152
    }
}

internal object HeightmapTypeSerializer : KSerializer<HeightmapType> {
    override val descriptor = buildClassSerialDescriptor(
        "minecraft.HeightmapType",
    ) {
        element<Int>("id", annotations = listOf(VarInt()))
    }

    override fun serialize(encoder: Encoder, value: HeightmapType) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, 0, value.ordinal)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): HeightmapType {
        val input = decoder.beginStructure(descriptor)
        val heightmapType = HeightmapType.entries.getOrElse(
            input.decodeIntElement(descriptor, 0),
        ) {
            HeightmapType.WORLD_SURFACE_WG
        }
        input.endStructure(descriptor)
        return heightmapType
    }
}
