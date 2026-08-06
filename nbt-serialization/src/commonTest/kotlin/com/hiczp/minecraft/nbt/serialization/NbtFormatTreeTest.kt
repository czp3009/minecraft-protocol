package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.jvm.JvmInline
import kotlin.test.*

class NbtFormatTreeTest {
    @Test
    fun `maps every primitive array class enum and inline value`() {
        val value = TreeSample(
            boolean = true,
            byte = -1,
            short = -2,
            int = -3,
            long = -4,
            float = 1.25f,
            double = -2.5,
            text = "hello",
            bytes = byteArrayOf(1, 2),
            ints = intArrayOf(3, 4),
            longs = longArrayOf(5, 6),
            shorts = shortArrayOf(7, 8),
            mode = TreeMode.SECOND,
            inline = InlineInt(9),
            nested = NestedValue("value"),
        )

        val tag = NbtFormat.encodeToNbtTag(TreeSample.serializer(), value)
        val compound = tag as NbtCompound
        assertEquals(NbtByte(1), compound.value["boolean"])
        assertEquals(NbtByteArray(byteArrayOf(1, 2)), compound.value["bytes"])
        assertEquals(NbtIntArray(intArrayOf(3, 4)), compound.value["ints"])
        assertEquals(NbtLongArray(longArrayOf(5, 6)), compound.value["longs"])
        assertEquals(
            NbtList(listOf(NbtShort(7), NbtShort(8))),
            compound.value["shorts"],
        )
        assertEquals(NbtString("second"), compound.value["mode"])
        assertEquals(NbtInt(9), compound.value["inline"])
        assertEquals(
            NbtCompound(mapOf("renamed" to NbtString("value"))),
            compound.value["nested"],
        )

        val decoded = NbtFormat.decodeFromNbtTag(TreeSample.serializer(), tag)
        assertEquals(
            value,
            decoded.copy(
                bytes = value.bytes,
                ints = value.ints,
                longs = value.longs,
                shorts = value.shorts,
            ),
        )
        assertContentEquals(value.bytes, decoded.bytes)
        assertContentEquals(value.ints, decoded.ints)
        assertContentEquals(value.longs, decoded.longs)
        assertContentEquals(value.shorts, decoded.shorts)
    }

    @Test
    fun `classes lists and string maps round trip`() {
        val value = CollectionSample(
            values = listOf(1, 2, 3),
            mapping = linkedMapOf("first" to 1L, "second" to 2L),
            raw = NbtList(listOf(NbtInt(1), NbtString("two"))),
            concrete = NbtCompound(mapOf("value" to NbtInt(3))),
        )
        val tag = NbtFormat.encodeToNbtTag(CollectionSample.serializer(), value)

        assertEquals(value, NbtFormat.decodeFromNbtTag(CollectionSample.serializer(), tag))
        assertEquals(
            value.raw,
            NbtFormat.encodeToNbtTag(NbtTag.serializer(), value.raw),
        )
        assertEquals(
            value.raw,
            NbtFormat.decodeFromNbtTag(NbtTag.serializer(), value.raw),
        )
    }

