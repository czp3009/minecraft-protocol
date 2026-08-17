package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.Vector3d
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.*

class MinecraftProtocolFormatTest {
    @Test
    fun `stream APIs are canonical and honor the caller payload boundary`() {
        val value = PrimitiveValue(
            byte = -1,
            short = 2,
            int = 3,
            long = 4,
            unsignedByte = 5,
            unsignedShort = 6,
        )
        val sink = Buffer()
        MinecraftProtocolFormat.encodeToSink(
            PrimitiveValue.serializer(),
            value,
            sink,
        )
        val streamed = sink.readByteArray()

        assertContentEquals(
            MinecraftProtocolFormat.encodeToByteArray(
                PrimitiveValue.serializer(),
                value,
            ),
            streamed,
        )

        val source = Buffer()
        source.write(streamed)
        source.write(byteArrayOf(99, 100))
        assertEquals(
            value,
            MinecraftProtocolFormat.decodeFromSource(
                PrimitiveValue.serializer(),
                source,
                streamed.size,
            ),
        )
        assertContentEquals(byteArrayOf(99, 100), source.readByteArray())
    }

    @Test
    fun `companion is the default format and factory creates configured instances`() {
        assertSame(MinecraftProtocolFormat, MinecraftProtocolFormat.Default)
        assertEquals(MinecraftProtocolFormatConfiguration(), MinecraftProtocolFormat.configuration)

        val configuration = MinecraftProtocolFormatConfiguration(
            strictBooleans = false,
            registries = testRegistryContext(chunkSectionCount = 24),
        )
        val configured = MinecraftProtocolFormat(configuration)

        assertTrue(configured !== MinecraftProtocolFormat.Default)
        assertEquals(configuration, configured.configuration)
    }

    @Test
    fun `var numbers match protocol golden vectors`() {
        val varInts = listOf(
            0 to "00",
            1 to "01",
            127 to "7f",
            128 to "8001",
            255 to "ff01",
            2_147_483_647 to "ffffffff07",
            -1 to "ffffffff0f",
            -2_147_483_648 to "8080808008",
        )
        for ((value, hex) in varInts) {
            assertContentEquals(
                hex.hexToByteArray(),
                MinecraftProtocolFormat.encodeToByteArray(VarIntValue.serializer(), VarIntValue(value)),
            )
            assertEquals(
                VarIntValue(value),
                MinecraftProtocolFormat.decodeFromByteArray(VarIntValue.serializer(), hex.hexToByteArray()),
            )
        }

        val varLongs = listOf(
            0L to "00",
            128L to "8001",
            Long.MAX_VALUE to "ffffffffffffffff7f",
            -1L to "ffffffffffffffffff01",
        )
        for ((value, hex) in varLongs) {
            assertContentEquals(
                hex.hexToByteArray(),
                MinecraftProtocolFormat.encodeToByteArray(VarLongValue.serializer(), VarLongValue(value)),
            )
            assertEquals(
                VarLongValue(value),
                MinecraftProtocolFormat.decodeFromByteArray(VarLongValue.serializer(), hex.hexToByteArray()),
            )
        }
    }

    @Test
    fun `var numbers round trip deterministic boundary-heavy samples`() {
        val random = Random(0x2602_0776)
        val intValues = buildList {
            addAll(
                listOf(
                    Int.MIN_VALUE,
                    -16_384,
                    -129,
                    -128,
                    -1,
                    0,
                    1,
                    127,
                    128,
                    16_383,
                    16_384,
                    Int.MAX_VALUE,
                ),
            )
            repeat(1_000) { add(random.nextInt()) }
        }
        intValues.forEach { value ->
            val encoded = MinecraftProtocolFormat.encodeToByteArray(
                VarIntValue.serializer(),
                VarIntValue(value),
            )
            assertEquals(
                VarIntValue(value),
                MinecraftProtocolFormat.decodeFromByteArray(
                    VarIntValue.serializer(),
                    encoded,
                ),
            )
        }

        val longValues = buildList {
            addAll(
                listOf(
                    Long.MIN_VALUE,
                    Int.MIN_VALUE.toLong() - 1,
                    -1L,
                    0L,
                    1L,
                    Int.MAX_VALUE.toLong() + 1,
                    Long.MAX_VALUE,
                ),
            )
            repeat(1_000) { add(random.nextLong()) }
        }
        longValues.forEach { value ->
            val encoded = MinecraftProtocolFormat.encodeToByteArray(
                VarLongValue.serializer(),
                VarLongValue(value),
            )
            assertEquals(
                VarLongValue(value),
                MinecraftProtocolFormat.decodeFromByteArray(
                    VarLongValue.serializer(),
                    encoded,
                ),
            )
        }
    }

