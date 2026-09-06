package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

private const val LIGHT_LAYER_BYTE_COUNT: Int = MinecraftCoordinates.SECTION_BLOCK_COUNT / 2

internal inline fun <reified T : NbtTag> NbtCompound.optionalTag(name: String): T? {
    val nbtTag = this[name] ?: return null
    return nbtTag as? T ?: throw NbtPropertyFormatException("Field $name must be ${T::class.simpleName}")
}

internal inline fun <reified T : NbtTag> NbtCompound.requiredTag(name: String): T =
    optionalTag<T>(name) ?: throw NbtPropertyFormatException("Missing NBT field $name")

internal fun NbtCompound.int(name: String, default: Int? = null): Int =
    optionalTag<NbtInt>(name)?.value ?: default ?: throw NbtPropertyFormatException("Missing NBT Int $name")

internal fun NbtCompound.long(name: String, default: Long? = null): Long =
    optionalTag<NbtLong>(name)?.value ?: default ?: throw NbtPropertyFormatException("Missing NBT Long $name")

internal fun NbtCompound.string(name: String, default: String? = null): String =
    optionalTag<NbtString>(name)?.value ?: default ?: throw NbtPropertyFormatException("Missing NBT String $name")

internal fun NbtCompound.boolean(name: String, default: Boolean? = null): Boolean =
    optionalTag<NbtByte>(name)?.value?.let { it != 0.toByte() }
        ?: default ?: throw NbtPropertyFormatException("Missing NBT Boolean $name")

internal fun NbtTag.compound(): NbtCompound =
    this as? NbtCompound ?: throw NbtPropertyFormatException("Expected an NBT compound")

internal fun NbtTag.list(): NbtList = this as? NbtList ?: throw NbtPropertyFormatException("Expected an NBT list")

internal fun NbtTag.int(): Int =
    (this as? NbtInt)?.value ?: throw NbtPropertyFormatException("Expected an NBT Int")

internal fun NbtTag.long(): Long =
    (this as? NbtLong)?.value ?: throw NbtPropertyFormatException("Expected an NBT Long")

internal fun NbtTag.string(): String =
    (this as? NbtString)?.value ?: throw NbtPropertyFormatException("Expected an NBT String")

internal fun packNbtValues(values: IntArray, bits: Int): NbtLongArray {
    require(bits in 1..32) { "Packed value width must be in 1..32" }
    val valuesPerLong = Long.SIZE_BITS / bits
    val packed = LongArray((values.size + valuesPerLong - 1) / valuesPerLong)
    val mask = (1L shl bits) - 1
    values.forEachIndexed { index, value ->
        require(value.toLong() in 0..mask) { "Packed value $value does not fit $bits bits" }
        val cell = index / valuesPerLong
        packed[cell] = packed[cell] or (value.toLong() shl (index % valuesPerLong * bits))
    }
    return NbtLongArray(packed)
}

internal fun unpackNbtValues(nbtLongArray: NbtLongArray, bits: Int, size: Int): IntArray {
    require(bits in 1..32) { "Packed value width must be in 1..32" }
    val valuesPerLong = Long.SIZE_BITS / bits
    val expected = (size + valuesPerLong - 1) / valuesPerLong
    require(nbtLongArray.size == expected) { "Packed data has ${nbtLongArray.size} longs, expected $expected" }
    val mask = (1L shl bits) - 1
    return IntArray(size) { index ->
        (nbtLongArray[index / valuesPerLong] ushr (index % valuesPerLong * bits) and mask).toInt()
    }
}

internal fun LightLayer.toNbt(): NbtByteArray = NbtByteArray(ByteArray(LIGHT_LAYER_BYTE_COUNT) { index ->
    (get(index * 2) or (get(index * 2 + 1) shl 4)).toByte()
})

internal fun NbtByteArray.toLightLayer(): LightLayer {
    require(size == LIGHT_LAYER_BYTE_COUNT) { "A light layer must contain $LIGHT_LAYER_BYTE_COUNT packed bytes" }
    return LightLayer(List(MinecraftCoordinates.SECTION_BLOCK_COUNT) { index ->
        get(index / 2).toInt() ushr (index % 2 * 4) and 15
    })
}

internal fun BlockPosition.toNbt(): NbtIntArray = NbtIntArray(intArrayOf(x, y, z))

internal fun NbtIntArray.toBlockPosition(): BlockPosition {
    require(size == 3) { "A Block position must contain three integers" }
    return BlockPosition(get(0), get(1), get(2))
}

internal fun NbtCompound.blockPosition(): BlockPosition = BlockPosition(int("x"), int("y"), int("z"))
