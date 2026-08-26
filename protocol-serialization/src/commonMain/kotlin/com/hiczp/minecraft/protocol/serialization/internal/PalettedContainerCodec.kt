package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.model.type.PackedLongArray
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import com.hiczp.minecraft.protocol.model.wire.PaletteKind
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException

internal object PalettedContainerCodec {
    fun write(
        minecraftWriter: MinecraftWriter,
        palettedContainer: PalettedContainer,
        paletteKind: PaletteKind,
        minecraftProtocolFormatConfiguration: MinecraftProtocolFormatConfiguration,
    ) {
        val registrySize = paletteKind.registrySize(minecraftProtocolFormatConfiguration)
        when (palettedContainer) {
            is PalettedContainer.Single -> {
                validateRegistryId(palettedContainer.valueId, registrySize, paletteKind)
                minecraftWriter.writeByte(0)
                minecraftWriter.writeVarInt(palettedContainer.valueId)
            }

            is PalettedContainer.Indirect -> {
                validateIndirectBits(palettedContainer.bitsPerEntry, paletteKind)
                validatePalette(palettedContainer.palette, palettedContainer.bitsPerEntry, registrySize, paletteKind)
                validatePackedSize(palettedContainer.data, palettedContainer.bitsPerEntry, paletteKind)

                minecraftWriter.writeByte(palettedContainer.bitsPerEntry)
                minecraftWriter.writeVarInt(palettedContainer.palette.size)
                palettedContainer.palette.forEach(minecraftWriter::writeVarInt)
                writePacked(minecraftWriter, palettedContainer.data)
            }

            is PalettedContainer.Direct -> {
                val bits = minimumBitsForDistinctValues(registrySize)
                if (bits <= paletteKind.maximumIndirectBits) {
                    val maximumBits = paletteKind.maximumIndirectBits
                    val displayName = paletteKind.displayName
                    throw MinecraftSerializationException(
                        "A direct $displayName palette requires more than $maximumBits bits for the global registry",
                    )
                }
                validatePackedSize(palettedContainer.data, bits, paletteKind)

                minecraftWriter.writeByte(bits)
                writePacked(minecraftWriter, palettedContainer.data)
            }
        }
    }

    fun read(
        minecraftReader: MinecraftReader,
        paletteKind: PaletteKind,
        minecraftProtocolFormatConfiguration: MinecraftProtocolFormatConfiguration,
    ): PalettedContainer {
        val registrySize = paletteKind.registrySize(minecraftProtocolFormatConfiguration)
        val wireBits = minecraftReader.readUnsignedByte()
        if (wireBits == 0) {
            val valueId = minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
            validateRegistryId(valueId, registrySize, paletteKind)
            return PalettedContainer.Single(valueId)
        }

        val indirectBits = paletteKind.normalizedIndirectBits(wireBits)
        if (indirectBits != null) {
            val capacity = 1 shl indirectBits
            val paletteSize =
                minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
            if (paletteSize !in 0..capacity) {
                throw MinecraftSerializationException(
                    "Invalid ${paletteKind.displayName} palette size $paletteSize; maximum is $capacity",
                )
            }
            val palette = List(paletteSize) {
                minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers).also {
                    validateRegistryId(it, registrySize, paletteKind)
                }
            }
            return PalettedContainer.Indirect(
                indirectBits,
                palette,
                readPacked(minecraftReader, indirectBits, paletteKind, minecraftProtocolFormatConfiguration),
            )
        }

        // Vanilla ignores an out-of-range direct discriminator and sizes the
        // storage from the synchronized global registry instead.
        val globalBits = minimumBitsForDistinctValues(registrySize)
        if (globalBits == 0) {
            throw MinecraftSerializationException(
                "The ${paletteKind.displayName} global registry is too small for a direct palette",
            )
        }
        return PalettedContainer.Direct(
            readPacked(minecraftReader, globalBits, paletteKind, minecraftProtocolFormatConfiguration),
        )
    }

    private fun validateIndirectBits(bits: Int, paletteKind: PaletteKind) {
        if (bits !in paletteKind.minimumIndirectBits..paletteKind.maximumIndirectBits) {
            val allowedBits = "${paletteKind.minimumIndirectBits}..${paletteKind.maximumIndirectBits}"
            throw MinecraftSerializationException(
                "${paletteKind.displayName} indirect palettes require $allowedBits bits, got $bits",
            )
        }
    }

    private fun validatePalette(
        palette: List<Int>,
        bits: Int,
        registrySize: Int,
        paletteKind: PaletteKind,
    ) {
        val capacity = 1 shl bits
        if (palette.size > capacity) {
            throw MinecraftSerializationException(
                "${paletteKind.displayName} palette has ${palette.size} entries; capacity is $capacity",
            )
        }
        palette.forEach { validateRegistryId(it, registrySize, paletteKind) }
    }

    private fun validateRegistryId(
        id: Int,
        registrySize: Int,
        paletteKind: PaletteKind,
    ) {
        if (id !in 0 until registrySize) {
            throw MinecraftSerializationException(
                "Invalid ${paletteKind.displayName} registry ID $id; size is $registrySize",
            )
        }
    }

    private fun validatePackedSize(
        packedLongArray: PackedLongArray,
        bits: Int,
        paletteKind: PaletteKind,
    ) {
        val expected = packedLongCount(paletteKind.entryCount, bits)
        if (packedLongArray.size != expected) {
            throw MinecraftSerializationException(
                "${paletteKind.displayName} packed data has ${packedLongArray.size} Longs; expected $expected for $bits bits per entry",
            )
        }
    }

    private fun readPacked(
        minecraftReader: MinecraftReader,
        bits: Int,
        paletteKind: PaletteKind,
        minecraftProtocolFormatConfiguration: MinecraftProtocolFormatConfiguration,
    ): PackedLongArray {
        val count = packedLongCount(paletteKind.entryCount, bits)
        return PackedLongArray(LongArray(count) { minecraftReader.readLong() })
    }

    private fun writePacked(minecraftWriter: MinecraftWriter, packedLongArray: PackedLongArray) {
        repeat(packedLongArray.size) { minecraftWriter.writeLong(packedLongArray[it]) }
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
        minecraftProtocolFormatConfiguration: MinecraftProtocolFormatConfiguration,
    ): Int = when (this) {
        PaletteKind.BLOCK_STATES -> minecraftProtocolFormatConfiguration.requireBlockStateRegistrySize()
        PaletteKind.BIOMES -> minecraftProtocolFormatConfiguration.requireBiomeRegistrySize()
    }
}
