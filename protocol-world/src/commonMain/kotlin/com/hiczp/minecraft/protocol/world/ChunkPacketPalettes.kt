package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.type.PackedLongArray
import com.hiczp.minecraft.world.format.PalettedContainer
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as PacketPalettedContainer

internal fun <T : Any> encodePacketPalette(
    palettedContainer: PalettedContainer<T>,
    registrySize: Int,
    minimumIndirectBits: Int,
    maximumIndirectBits: Int,
    rawId: (T) -> Int,
): PacketPalettedContainer {
    val palette = palettedContainer.compactSnapshot()
    val registryIds = palette.values.map(rawId)
    if (registryIds.size == 1) return PacketPalettedContainer.Single(registryIds.single())
    val logicalBits = packetValueBits(registryIds.size)
    return if (logicalBits <= maximumIndirectBits) {
        val bits = maxOf(minimumIndirectBits, logicalBits)
        PacketPalettedContainer.Indirect(
            bits,
            registryIds,
            packPacketValues(bits, palette.entryCount) { palette.ids[it] })
    } else {
        require(registrySize > 0) { "A direct palette requires a non-empty registry" }
        PacketPalettedContainer.Direct(
            packPacketValues(packetValueBits(registrySize), palette.entryCount) { registryIds[palette.ids[it]] },
        )
    }
}

internal fun <T : Any> decodePacketPalette(
    packetPalettedContainer: PacketPalettedContainer,
    entryCount: Int,
    registrySize: Int,
    value: (Int) -> T,
): PalettedContainer<T> = when (packetPalettedContainer) {
    is PacketPalettedContainer.Single -> PalettedContainer(entryCount, value(packetPalettedContainer.valueId))
    is PacketPalettedContainer.Indirect -> PalettedContainer.fromPalette(
        packetPalettedContainer.palette.map(value),
        unpackPacketValues(packetPalettedContainer.data, packetPalettedContainer.bitsPerEntry, entryCount),
    )

    is PacketPalettedContainer.Direct -> {
        val registryIds = unpackPacketValues(packetPalettedContainer.data, packetValueBits(registrySize), entryCount)
        val palette = mutableListOf<T>()
        val indices = mutableMapOf<T, Int>()
        val ids = IntArray(entryCount) { index ->
            val resolved = value(registryIds[index])
            indices.getOrPut(resolved) { palette.add(resolved); palette.lastIndex }
        }
        PalettedContainer.fromPalette(palette, ids)
    }
}

internal fun packetValueBits(size: Int): Int = if (size <= 1) 0 else Int.SIZE_BITS - (size - 1).countLeadingZeroBits()

internal fun packPacketValues(bits: Int, count: Int, value: (Int) -> Int): PackedLongArray {
    require(bits in 1..31) { "Packed value width must be in 1..31" }
    val perLong = Long.SIZE_BITS / bits
    val packed = LongArray((count + perLong - 1) / perLong)
    repeat(count) { index ->
        val item = value(index)
        require(item >= 0 && item.toLong() < (1L shl bits)) { "Value $item does not fit $bits bits" }
        packed[index / perLong] = packed[index / perLong] or (item.toLong() shl (index % perLong * bits))
    }
    return PackedLongArray(packed)
}

internal fun unpackPacketValues(packed: PackedLongArray, bits: Int, count: Int): IntArray {
    require(bits in 1..31) { "Packed value width must be in 1..31" }
    val perLong = Long.SIZE_BITS / bits
    require(packed.size == (count + perLong - 1) / perLong) { "Packed array does not match its value count and width" }
    val mask = (1L shl bits) - 1
    return IntArray(count) { index -> (packed[index / perLong] ushr (index % perLong * bits) and mask).toInt() }
}
