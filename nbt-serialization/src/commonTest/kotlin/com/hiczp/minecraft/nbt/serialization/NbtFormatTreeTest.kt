package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
        val treeSample = TreeSample(
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

        val nbtTag = NbtFormat.encodeToNbtTag(treeSample)
        val nbtCompound = nbtTag as NbtCompound
        assertEquals(NbtByte(1), nbtCompound.value["boolean"])
        assertEquals(NbtByteArray(byteArrayOf(1, 2)), nbtCompound.value["bytes"])
        assertEquals(NbtIntArray(intArrayOf(3, 4)), nbtCompound.value["ints"])
        assertEquals(NbtLongArray(longArrayOf(5, 6)), nbtCompound.value["longs"])
        assertEquals(
            NbtList(listOf(NbtShort(7), NbtShort(8))),
            nbtCompound.value["shorts"],
        )
        assertEquals(NbtString("second"), nbtCompound.value["mode"])
        assertEquals(NbtInt(9), nbtCompound.value["inline"])
        assertEquals(
            NbtCompound(mapOf("renamed" to NbtString("value"))),
            nbtCompound.value["nested"],
        )

        val decoded = NbtFormat.decodeFromNbtTag<TreeSample>(nbtTag)
        assertEquals(
            treeSample,
            decoded.copy(
                bytes = treeSample.bytes,
                ints = treeSample.ints,
                longs = treeSample.longs,
                shorts = treeSample.shorts,
            ),
        )
        assertContentEquals(treeSample.bytes, decoded.bytes)
        assertContentEquals(treeSample.ints, decoded.ints)
        assertContentEquals(treeSample.longs, decoded.longs)
        assertContentEquals(treeSample.shorts, decoded.shorts)
    }

    @Test
    fun `classes lists and string maps round trip`() {
        val collectionSample = CollectionSample(
            values = listOf(1, 2, 3),
            mapping = linkedMapOf("first" to 1L, "second" to 2L),
            raw = NbtList(listOf(NbtInt(1), NbtString("two"))),
            concrete = NbtCompound(mapOf("value" to NbtInt(3))),
        )
        val nbtTag = NbtFormat.encodeToNbtTag(collectionSample)

        assertEquals(collectionSample, NbtFormat.decodeFromNbtTag<CollectionSample>(nbtTag))
        assertEquals(
            collectionSample.raw,
            NbtFormat.encodeToNbtTag(collectionSample.raw),
        )
        assertEquals(
            collectionSample.raw,
            NbtFormat.decodeFromNbtTag<NbtTag>(collectionSample.raw),
        )
    }

    @Test
    fun `objects use empty compounds and nested failures report their path`() {
        assertEquals(
            NbtCompound(emptyMap()),
            NbtFormat.encodeToNbtTag(EmptyObject),
        )
        assertEquals(
            EmptyObject,
            NbtFormat.decodeFromNbtTag<EmptyObject>(NbtCompound(emptyMap())),
        )

        val failure = assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag<NestedFailure>(
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
        val primitiveArraySample = PrimitiveArraySample(
            booleans = booleanArrayOf(true, false),
            shorts = shortArrayOf(1, -2),
            floats = floatArrayOf(1.25f, -2.5f),
            doubles = doubleArrayOf(3.5, -4.75),
        )
        val nbtTag = NbtFormat.encodeToNbtTag(primitiveArraySample)
        val nbtCompound = nbtTag as NbtCompound

        assertEquals(
            NbtList(listOf(NbtByte(1), NbtByte(0))),
            nbtCompound.value["booleans"],
        )
        assertEquals(
            NbtList(listOf(NbtShort(1), NbtShort(-2))),
            nbtCompound.value["shorts"],
        )
        assertEquals(
            NbtList(listOf(NbtFloat(1.25f), NbtFloat(-2.5f))),
            nbtCompound.value["floats"],
        )
        assertEquals(
            NbtList(listOf(NbtDouble(3.5), NbtDouble(-4.75))),
            nbtCompound.value["doubles"],
        )

        val decoded = NbtFormat.decodeFromNbtTag<PrimitiveArraySample>(nbtTag)
        assertContentEquals(primitiveArraySample.booleans, decoded.booleans)
        assertContentEquals(primitiveArraySample.shorts, decoded.shorts)
        assertContentEquals(primitiveArraySample.floats, decoded.floats)
        assertContentEquals(primitiveArraySample.doubles, decoded.doubles)

        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(CharArraySample(charArrayOf('x')))
        }
    }

    @Test
    fun `defaults unknown keys and nullable properties follow configuration`() {
        val omitted = NbtFormat.encodeToNbtTag(DefaultsSample())
        assertEquals(NbtCompound(emptyMap()), omitted)
        assertEquals(
            DefaultsSample(),
            NbtFormat.decodeFromNbtTag<DefaultsSample>(omitted),
        )
        assertEquals(
            RequiredNullable(null),
            NbtFormat.decodeFromNbtTag<RequiredNullable>(NbtCompound(emptyMap())),
        )
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag<NestedValue>(NbtCompound(emptyMap()))
        }

        val withDefaults = NbtFormat(
            NbtFormatConfiguration(encodeDefaults = true),
        ).encodeToNbtTag(DefaultsSample())
        assertEquals(
            NbtCompound(mapOf("number" to NbtInt(7))),
            withDefaults,
        )

        val unknown = NbtCompound(
            mapOf("number" to NbtInt(8), "extra" to NbtString("ignored")),
        )
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag<DefaultsSample>(unknown)
        }
        assertEquals(
            DefaultsSample(number = 8),
            NbtFormat(
                NbtFormatConfiguration(ignoreUnknownKeys = true),
            ).decodeFromNbtTag<DefaultsSample>(unknown),
        )
    }

    @Test
    fun `null roots list entries and map values are rejected`() {
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag<String?>(null)
        }
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(listOf("value", null))
        }
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(mapOf<String, Int?>("value" to null))
        }
    }

    @Test
    fun `map keys must serialize as strings`() {
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(mapOf(1 to "one"))
        }
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(mapOf(TreeMode.FIRST to 1))
        }
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag<Map<TreeMode, Int>>(
                NbtCompound(mapOf("FIRST" to NbtInt(1))),
            )
        }
    }

    @Test
    fun `strict booleans reject noncanonical bytes`() {
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag<Boolean>(NbtByte(2))
        }
        assertEquals(
            true,
            NbtFormat(
                NbtFormatConfiguration(strictBooleans = false),
            ).decodeFromNbtTag<Boolean>(NbtByte(2)),
        )
    }

    @Test
    fun `tree encoding preserves specialized arrays without policy limits`() {
        assertEquals(
            NbtByteArray(byteArrayOf(1, 2)),
            NbtFormat.encodeToNbtTag(byteArrayOf(1, 2)),
        )
        assertEquals(
            NbtIntArray(intArrayOf(1, 2)),
            NbtFormat.encodeToNbtTag(intArrayOf(1, 2)),
        )
        assertEquals(
            NbtLongArray(longArrayOf(1, 2)),
            NbtFormat.encodeToNbtTag(longArrayOf(1, 2)),
        )
    }

    @Test
    fun `explicit serializers override specialized runtime array mappings`() {
        val value = byteArrayOf(0, 1, -1)
        val nbtTag = NbtFormat.encodeToNbtTag(ByteArrayAsStringSerializer, value)

        assertEquals(NbtString("0001ff"), nbtTag)
        assertContentEquals(
            value,
            NbtFormat.decodeFromNbtTag(ByteArrayAsStringSerializer, nbtTag),
        )
    }

    @Test
    fun `raw tag subtype and enum decoding reject incompatible values`() {
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag<NbtCompound>(NbtInt(1))
        }
        assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromNbtTag<TreeMode>(NbtString("missing"))
        }
    }

    @Test
    fun `char and polymorphism are explicitly unsupported`() {
        assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag('x')
        }
        val failure = assertFailsWith<NbtEncodingException> {
            NbtFormat.encodeToNbtTag(PolymorphicHolder(PolymorphicValue.Text("value")))
        }
        assertTrue(failure.message.orEmpty().contains("Polymorphic"))
    }

    @Test
    fun `contextual serializers use the configured module`() {
        val nbtFormat = NbtFormat(
            NbtFormatConfiguration(
                serializersModule = SerializersModule {
                    contextual(ContextValue::class, ContextValueSerializer)
                },
            ),
        )
        val contextHolder = ContextHolder(ContextValue("context"))
        val nbtTag = nbtFormat.encodeToNbtTag(contextHolder)

        assertEquals(
            NbtCompound(mapOf("value" to NbtString("context"))),
            nbtTag,
        )
        assertEquals(
            contextHolder,
            nbtFormat.decodeFromNbtTag<ContextHolder>(nbtTag),
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
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ContextValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ContextValue) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): ContextValue =
        ContextValue(decoder.decodeString())
}

private object ByteArrayAsStringSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ByteArrayAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(value.toHexString())
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        decoder.decodeString().hexToByteArray()
}

@Serializable
private data class ContextHolder(@Contextual val value: ContextValue)
