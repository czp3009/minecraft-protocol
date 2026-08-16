package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Minecraft's packed 26/12/26-bit block position.
 *
 * It has a scalar serial form so every `kotlinx.serialization` format agrees on the same logical value.
 * Human-facing tools can use [x], [y], and [z].
 */
@Serializable(with = BlockPositionSerializer::class)
data class BlockPosition(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(x in MIN_XZ..MAX_XZ) { "x is outside the 26-bit protocol range: $x" }
        require(z in MIN_XZ..MAX_XZ) { "z is outside the 26-bit protocol range: $z" }
        require(y in MIN_Y..MAX_Y) { "y is outside the 12-bit protocol range: $y" }
    }

    fun packed(): Long =
        ((x.toLong() and XZ_MASK) shl 38) or
                ((z.toLong() and XZ_MASK) shl 12) or
                (y.toLong() and Y_MASK)

    companion object {
        const val MIN_XZ: Int = -33_554_432
        const val MAX_XZ: Int = 33_554_431
        const val MIN_Y: Int = -2_048
        const val MAX_Y: Int = 2_047

        private const val XZ_MASK: Long = 0x3FFFFFF
        private const val Y_MASK: Long = 0xFFF

        fun fromPacked(value: Long): BlockPosition = BlockPosition(
            x = (value shr 38).toInt(),
            y = (value shl 52 shr 52).toInt(),
            z = (value shl 26 shr 38).toInt(),
        )
    }
}

internal object BlockPositionSerializer : KSerializer<BlockPosition> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("minecraft.BlockPosition", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: BlockPosition): Unit =
        encoder.encodeLong(value.packed())

    override fun deserialize(decoder: Decoder): BlockPosition =
        BlockPosition.fromPacked(decoder.decodeLong())
}