    @Test
    fun `fixed primitives are big endian and unsigned hints are honored`() {
        val value = PrimitiveValue(
            byte = 0xFE.toByte(),
            short = 0x1234,
            int = 0x12345678,
            long = 0x0102030405060708,
            unsignedByte = 255,
            unsignedShort = 65_535,
        )
        val expected = "fe1234123456780102030405060708ffffff".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(PrimitiveValue.serializer(), value),
        )
        assertEquals(
            expected = value,
            actual = MinecraftProtocolFormat.decodeFromByteArray(PrimitiveValue.serializer(), expected),
        )
    }

    @Test
    fun `strings enforce UTF-16 and UTF-8 limits`() {
        val value = LimitedString("éé")
        assertContentEquals(
            "04c3a9c3a9".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(LimitedString.serializer(), value),
        )
        assertEquals(
            value,
            MinecraftProtocolFormat.decodeFromByteArray(
                LimitedString.serializer(),
                "04c3a9c3a9".hexToByteArray(),
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.encodeToByteArray(
                LimitedString.serializer(),
                LimitedString("abc"),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                LimitedString.serializer(),
                "02c328".hexToByteArray(),
            )
        }
    }

    @Test
    fun `collections maps nullable and byte shapes round trip`() {
        val collection = CollectionValue(
            values = listOf(1, 2, 300),
            map = linkedMapOf("a" to 1, "b" to 2),
            optional = 7,
        )
        val encoded = MinecraftProtocolFormat.encodeToByteArray(CollectionValue.serializer(), collection)
        assertEquals(
            collection,
            MinecraftProtocolFormat.decodeFromByteArray(CollectionValue.serializer(), encoded),
        )

        assertContentEquals(
            "030102ac02020161000000010162000000020100000007".hexToByteArray(),
            encoded,
        )

        val bytes = ByteShapes(
            prefixed = ByteString(byteArrayOf(1, 2, 3)),
            fixed = ByteString(byteArrayOf(4, 5)),
            remaining = ByteString(byteArrayOf(6, 7, 8)),
        )
        val byteEncoding = "030102030405060708".hexToByteArray()
        assertContentEquals(
            byteEncoding,
            MinecraftProtocolFormat.encodeToByteArray(ByteShapes.serializer(), bytes),
        )
        assertEquals(
            bytes,
            MinecraftProtocolFormat.decodeFromByteArray(ByteShapes.serializer(), byteEncoding),
        )
    }

    @Test
    fun `byte arrays and general collections have no shared policy budget`() {
        val bytes = BytesValue(ByteString(ByteArray(257) { it.toByte() }))
        val values = IntListValue(List(257) { it })

        assertEquals(
            bytes,
            MinecraftProtocolFormat.decodeFromByteArray(
                BytesValue.serializer(),
                MinecraftProtocolFormat.encodeToByteArray(BytesValue.serializer(), bytes),
            ),
        )
        assertEquals(
            values,
            MinecraftProtocolFormat.decodeFromByteArray(
                IntListValue.serializer(),
                MinecraftProtocolFormat.encodeToByteArray(IntListValue.serializer(), values),
            ),
        )
    }

    @Test
    fun `registry context and fixed byte widths reject every invalid boundary`() {
        assertFailsWith<IllegalArgumentException> {
            ProtocolRegistryContext(
                registries = emptyList(),
                blockStates = emptyList(),
                chunkSectionCount = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProtocolRegistryContext(
                registries = emptyList(),
                blockStates = emptyList(),
                registrySizeOverrides = mapOf(
                    ProtocolRegistryContext.BIOME_REGISTRY to 0,
                ),
            )
        }

        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.encodeToByteArray(
                FixedBytes.serializer(),
                FixedBytes(ByteString(byteArrayOf(1))),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                FixedBytes.serializer(),
                byteArrayOf(1),
            )
        }
    }

    @Test
    fun `network NBT has exact unnamed encoding`() {
        val value = NbtValue(
            NbtCompound(
                linkedMapOf(
                    "name" to NbtString("value"),
                    "numbers" to NbtList(listOf(NbtInt(1), NbtInt(2))),
                ),
            ),
        )
        val encoded = MinecraftProtocolFormat.encodeToByteArray(NbtValue.serializer(), value)
        assertEquals(
            value,
            MinecraftProtocolFormat.decodeFromByteArray(NbtValue.serializer(), encoded),
        )
        assertEquals(10, encoded.first().toInt())
        assertEquals(0, encoded.last().toInt())
    }

    @Test
    fun `network NBT strings use Java modified UTF`() {
        val value = NbtValue(NbtString("\u0000Aé😀"))
        val expected = "08000bc08041c3a9eda0bdedb880".hexToByteArray()

        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(NbtValue.serializer(), value),
        )
        assertEquals(
            expected = value,
            actual = MinecraftProtocolFormat.decodeFromByteArray(
                NbtValue.serializer(),
                expected,
            ),
        )
    }

    @Test
    fun `network NBT failures retain their serialization exception type`() {
        val failure = assertFailsWith<NbtDecodingException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                NbtValue.serializer(),
                byteArrayOf(99),
            )
        }
        assertNull(failure.cause)
    }

    @Test
    fun `length-prefixed nullable NBT uses TAG End rather than boolean`() {
        val absent = LengthPrefixedOptionalNbt(null)
        assertContentEquals(
            "0100".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                LengthPrefixedOptionalNbt.serializer(),
                absent,
            ),
        )
        assertEquals(
            absent,
            MinecraftProtocolFormat.decodeFromByteArray(
                LengthPrefixedOptionalNbt.serializer(),
                "0100".hexToByteArray(),
            ),
        )

        val present = LengthPrefixedOptionalNbt(NbtString("ok"))
        val expected = "050800026f6b".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(
                LengthPrefixedOptionalNbt.serializer(),
                present,
            ),
        )
        assertEquals(
            expected = present,
            actual = MinecraftProtocolFormat.decodeFromByteArray(
                LengthPrefixedOptionalNbt.serializer(),
                expected,
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                LengthPrefixedOptionalNbt.serializer(),
                "060800026f6b00".hexToByteArray(),
            )
        }
    }

    @Test
    fun `low precision vectors match vanilla golden bytes`() {
        val zero = LowPrecisionVectorValue(Vector3d(0.0, 0.0, 0.0))
        assertContentEquals(
            "00".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(LowPrecisionVectorValue.serializer(), zero),
        )

        val unitX = LowPrecisionVectorValue(Vector3d(1.0, 0.0, 0.0))
        assertContentEquals(
            "f1ff7ffeffff".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(LowPrecisionVectorValue.serializer(), unitX),
        )
        val unitDecoded = MinecraftProtocolFormat.decodeFromByteArray(
            LowPrecisionVectorValue.serializer(),
            "f1ff7ffeffff".hexToByteArray(),
        )
        assertEquals(1.0, unitDecoded.value.x, 0.000_001)
        assertEquals(0.0, unitDecoded.value.y, 0.000_001)
        assertEquals(0.0, unitDecoded.value.z, 0.000_001)

        val continuation = LowPrecisionVectorValue(Vector3d(4.0, 0.0, 0.0))
        assertContentEquals(
            "f4ff7ffeffff01".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                LowPrecisionVectorValue.serializer(),
                continuation,
            ),
        )
        assertEquals(
            continuation,
            MinecraftProtocolFormat.decodeFromByteArray(
                LowPrecisionVectorValue.serializer(),
                "f4ff7ffeffff01".hexToByteArray(),
            ),
        )

    }

    @Test
    fun `low precision vectors sanitize and validate their compact representation`() {
        val clamped = LowPrecisionVectorValue(
            Vector3d(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN),
        )
        assertContentEquals(
            "f7ff7ffe0003ffffffff0f".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                LowPrecisionVectorValue.serializer(),
                clamped,
            ),
        )
        val decoded = MinecraftProtocolFormat.decodeFromByteArray(
            LowPrecisionVectorValue.serializer(),
            "f7ff7ffe0003ffffffff0f".hexToByteArray(),
        )
        assertEquals(17_179_869_183.0, decoded.value.x, 0.000_001)
        assertEquals(-17_179_869_183.0, decoded.value.y, 0.000_001)
        assertEquals(0.0, decoded.value.z, 0.000_001)

        val belowThreshold = LowPrecisionVectorValue(
            Vector3d(3.0E-5, -3.0E-5, 0.0),
        )
        assertContentEquals(
            "00".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                LowPrecisionVectorValue.serializer(),
                belowThreshold,
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                LowPrecisionVectorValue.serializer(),
                "04ffffffffff".hexToByteArray(),
            )
        }
    }

    @Test
    fun `malformed lengths varints booleans and trailing bytes are rejected`() {
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                VarIntValue.serializer(),
                "808080808000".hexToByteArray(),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                NullableValue.serializer(),
                "02".hexToByteArray(),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray(
                VarIntValue.serializer(),
                "0000".hexToByteArray(),
            )
        }
        val strict = MinecraftProtocolFormat(
            MinecraftProtocolFormatConfiguration(rejectNonMinimalVarNumbers = true),
        )
        assertFailsWith<MinecraftSerializationException> {
            strict.decodeFromByteArray(
                VarIntValue.serializer(),
                "8000".hexToByteArray(),
            )
        }

        val permissive = MinecraftProtocolFormat(
            MinecraftProtocolFormatConfiguration(strictBooleans = false),
        )
        assertEquals(
            NullableValue(7),
            permissive.decodeFromByteArray(
                NullableValue.serializer(),
                "0200000007".hexToByteArray(),
            ),
        )
    }
}