    @Test
    fun `objects use empty compounds and nested failures report their path`() {
        assertEquals(
            NbtCompound(emptyMap()),
            NbtFormat.encodeToNbtTag(EmptyObject.serializer(), EmptyObject),
        )
        assertEquals(
            EmptyObject,
            NbtFormat.decodeFromNbtTag(
                EmptyObject.serializer(),
                NbtCompound(emptyMap()),
            ),
        )

        val failure = assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag(
                NestedFailure.serializer(),
                NbtCompound(
                    mapOf(
                        "values" to NbtList(listOf(NbtString("wrong"))),
                    ),
                ),
            )
        }
        assertTrue(failure.message.orEmpty().contains("$.values[0]"))
    }

    @Test
    fun `other primitive arrays use lists while char arrays are rejected`() {
        val value = PrimitiveArraySample(
            booleans = booleanArrayOf(true, false),
            shorts = shortArrayOf(1, -2),
            floats = floatArrayOf(1.25f, -2.5f),
            doubles = doubleArrayOf(3.5, -4.75),
        )
        val tag = NbtFormat.encodeToNbtTag(PrimitiveArraySample.serializer(), value)
        val compound = tag as NbtCompound

        assertEquals(
            NbtList(listOf(NbtByte(1), NbtByte(0))),
            compound.value["booleans"],
        )
        assertEquals(
            NbtList(listOf(NbtShort(1), NbtShort(-2))),
            compound.value["shorts"],
        )
        assertEquals(
            NbtList(listOf(NbtFloat(1.25f), NbtFloat(-2.5f))),
            compound.value["floats"],
        )
        assertEquals(
            NbtList(listOf(NbtDouble(3.5), NbtDouble(-4.75))),
            compound.value["doubles"],
        )

        val decoded = NbtFormat.decodeFromNbtTag(
            PrimitiveArraySample.serializer(),
            tag,
        )
        assertContentEquals(value.booleans, decoded.booleans)
        assertContentEquals(value.shorts, decoded.shorts)
        assertContentEquals(value.floats, decoded.floats)
        assertContentEquals(value.doubles, decoded.doubles)

        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(
                CharArraySample.serializer(),
                CharArraySample(charArrayOf('x')),
            )
        }
    }

    @Test
    fun `defaults unknown keys and nullable properties follow configuration`() {
        val omitted = NbtFormat.encodeToNbtTag(
            DefaultsSample.serializer(),
            DefaultsSample(),
        )
        assertEquals(NbtCompound(emptyMap()), omitted)
        assertEquals(
            DefaultsSample(),
            NbtFormat.decodeFromNbtTag(DefaultsSample.serializer(), omitted),
        )
        assertEquals(
            RequiredNullable(null),
            NbtFormat.decodeFromNbtTag(
                RequiredNullable.serializer(),
                NbtCompound(emptyMap()),
            ),
        )
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag(
                NestedValue.serializer(),
                NbtCompound(emptyMap()),
            )
        }

        val withDefaults = NbtFormat(
            NbtFormatConfiguration(encodeDefaults = true),
        ).encodeToNbtTag(DefaultsSample.serializer(), DefaultsSample())
        assertEquals(
            NbtCompound(mapOf("number" to NbtInt(7))),
            withDefaults,
        )

        val unknown = NbtCompound(
            mapOf("number" to NbtInt(8), "extra" to NbtString("ignored")),
        )
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag(DefaultsSample.serializer(), unknown)
        }
        assertEquals(
            DefaultsSample(number = 8),
            NbtFormat(
                NbtFormatConfiguration(ignoreUnknownKeys = true),
            ).decodeFromNbtTag(DefaultsSample.serializer(), unknown),
        )
    }

    @Test
    fun `null roots list entries and map values are rejected`() {
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(
                String.serializer().nullable,
                null,
            )
        }
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(
                ListSerializer(
                    String.serializer().nullable,
                ),
                listOf("value", null),
            )
        }
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(
                MapSerializer(
                    String.serializer(),
                    Int.serializer().nullable,
                ),
                mapOf("value" to null),
            )
        }
    }

    @Test
    fun `map keys must serialize as strings`() {
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(
                MapSerializer(
                    Int.serializer(),
                    String.serializer(),
                ),
                mapOf(1 to "one"),
            )
        }
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(
                MapSerializer(
                    TreeMode.serializer(),
                    Int.serializer(),
                ),
                mapOf(TreeMode.FIRST to 1),
            )
        }
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag(
                MapSerializer(
                    TreeMode.serializer(),
                    Int.serializer(),
                ),
                NbtCompound(mapOf("FIRST" to NbtInt(1))),
            )
        }
    }

    @Test
    fun `strict booleans reject noncanonical bytes`() {
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag(Boolean.serializer(), NbtByte(2))
        }
        assertEquals(
            true,
            NbtFormat(
                NbtFormatConfiguration(strictBooleans = false),
            ).decodeFromNbtTag(Boolean.serializer(), NbtByte(2)),
        )
    }

    @Test
    fun `tree encoding checks specialized array limits before building tags`() {
        val limited = NbtFormat(
            NbtFormatConfiguration(
                maximumCollectionSize = 1,
                maximumByteArraySize = 1,
            ),
        )

        assertFailsWith<NbtLimitException> {
            limited.encodeToNbtTag(
                ByteArraySerializer(),
                byteArrayOf(1, 2),
            )
        }
        assertFailsWith<NbtLimitException> {
            limited.encodeToNbtTag(
                IntArraySerializer(),
                intArrayOf(1, 2),
            )
        }
        assertFailsWith<NbtLimitException> {
            limited.encodeToNbtTag(
                LongArraySerializer(),
                longArrayOf(1, 2),
            )
        }
    }

    @Test
    fun `explicit serializers override specialized runtime array mappings`() {
        val value = byteArrayOf(0, 1, -1)
        val tag = NbtFormat.encodeToNbtTag(ByteArrayAsStringSerializer, value)

        assertEquals(NbtString("0001ff"), tag)
        assertContentEquals(
            value,
            NbtFormat.decodeFromNbtTag(ByteArrayAsStringSerializer, tag),
        )
    }

    @Test
    fun `raw tag subtype and enum decoding reject incompatible values`() {
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag(
                NbtCompound.serializer(),
                NbtInt(1),
            )
        }
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag(
                TreeMode.serializer(),
                NbtString("missing"),
            )
        }
    }

    @Test
    fun `char and polymorphism are explicitly unsupported`() {
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(Char.serializer(), 'x')
        }
        val failure = assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(
                PolymorphicHolder.serializer(),
                PolymorphicHolder(PolymorphicValue.Text("value")),
            )
        }
        assertTrue(failure.message.orEmpty().contains("Polymorphic"))
    }

    @Test
    fun `contextual serializers use the configured module`() {
        val format = NbtFormat(
            NbtFormatConfiguration(
                serializersModule = SerializersModule {
                    contextual(ContextValue::class, ContextValueSerializer)
                },
            ),
        )
        val value = ContextHolder(ContextValue("context"))
        val tag = format.encodeToNbtTag(ContextHolder.serializer(), value)

        assertEquals(
            NbtCompound(mapOf("value" to NbtString("context"))),
            tag,
        )
        assertEquals(
            value,
            format.decodeFromNbtTag(ContextHolder.serializer(), tag),
        )
    }
}

