package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.*
import kotlinx.serialization.Serializable
import kotlin.test.*

class SnbtFormatTest {
    @Test
    fun `compact writer covers every representable tag and sorts compounds`() {
        val tag = NbtCompound(
            linkedMapOf(
                "z" to NbtList(listOf(NbtInt(1), NbtString("two"))),
                "byte" to NbtByte(-1),
                "short" to NbtShort(2),
                "int" to NbtInt(3),
                "long" to NbtLong(4),
                "intMin" to NbtInt(Int.MIN_VALUE),
                "longMin" to NbtLong(Long.MIN_VALUE),
                "float" to NbtFloat(1.25f),
                "double" to NbtDouble(-2.5),
                "floatMin" to NbtFloat(Float.MIN_VALUE),
                "floatMax" to NbtFloat(Float.MAX_VALUE),
                "floatNegativeZero" to NbtFloat(-0.0f),
                "doubleMin" to NbtDouble(Double.MIN_VALUE),
                "doubleMax" to NbtDouble(Double.MAX_VALUE),
                "doubleNegativeZero" to NbtDouble(-0.0),
                "bytes" to NbtByteArray(byteArrayOf(1, -1)),
                "ints" to NbtIntArray(intArrayOf(Int.MIN_VALUE, 2, Int.MAX_VALUE)),
                "longs" to NbtLongArray(longArrayOf(Long.MIN_VALUE, 3, Long.MAX_VALUE)),
                "a key" to NbtString("quote'\"slash\\line\ncontrol\u0001"),
                "nested" to NbtCompound(mapOf("true" to NbtString("value"))),
            ),
        )

        val encoded = SnbtFormat.encodeTagToString(tag)

        assertTrue(encoded.startsWith("{\"a key\":"))
        assertTrue(encoded.contains("bytes:[B;1B,-1B]"))
        assertTrue(encoded.contains("ints:[I;-2147483648,2,2147483647]"))
        assertTrue(encoded.contains("longs:[L;-9223372036854775808L,3L,9223372036854775807L]"))
        assertTrue(encoded.contains("z:[1,\"two\"]"))
        val decoded = SnbtFormat.decodeTagFromString(encoded) as NbtCompound
        assertEquals(tag.size, decoded.size)
        tag.forEachEntry { name, expected ->
            assertEquals(expected, decoded[name], name)
        }
    }

    @Test
    fun `parser accepts selected release numbers arrays booleans and operations`() {
        val parsed = SnbtFormat.decodeTagFromString(
            """
                {
                    zeroByte: 0b,
                    binary: 0 b 1010,
                    hex: 0xFFFFFFFF,
                    signedHex: -0x1si,
                    underscored: 1__2,
                    floats: [.5f, 1.d, 1 e 2],
                    booleans: [TRUE, false, bool(-2), bool(0),],
                    uuid: uuid("1-1-1-1-1"),
                    bytes: [B; 1, 255ub, -1sb,],
                    ints: [I; 1b, 2s, 3,],
                    longs: [L; 1b, 2s, 3, 4L,],
                }
            """.trimIndent(),
        ) as NbtCompound

        assertEquals(NbtByte(0), parsed["zeroByte"])
        assertEquals(NbtInt(10), parsed["binary"])
        assertEquals(NbtInt(-1), parsed["hex"])
        assertEquals(NbtInt(-1), parsed["signedHex"])
        assertEquals(NbtInt(12), parsed["underscored"])
        assertEquals(
            NbtList(listOf(NbtFloat(0.5f), NbtDouble(1.0), NbtDouble(100.0))),
            parsed["floats"],
        )
        assertEquals(
            NbtList(listOf(NbtByte(1), NbtByte(0), NbtByte(1), NbtByte(0))),
            parsed["booleans"],
        )
        assertEquals(NbtIntArray(intArrayOf(1, 65_537, 65_536, 1)), parsed["uuid"])
        assertEquals(NbtByteArray(byteArrayOf(1, -1, -1)), parsed["bytes"])
        assertEquals(NbtIntArray(intArrayOf(1, 2, 3)), parsed["ints"])
        assertEquals(NbtLongArray(longArrayOf(1, 2, 3, 4)), parsed["longs"])
    }

