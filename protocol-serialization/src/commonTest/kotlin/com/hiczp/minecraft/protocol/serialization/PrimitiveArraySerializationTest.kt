package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.wire.VarIntElements
import com.hiczp.minecraft.protocol.model.wire.VarLongElements
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class PrimitiveArraySerializationTest {
    @Test
    fun `all primitive arrays retain their protocol representation`() {
        val primitiveArrays = PrimitiveArrays(
            booleans = booleanArrayOf(false, true),
            bytes = byteArrayOf(-1, 0, 1),
            shorts = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE),
            ints = intArrayOf(Int.MIN_VALUE, 0, Int.MAX_VALUE),
            longs = longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE),
            floats = floatArrayOf(-1.25f, 0f, 3.5f),
            doubles = doubleArrayOf(-1.25, 0.0, 3.5),
            chars = charArrayOf('\u0000', 'A', '\uffff'),
            varInts = intArrayOf(-1, 0, 128),
            varLongs = longArrayOf(-1, 0, 128),
        )

        val encoded = MinecraftProtocolFormat.encodeToByteArray(primitiveArrays)
        val decoded = MinecraftProtocolFormat.decodeFromByteArray<PrimitiveArrays>(encoded)

        assertContentEquals(primitiveArrays.booleans, decoded.booleans)
        assertContentEquals(primitiveArrays.bytes, decoded.bytes)
        assertContentEquals(primitiveArrays.shorts, decoded.shorts)
        assertContentEquals(primitiveArrays.ints, decoded.ints)
        assertContentEquals(primitiveArrays.longs, decoded.longs)
        assertContentEquals(primitiveArrays.floats, decoded.floats)
        assertContentEquals(primitiveArrays.doubles, decoded.doubles)
        assertContentEquals(primitiveArrays.chars, decoded.chars)
        assertContentEquals(primitiveArrays.varInts, decoded.varInts)
        assertContentEquals(primitiveArrays.varLongs, decoded.varLongs)
    }

    @Test
    fun `boolean arrays follow the official nonzero truth rule by default`() {
        assertContentEquals(
            booleanArrayOf(true),
            MinecraftProtocolFormat.decodeFromByteArray<BooleanArray>(byteArrayOf(1, 2)),
        )

        val strict = MinecraftProtocolFormat(MinecraftProtocolFormatConfiguration(strictBooleans = true))
        assertFailsWith<MinecraftSerializationException> {
            strict.decodeFromByteArray<BooleanArray>(byteArrayOf(1, 2))
        }
    }

    @Test
    fun `fixed-width primitive arrays reject impossible payload lengths before allocation`() {
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray<IntArray>(
                "ffffffff07".hexToByteArray(),
            )
        }
    }
}

@Serializable
private data class PrimitiveArrays(
    val booleans: BooleanArray,
    val bytes: ByteArray,
    val shorts: ShortArray,
    val ints: IntArray,
    val longs: LongArray,
    val floats: FloatArray,
    val doubles: DoubleArray,
    val chars: CharArray,
    @VarIntElements val varInts: IntArray,
    @VarLongElements val varLongs: LongArray,
)