@Serializable
private data class VarIntValue(@VarInt val value: Int)

@Serializable
private data class VarLongValue(@VarLong val value: Long)

@Serializable
private data class PrimitiveValue(
    val byte: Byte,
    val short: Short,
    val int: Int,
    val long: Long,
    @UnsignedByte val unsignedByte: Int,
    @UnsignedShort val unsignedShort: Int,
)

@Serializable
private data class LimitedString(@MaxLength(2) val value: String)

@Serializable
private data class CollectionValue(
    @VarIntElements
    val values: List<Int>,
    val map: Map<String, Int>,
    val optional: Int?,
)

@Serializable
private data class ByteShapes(
    val prefixed: ByteString,
    @FixedLength(2) val fixed: ByteString,
    @RemainingBytes val remaining: ByteString,
)

@Serializable
private data class BytesValue(val value: ByteString)

@Serializable
private data class IntListValue(val values: List<Int>)

@Serializable
private data class FixedBytes(
    @FixedLength(2)
    val value: ByteString,
)

@Serializable
private data class NbtValue(val value: NbtTag)

@Serializable
private data class LengthPrefixedOptionalNbt(
    @ByteLengthPrefixed(6)
    @NbtEndOptional
    val value: NbtTag?,
)

@Serializable
private data class LowPrecisionVectorValue(
    @LowPrecisionVector
    val value: Vector3d,
)

@Serializable
private data class NullableValue(val value: Int?)
