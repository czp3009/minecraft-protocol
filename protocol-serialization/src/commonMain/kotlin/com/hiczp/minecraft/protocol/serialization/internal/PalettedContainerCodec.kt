package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.model.type.PackedLongArray
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import com.hiczp.minecraft.protocol.model.wire.PaletteKind
import com.hiczp.minecraft.protocol.serialization.MinecraftFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException

internal object PalettedContainerCodec {
    fun write(
        writer: MinecraftWriter,
        value: PalettedContainer,
        kind: PaletteKind,
        configuration: MinecraftFormatConfiguration,
    ) {
        val registrySize = kind.registrySize(configuration)
        when (value) {
            is PalettedContainer.Single -> {
                validateRegistryId(value.valueId, registrySize, kind)
                writer.writeByte(0)
                writer.writeVarInt(value.valueId)
            }

            is PalettedContainer.Indirect -> {
                validateIndirectBits(value.bitsPerEntry, kind)
                validatePalette(value.palette, value.bitsPerEntry, registrySize, kind)
                validatePackedSize(value.data, value.bitsPerEntry, kind)

                writer.writeByte(value.bitsPerEntry)
                writer.writeVarInt(value.palette.size)
                value.palette.forEach(writer::writeVarInt)
                writePacked(writer, value.data)
            }

            is PalettedContainer.Direct -> {
                val bits = minimumBitsForDistinctValues(registrySize)
                if (bits <= kind.maximumIndirectBits) {
                    throw MinecraftSerializationException(
                        "A direct ${kind.displayName} palette requires more than ${kind.maximumIndirectBits} bits for the global registry",
                    )
                }
                validatePackedSize(value.data, bits, kind)

                writer.writeByte(bits)
                writePacked(writer, value.data)
            }
        }
    }

    fun read(
        reader: MinecraftReader,
        kind: PaletteKind,
        configuration: MinecraftFormatConfiguration,
    ): PalettedContainer {
        val registrySize = kind.registrySize(configuration)
        val wireBits = reader.readUnsignedByte()
        if (wireBits == 0) {
            val valueId = reader.readVarInt(configuration.rejectNonMinimalVarNumbers)
            validateRegistryId(valueId, registrySize, kind)
            return PalettedContainer.Single(valueId)
        }

        val indirectBits = kind.normalizedIndirectBits(wireBits)
        if (indirectBits != null) {
            val capacity = 1 shl indirectBits
            val paletteSize = reader.readVarInt(configuration.rejectNonMinimalVarNumbers)
            val maximum = minOf(capacity, configuration.maximumCollectionSize)
            if (paletteSize !in 0..maximum) {
                throw MinecraftSerializationException(
                    "Invalid ${kind.displayName} palette size $paletteSize; maximum is $maximum",
                )
            }
            val palette = List(paletteSize) {
                reader.readVarInt(configuration.rejectNonMinimalVarNumbers).also {
                    validateRegistryId(it, registrySize, kind)
                }
            }
            return PalettedContainer.Indirect(
                indirectBits,
                palette,
                readPacked(reader, indirectBits, kind, configuration),
            )
        }

        // Vanilla ignores an out-of-range direct discriminator and sizes the
        // storage from the synchronized global registry instead.
        val globalBits = minimumBitsForDistinctValues(registrySize)
        if (globalBits == 0) {
            throw MinecraftSerializationException(
                "The ${kind.displayName} global registry is too small for a direct palette",
            )
        }
        return PalettedContainer.Direct(
            readPacked(reader, globalBits, kind, configuration),
        )
    }

    private fun validateIndirectBits(bits: Int, kind: PaletteKind) {
        if (bits !in kind.minimumIndirectBits..kind.maximumIndirectBits) {
            throw MinecraftSerializationException(
                "${kind.displayName} indirect palettes require ${kind.minimumIndirectBits}..${kind.maximumIndirectBits} bits, got $bits",
            )
        }
    }

    private fun validatePalette(
        palette: List<Int>,
        bits: Int,
        registrySize: Int,
        kind: PaletteKind,
    ) {
        val capacity = 1 shl bits
        if (palette.size > capacity) {
            throw MinecraftSerializationException(
                "${kind.displayName} palette has ${palette.size} entries; capacity is $capacity",
            )
        }
        palette.forEach { validateRegistryId(it, registrySize, kind) }
    }

    private fun validateRegistryId(
        id: Int,
        registrySize: Int,
        kind: PaletteKind,
    ) {
        if (id !in 0 until registrySize) {
            throw MinecraftSerializationException(
                "Invalid ${kind.displayName} registry ID $id; size is $registrySize",
            )
        }
    }

    private fun validatePackedSize(
        data: PackedLongArray,
        bits: Int,
        kind: PaletteKind,
    ) {
        val expected = packedLongCount(kind.entryCount, bits)
        if (data.size != expected) {
            throw MinecraftSerializationException(
                "${kind.displayName} packed data has ${data.size} Longs; expected $expected for $bits bits per entry",
            )
        }
    }

    private fun readPacked(
        reader: MinecraftReader,
        bits: Int,
        kind: PaletteKind,
        configuration: MinecraftFormatConfiguration,
    ): PackedLongArray {
        val count = packedLongCount(kind.entryCount, bits)
        if (count > configuration.maximumCollectionSize ||
            count.toLong() * Long.SIZE_BYTES > configuration.maximumByteArraySize
        ) {
            throw MinecraftSerializationException(
                "${kind.displayName} packed data exceeds configured allocation limits",
            )
        }
        return PackedLongArray(LongArray(count) { reader.readLong() })
    }

    private fun writePacked(writer: MinecraftWriter, data: PackedLongArray) {
        repeat(data.size) { writer.writeLong(data[it]) }
    }

    private fun packedLongCount(entryCount: Int, bits: Int): Int {
        val valuesPerLong = Long.SIZE_BITS / bits
        return (entryCount + valuesPerLong - 1) / valuesPerLong
    }

    private fun minimumBitsForDistinctValues(count: Int): Int =
        if (count <= 1) 0 else Int.SIZE_BITS - (count - 1).countLeadingZeroBits()

    private val PaletteKind.entryCount: Int
        get() = when (this) {
            PaletteKind.BLOCK_STATES -> 16 * 16 * 16
            PaletteKind.BIOMES -> 4 * 4 * 4
        }

    private val PaletteKind.minimumIndirectBits: Int
        get() = when (this) {
            PaletteKind.BLOCK_STATES -> 4
            PaletteKind.BIOMES -> 1
        }

    private val PaletteKind.maximumIndirectBits: Int
        get() = when (this) {
            PaletteKind.BLOCK_STATES -> 8
            PaletteKind.BIOMES -> 3
        }

    private val PaletteKind.displayName: String
        get() = when (this) {
            PaletteKind.BLOCK_STATES -> "block-state"
            PaletteKind.BIOMES -> "biome"
        }

    private fun PaletteKind.normalizedIndirectBits(wireBits: Int): Int? =
        when (this) {
            PaletteKind.BLOCK_STATES -> when (wireBits) {
                in 1..4 -> 4
                in 5..8 -> wireBits
                else -> null
            }

            PaletteKind.BIOMES -> wireBits.takeIf { it in 1..3 }
        }

    private fun PaletteKind.registrySize(
        configuration: MinecraftFormatConfiguration,
    ): Int = when (this) {
        PaletteKind.BLOCK_STATES -> configuration.blockStateRegistrySize
        PaletteKind.BIOMES -> configuration.biomeRegistrySize
    }
}
