package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.model.type.Vector3d
import com.hiczp.minecraft.protocol.serialization.MinecraftFormatConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Vanilla's LpVec3 codec. It quantizes three doubles into 15 bits each and
 * shares one integral scale between the components.
 */
internal object LowPrecisionVectorCodec {
    private const val DATA_MASK: Long = 0x7FFFL
    private const val MAX_QUANTIZED_VALUE: Double = 32_766.0
    private const val SCALE_MASK: Long = 3L
    private const val CONTINUATION_FLAG: Long = 4L
    private const val X_OFFSET: Int = 3
    private const val Y_OFFSET: Int = 18
    private const val Z_OFFSET: Int = 33
    private const val ABS_MAX_VALUE: Double = 17_179_869_183.0
    private const val ABS_MIN_VALUE: Double = 3.051944088384301E-5

    fun write(writer: MinecraftWriter, value: Vector3d) {
        val x = sanitize(value.x)
        val y = sanitize(value.y)
        val z = sanitize(value.z)
        val maximum = max(abs(x), max(abs(y), abs(z)))
        if (maximum < ABS_MIN_VALUE) {
            writer.writeByte(0)
            return
        }

        val scale = ceil(maximum).toLong()
        val hasContinuation = scale and SCALE_MASK != scale
        val markers = if (hasContinuation) {
            scale and SCALE_MASK or CONTINUATION_FLAG
        } else {
            scale
        }
        val buffer = markers or
                (pack(x / scale) shl X_OFFSET) or
                (pack(y / scale) shl Y_OFFSET) or
                (pack(z / scale) shl Z_OFFSET)

        writer.writeByte(buffer.toInt())
        writer.writeByte((buffer shr 8).toInt())
        writer.writeInt((buffer shr 16).toInt())
        if (hasContinuation) {
            writer.writeVarInt((scale shr 2).toInt())
        }
    }

    fun read(
        reader: MinecraftReader,
        configuration: MinecraftFormatConfiguration,
    ): Vector3d {
        val lowest = reader.readUnsignedByte()
        if (lowest == 0) {
            return Vector3d(0.0, 0.0, 0.0)
        }

        val middle = reader.readUnsignedByte()
        val highest = reader.readInt().toLong() and 0xFFFF_FFFFL
        val buffer = (highest shl 16) or (middle.toLong() shl 8) or lowest.toLong()
        var scale = lowest.toLong() and SCALE_MASK
        if (lowest and CONTINUATION_FLAG.toInt() != 0) {
            scale = scale or (
                    (reader.readVarInt(configuration.rejectNonMinimalVarNumbers)
                        .toLong() and 0xFFFF_FFFFL) shl 2
                    )
        }

        return Vector3d(
            x = unpack(buffer shr X_OFFSET) * scale,
            y = unpack(buffer shr Y_OFFSET) * scale,
            z = unpack(buffer shr Z_OFFSET) * scale,
        )
    }

    private fun sanitize(value: Double): Double = when {
        value.isNaN() -> 0.0
        value < -ABS_MAX_VALUE -> -ABS_MAX_VALUE
        value > ABS_MAX_VALUE -> ABS_MAX_VALUE
        else -> value
    }

    /** Java Math.round for this codec's non-negative quantization domain. */
    private fun pack(value: Double): Long =
        floor((value * 0.5 + 0.5) * MAX_QUANTIZED_VALUE + 0.5).toLong()

    private fun unpack(value: Long): Double =
        minOf(value and DATA_MASK, MAX_QUANTIZED_VALUE.toLong()) *
                2.0 / MAX_QUANTIZED_VALUE - 1.0
}