@Serializable
private data class TreeSample(
    val boolean: Boolean,
    val byte: Byte,
    val short: Short,
    val int: Int,
    val long: Long,
    val float: Float,
    val double: Double,
    val text: String,
    val bytes: ByteArray,
    val ints: IntArray,
    val longs: LongArray,
    val shorts: ShortArray,
    val mode: TreeMode,
    val inline: InlineInt,
    val nested: NestedValue,
)

@Serializable
private enum class TreeMode {
    FIRST,

    @SerialName("second")
    SECOND,
}

@Serializable
@JvmInline
private value class InlineInt(val value: Int)

@Serializable
private data class NestedValue(@SerialName("renamed") val value: String)

@Serializable
private data class CollectionSample(
    val values: List<Int>,
    val mapping: Map<String, Long>,
    val raw: NbtTag,
    val concrete: NbtCompound,
)

@Serializable
private data object EmptyObject

@Serializable
private data class NestedFailure(val values: List<Int>)

@Serializable
private data class PrimitiveArraySample(
    val booleans: BooleanArray,
    val shorts: ShortArray,
    val floats: FloatArray,
    val doubles: DoubleArray,
)

@Serializable
private data class CharArraySample(val value: CharArray)

@Serializable
private data class DefaultsSample(
    val number: Int = 7,
    val nullable: String? = null,
)

@Serializable
private data class RequiredNullable(val value: String?)

@Serializable
private sealed interface PolymorphicValue {
    @Serializable
    data class Text(val value: String) : PolymorphicValue
}

@Serializable
private data class PolymorphicHolder(val value: PolymorphicValue)

private data class ContextValue(val value: String)

private object ContextValueSerializer : KSerializer<ContextValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ContextValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ContextValue) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): ContextValue =
        ContextValue(decoder.decodeString())
}

private object ByteArrayAsStringSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ByteArrayAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(value.toHexString())
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        decoder.decodeString().hexToByteArray()
}

@Serializable
private data class ContextHolder(@Contextual val value: ContextValue)