    @Test
    fun `quoted strings support official escapes and configurable Unicode names`() {
        val escaped = "\"\\b\\s\\t\\n\\f\\r\\\\\\'\\\"\\x41\\u0042\\U0001F600\""
        assertEquals(
            NbtString("\b \t\n\u000C\r\\'\"AB😀"),
            SnbtFormat.decodeTagFromString(escaped),
        )

        assertFailsWith<NbtDecodingException> {
            SnbtFormat.decodeTagFromString("\"\\N{LATIN CAPITAL LETTER A}\"")
        }
        val withNames = SnbtFormat(
            SnbtFormatConfiguration(
                unicodeNameResolver = SnbtUnicodeNameResolver { name ->
                    if (name.trim().equals("LATIN CAPITAL LETTER A", ignoreCase = true)) 0x41 else null
                },
            ),
        )
        assertEquals(
            NbtString("A"),
            withNames.decodeTagFromString("\"\\N{LATIN CAPITAL LETTER A}\""),
        )
    }

    @Test
    fun `generic format document and tag shortcuts round trip`() {
        val value = SnbtSample(42, "hello", listOf(1L, 2L))
        val encoded = SnbtFormat.encodeToString(SnbtSample.serializer(), value)

        assertEquals(value, SnbtFormat.decodeFromString(SnbtSample.serializer(), encoded))
        val stream = Buffer()
        SnbtFormat.encodeToSink(SnbtSample.serializer(), value, stream)
        assertEquals(value, SnbtFormat.decodeFromSource(SnbtSample.serializer(), stream))

        val tag = NbtCompound(mapOf("value" to NbtInt(42)))
        assertEquals(tag, tag.toSnbtString().toNbtTag())
        val document = NbtDocument(tag)
        assertEquals(document, document.toSnbtString().toNbtDocument())
    }

    @Test
    fun `stream methods process UTF-8 incrementally and retain caller ownership`() {
        val rawSink = SnbtTrackingRawSink()
        val sink = rawSink.buffered()
        val tag = NbtCompound(mapOf("emoji" to NbtString("😀"), "value" to NbtInt(42)))

        SnbtFormat.encodeTagToSink(tag, sink)
        assertFalse(rawSink.closed)
        sink.flush()

        val bytes = rawSink.storage.readByteArray()
        val rawSource = SnbtTrackingRawSource(bytes)
        val source = rawSource.buffered()
        assertEquals(tag, SnbtFormat.decodeTagFromSource(source))
        assertFalse(rawSource.closed)
    }

    @Test
    fun `writer can retain compound insertion order`() {
        val format = SnbtFormat(SnbtFormatConfiguration(sortCompoundKeys = false))
        val tag = NbtCompound(linkedMapOf("z" to NbtInt(1), "a" to NbtInt(2)))

        assertEquals("{z:1,a:2}", format.encodeTagToString(tag))
    }

    @Test
    fun `unrepresentable values and malformed complete input fail clearly`() {
        assertFailsWith<NbtEncodingException> { SnbtFormat.encodeTagToString(NbtEnd) }
        assertFailsWith<NbtEncodingException> { SnbtFormat.encodeTagToString(NbtFloat(Float.NaN)) }
        assertFailsWith<NbtEncodingException> { SnbtFormat.encodeTagToString(NbtDouble(Double.POSITIVE_INFINITY)) }
        assertFailsWith<NbtEncodingException> {
            SnbtFormat.encodeTagToString(NbtCompound(mapOf("" to NbtInt(1))))
        }

        listOf(
            "",
            "1 trailing",
            "{key:1",
            "[B;128]",
            "[I;1L]",
            "-0x1",
            "1e9999",
            "1._0",
            "1.0_",
            "{} {}",
        ).forEach { malformed ->
            assertFailsWith<NbtDecodingException>(malformed) {
                SnbtFormat.decodeTagFromString(malformed)
            }
        }
    }

    @Test
    fun `stream and string adapters use the same text`() {
        val tag = NbtString("streamed")
        val sink = Buffer()
        SnbtFormat.encodeTagToSink(tag, sink)

        assertEquals(SnbtFormat.encodeTagToString(tag), sink.readString())

        val source = Buffer()
        source.writeString("[1,2,3]")
        assertEquals(
            NbtList(listOf(NbtInt(1), NbtInt(2), NbtInt(3))),
            SnbtFormat.decodeTagFromSource(source),
        )
        assertTrue(source.exhausted())
    }
}

@Serializable
private data class SnbtSample(
    val number: Int,
    val text: String,
    val values: List<Long>,
)

private class SnbtTrackingRawSource(bytes: ByteArray) : RawSource {
    private val storage = Buffer().also { it.write(bytes) }
    var closed: Boolean = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        storage.readAtMostTo(sink, minOf(byteCount, 1L))

    override fun close() {
        closed = true
    }
}

private class SnbtTrackingRawSink : RawSink {
    val storage: Buffer = Buffer()
    var closed: Boolean = false
        private set

    override fun write(source: Buffer, byteCount: Long) {
        storage.write(source, byteCount)
    }

    override fun flush() = Unit

    override fun close() {
        closed = true
    }
}
