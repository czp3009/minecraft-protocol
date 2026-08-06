package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.test.*

class NbtFormatBinaryTest {
    @Test
    fun rootFormsHaveExactBytesAndGenericFormatUsesConfiguredFraming() {
        val value = NbtInt(42)
        val anyBytes = byteArrayOf(3, 0, 0, 0, 42)
        val unnamedBytes = byteArrayOf(3, 0, 0, 0, 0, 0, 42)
        val namedBytes = byteArrayOf(3, 0, 1, 'x'.code.toByte(), 0, 0, 0, 42)

        assertContentEquals(anyBytes, NbtFormat.encodeAnyTagToByteArray(value))
        assertContentEquals(
            unnamedBytes,
            NbtFormat.encodeUnnamedTagToByteArray(value),
        )
        assertContentEquals(
            namedBytes,
            NbtFormat.encodeNamedTagToByteArray(NamedNbtTag("x", value)),
        )
        assertContentEquals(
            byteArrayOf(10, 0, 0, 0),
            NbtFormat.encodeDocumentToByteArray(
                NbtDocument(NbtCompound(emptyMap())),
            ),
        )
        assertContentEquals(
            byteArrayOf(0),
            NbtFormat.encodeAnyTagToByteArray(NbtEnd),
        )
        assertContentEquals(
            byteArrayOf(0),
            NbtFormat.encodeUnnamedTagToByteArray(NbtEnd),
        )
        assertEquals(value, NbtFormat.decodeUnnamedTagFromByteArray(namedBytes))

        assertContentEquals(
            byteArrayOf(3, 0, 0, 0, 42),
            NbtFormat.encodeToByteArray(Int.serializer(), 42),
        )
        assertContentEquals(
            unnamedBytes,
            NbtFormat(
                NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
            ).encodeToByteArray(Int.serializer(), 42),
        )
        val namedFormat = NbtFormat(
            NbtFormatConfiguration(
                rootEncoding = NbtRootEncoding.NAMED,
                rootName = "x",
            ),
        )
        assertContentEquals(namedBytes, namedFormat.encodeToByteArray(Int.serializer(), 42))
        assertEquals(42, namedFormat.decodeFromByteArray(Int.serializer(), namedBytes))
        assertFailsWith<NbtDecodingException> {
            NbtFormat(
                NbtFormatConfiguration(
                    rootEncoding = NbtRootEncoding.NAMED,
                    rootName = "other",
                ),
            ).decodeFromByteArray(Int.serializer(), namedBytes)
        }
    }

    @Test
    fun genericBinaryFormatRoundTripsClassesAndRejectsNamedEndRoots() {
        val format = NbtFormat(
            NbtFormatConfiguration(
                rootEncoding = NbtRootEncoding.NAMED,
                rootName = "sample",
            ),
        )
        val value = BinaryFormatSample(
            number = 42,
            names = listOf("first", "second"),
        )

        val encoded = format.encodeToByteArray(
            BinaryFormatSample.serializer(),
            value,
        )
        assertEquals(
            value,
            format.decodeFromByteArray(
                BinaryFormatSample.serializer(),
                encoded,
            ),
        )

        val sink = Buffer()
        assertFailsWith<NbtEncodingException> {
            format.encodeToSink(NbtEnd.serializer(), NbtEnd, sink)
        }
        assertTrue(sink.exhausted())
    }

    @Test
    fun roundTripsEveryTagKindInDocumentAndNamedRoot() {
        val document = NbtDocument(
            root = NbtCompound(
                linkedMapOf(
                    "byte" to NbtByte(-7),
                    "short" to NbtShort(-32_000),
                    "int" to NbtInt(Int.MIN_VALUE),
                    "long" to NbtLong(Long.MAX_VALUE),
                    "float" to NbtFloat(1.25f),
                    "double" to NbtDouble(-2.5),
                    "bytes" to NbtByteArray(byteArrayOf(0, -1, 127)),
                    "string" to NbtString("zero\u0000supplementary😀"),
                    "list" to NbtList(listOf(NbtInt(1), NbtInt(2))),
                    "emptyList" to NbtList(emptyList()),
                    "compound" to NbtCompound(
                        linkedMapOf("nested" to NbtString("value")),
                    ),
                    "ints" to NbtIntArray(intArrayOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)),
                    "longs" to NbtLongArray(longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE)),
                ),
            ),
        )

        val encoded = NbtFormat.encodeDocumentToByteArray(document)

        assertEquals(document, NbtFormat.decodeDocumentFromByteArray(encoded))

        val named = NamedNbtTag("root\u0000😀", document.root)
        assertEquals(
            named,
            NbtFormat.decodeNamedTagFromByteArray(
                NbtFormat.encodeNamedTagToByteArray(named),
            ),
        )
    }

    @Test
    fun usesJavaModifiedUtfEncoding() {
        val encoded = NbtFormat.encodeAnyTagToByteArray(
            NbtString("\u0000Aé😀"),
        )

        assertContentEquals(
            byteArrayOf(
                8,
                0,
                11,
                0xC0.toByte(),
                0x80.toByte(),
                0x41,
                0xC3.toByte(),
                0xA9.toByte(),
                0xED.toByte(),
                0xA0.toByte(),
                0xBD.toByte(),
                0xED.toByte(),
                0xB8.toByte(),
                0x80.toByte(),
            ),
            encoded,
        )
        assertEquals(
            NbtString("\u0000Aé😀"),
            NbtFormat.decodeAnyTagFromByteArray(encoded),
        )
    }

    @Test
    fun streamDecodeLeavesFollowingBytesUnread() {
        val first = NbtFormat.encodeAnyTagToByteArray(NbtInt(42))
        val buffer = Buffer()
        buffer.write(first)
        buffer.write(byteArrayOf(99, 100))

        assertEquals(NbtInt(42), NbtFormat.decodeAnyTag(buffer))
        assertContentEquals(byteArrayOf(99, 100), buffer.readByteArray())
    }

    @Test
    fun streamMethodsLeaveCallerOwnedSourceAndSinkOpen() {
        val rawSink = TrackingRawSink()
        val sink = rawSink.buffered()

        NbtFormat.encodeAnyTag(sink, NbtInt(42))

        assertFalse(rawSink.closed)
        sink.flush()
        val encoded = rawSink.storage.readByteArray()

        val rawSource = TrackingRawSource(encoded)
        val source = rawSource.buffered()
        assertEquals(NbtInt(42), NbtFormat.decodeAnyTag(source))
        assertFalse(rawSource.closed)
    }

    @Test
    fun byteArrayDecodeRejectsTrailingBytes() {
        val encoded = byteArrayOf(
            *NbtFormat.encodeAnyTagToByteArray(NbtByte(1)),
            0,
        )

        assertFailsWith<NbtSerializationException> {
            NbtFormat.decodeAnyTagFromByteArray(encoded)
        }
    }

    @Test
    fun rejectsTruncatedAndUnknownTags() {
        assertFailsWith<NbtSerializationException> {
            NbtFormat.decodeAnyTagFromByteArray(byteArrayOf(3, 0, 0))
        }
        assertFailsWith<NbtSerializationException> {
            NbtFormat.decodeAnyTagFromByteArray(byteArrayOf(13))
        }
    }

    @Test
    fun rejectsInvalidLengthsAndListElementTypes() {
        val negativeLengths = listOf(
            byteArrayOf(7, -1, -1, -1, -1),
            byteArrayOf(9, 3, -1, -1, -1, -1),
            byteArrayOf(11, -1, -1, -1, -1),
            byteArrayOf(12, -1, -1, -1, -1),
        )
        negativeLengths.forEach { encoded ->
            assertFailsWith<NbtSerializationException> {
                NbtFormat.decodeAnyTagFromByteArray(encoded)
            }
        }
        assertFailsWith<NbtSerializationException> {
            NbtFormat.decodeAnyTagFromByteArray(
                byteArrayOf(9, 0, 0, 0, 0, 1),
            )
        }
        assertFailsWith<NbtSerializationException> {
            NbtFormat.decodeAnyTagFromByteArray(
                byteArrayOf(9, 13, 0, 0, 0, 1),
            )
        }
        assertEquals(
            NbtList(emptyList()),
            NbtFormat.decodeAnyTagFromByteArray(
                byteArrayOf(9, 13, 0, 0, 0, 0),
            ),
        )
    }

    @Test
    fun appliesDepthCollectionStringAndByteLimits() {
        val limited = NbtFormat(
            NbtFormatConfiguration(
                maximumDepth = 1,
                maximumCollectionSize = 1,
                maximumByteArraySize = 1,
                maximumStringBytes = 2,
                maximumEncodedBytes = 32,
            ),
        )

        assertFailsWith<NbtSerializationException> {
            limited.encodeAnyTagToByteArray(
                NbtCompound(
                    mapOf(
                        "a" to NbtCompound(
                            mapOf("b" to NbtInt(1)),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<NbtSerializationException> {
            limited.encodeAnyTagToByteArray(
                NbtCompound(mapOf("a" to NbtInt(1), "b" to NbtInt(2))),
            )
        }
        assertFailsWith<NbtSerializationException> {
            limited.encodeAnyTagToByteArray(NbtByteArray(byteArrayOf(1, 2)))
        }
        assertFailsWith<NbtSerializationException> {
            limited.encodeAnyTagToByteArray(NbtString("abc"))
        }
    }

    @Test
    fun rejectsNamedEndAndNonCompoundDocuments() {
        assertFailsWith<IllegalArgumentException> {
            NamedNbtTag("", NbtEnd)
        }

        val namedString = NbtFormat.encodeNamedTagToByteArray(
            NamedNbtTag("", NbtString("not a compound")),
        )
        assertFailsWith<NbtSerializationException> {
            NbtFormat.decodeDocumentFromByteArray(namedString)
        }
    }

    @Test
    fun doesNotConsumeFollowingValueWhenReadingFromStream() {
        val buffer = Buffer()
        NbtFormat.encodeAnyTag(buffer, NbtByte(1))
        NbtFormat.encodeAnyTag(buffer, NbtByte(2))

        assertEquals(NbtByte(1), NbtFormat.decodeAnyTag(buffer))
        assertFalse(buffer.exhausted())
        assertEquals(NbtByte(2), NbtFormat.decodeAnyTag(buffer))
    }

    @Test
    fun validatesEveryConfiguredResourceLimit() {
        assertFailsWith<IllegalArgumentException> {
            NbtFormatConfiguration(maximumDepth = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtFormatConfiguration(maximumCollectionSize = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtFormatConfiguration(maximumByteArraySize = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtFormatConfiguration(maximumStringBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtFormatConfiguration(maximumStringBytes = 65_536)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtFormatConfiguration(maximumEncodedBytes = -1)
        }
    }

    @Test
    fun rejectsMalformedModifiedUtfSequences() {
        fun encodedString(vararg payload: Int): ByteArray =
            byteArrayOf(
                8,
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
                *payload.map(Int::toByte).toByteArray(),
            )

        val malformed = listOf(
            encodedString(0x80),
            encodedString(0xC0),
            encodedString(0xC0, 0x41),
            encodedString(0xE0),
            encodedString(0xE0, 0x80),
            encodedString(0xE0, 0x80, 0x41),
            encodedString(0xF0, 0x80, 0x80, 0x80),
        )

        malformed.forEach { encoded ->
            assertFailsWith<NbtSerializationException> {
                NbtFormat.decodeAnyTagFromByteArray(encoded)
            }
        }
    }

    @Test
    fun canonicalizesModifiedUtfAndFloatingPointEdgeRepresentations() {
        val rawNull = byteArrayOf(8, 0, 1, 0)
        assertEquals(
            NbtString("\u0000"),
            NbtFormat.decodeAnyTagFromByteArray(rawNull),
        )
        assertContentEquals(
            byteArrayOf(8, 0, 2, 0xC0.toByte(), 0x80.toByte()),
            NbtFormat.encodeAnyTagToByteArray(
                NbtFormat.decodeAnyTagFromByteArray(rawNull),
            ),
        )

        val unpairedSurrogate = NbtString("\uD800")
        assertEquals(
            unpairedSurrogate,
            NbtFormat.decodeAnyTagFromByteArray(
                NbtFormat.encodeAnyTagToByteArray(unpairedSurrogate),
            ),
        )

        val noncanonicalFloatNaN = NbtFloat(Float.fromBits(0x7FA1_2345))
        assertContentEquals(
            byteArrayOf(5, 0x7F, 0xC0.toByte(), 0, 0),
            NbtFormat.encodeAnyTagToByteArray(noncanonicalFloatNaN),
        )
        val noncanonicalDoubleNaN = NbtDouble(
            Double.fromBits(0x7FF0_0000_0000_0001L),
        )
        assertContentEquals(
            byteArrayOf(6, 0x7F, 0xF8.toByte(), 0, 0, 0, 0, 0, 0),
            NbtFormat.encodeAnyTagToByteArray(noncanonicalDoubleNaN),
        )

        val negativeFloatZero = NbtFormat.decodeAnyTagFromByteArray(
            NbtFormat.encodeAnyTagToByteArray(NbtFloat(-0.0f)),
        ) as NbtFloat
        assertEquals((-0.0f).toRawBits(), negativeFloatZero.value.toRawBits())
        val negativeDoubleZero = NbtFormat.decodeAnyTagFromByteArray(
            NbtFormat.encodeAnyTagToByteArray(NbtDouble(-0.0)),
        ) as NbtDouble
        assertEquals((-0.0).toRawBits(), negativeDoubleZero.value.toRawBits())
    }

    @Test
    fun wrapsMixedListsAndRejectsInvalidEndPlacement() {
        val mixed = NbtList(
            listOf(
                NbtInt(1),
                NbtString("two"),
                NbtCompound(mapOf("value" to NbtLong(3))),
            ),
        )
        assertEquals(
            mixed,
            NbtFormat.decodeAnyTagFromByteArray(
                NbtFormat.encodeAnyTagToByteArray(mixed),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            NbtCompound(mapOf("end" to NbtEnd))
        }
        assertFailsWith<NbtSerializationException> {
            NbtFormat.decodeNamedTagFromByteArray(byteArrayOf(0))
        }
    }

    @Test
    fun preservesWrapperShapedCompoundsAndCanonicalizesOfficialReadForms() {
        val wrapperShaped = NbtList(
            listOf(NbtCompound(mapOf("" to NbtInt(1)))),
        )
        assertEquals(
            wrapperShaped,
            NbtFormat.decodeAnyTagFromByteArray(
                NbtFormat.encodeAnyTagToByteArray(wrapperShaped),
            ),
        )

        val noncanonicalEmptyList = byteArrayOf(9, 13, 0, 0, 0, 0)
        val emptyList = NbtFormat.decodeAnyTagFromByteArray(noncanonicalEmptyList)
        assertEquals(NbtList(emptyList()), emptyList)
        assertContentEquals(
            byteArrayOf(9, 0, 0, 0, 0, 0),
            NbtFormat.encodeAnyTagToByteArray(emptyList),
        )

        val duplicateName = byteArrayOf(
            10,
            3, 0, 1, 'x'.code.toByte(), 0, 0, 0, 1,
            3, 0, 1, 'x'.code.toByte(), 0, 0, 0, 2,
            0,
        )
        assertEquals(
            NbtCompound(mapOf("x" to NbtInt(2))),
            NbtFormat.decodeAnyTagFromByteArray(duplicateName),
        )
    }

    @Test
    fun encodedByteLimitAppliesToStreamAndArrayReadsAndWrites() {
        val limited = NbtFormat(
            NbtFormatConfiguration(
                maximumEncodedBytes = 4,
            ),
        )
        val encodedInt = NbtFormat.encodeAnyTagToByteArray(NbtInt(1))

        assertFailsWith<NbtSerializationException> {
            limited.decodeAnyTagFromByteArray(encodedInt)
        }
        assertFailsWith<NbtSerializationException> {
            val source = Buffer().also { it.write(encodedInt) }
            limited.decodeAnyTag(source)
        }
        assertFailsWith<NbtSerializationException> {
            limited.encodeAnyTagToByteArray(NbtInt(1))
        }
        assertFailsWith<NbtSerializationException> {
            val sink = Buffer()
            limited.encodeAnyTag(sink, NbtInt(1))
        }
    }

    @Test
    fun depthAndAllocationLimitsAlsoApplyWhileDecoding() {
        val nested = NbtFormat.encodeAnyTagToByteArray(
            NbtCompound(
                mapOf(
                    "nested" to NbtCompound(
                        mapOf("value" to NbtInt(1)),
                    ),
                ),
            ),
        )
        val depthLimited = NbtFormat(
            NbtFormatConfiguration(maximumDepth = 0),
        )
        assertFailsWith<NbtSerializationException> {
            depthLimited.decodeAnyTagFromByteArray(nested)
        }

        val declaredIntArray = byteArrayOf(
            11,
            0,
            0,
            0,
            2,
        )
        val allocationLimited = NbtFormat(
            NbtFormatConfiguration(
                maximumCollectionSize = 2,
                maximumEncodedBytes = declaredIntArray.size.toLong(),
            ),
        )
        assertFailsWith<NbtSerializationException> {
            allocationLimited.decodeAnyTagFromByteArray(declaredIntArray)
        }
    }

    @Test
    fun declaredSizesAreRejectedBeforeMissingPayloadsAreRead() {
        val limited = NbtFormat(
            NbtFormatConfiguration(
                maximumCollectionSize = 1,
                maximumByteArraySize = 1,
                maximumStringBytes = 1,
            ),
        )
        val oversizedDeclarations = listOf(
            byteArrayOf(7, 0, 0, 0, 2),
            byteArrayOf(8, 0, 2),
            byteArrayOf(9, 1, 0, 0, 0, 2),
            byteArrayOf(11, 0, 0, 0, 2),
            byteArrayOf(12, 0, 0, 0, 2),
            byteArrayOf(
                10,
                1, 0, 1, 'a'.code.toByte(), 1,
                1,
            ),
        )

        oversizedDeclarations.forEach { encoded ->
            assertFailsWith<NbtLimitException> {
                limited.decodeAnyTagFromByteArray(encoded)
            }
        }
    }

    @Test
    fun deterministicallyRoundTripsRandomNestedValues() {
        val random = Random(0x4E4254)

        repeat(250) {
            val tag = randomTag(random, depth = 3)
            val encoded = NbtFormat.encodeAnyTagToByteArray(tag)
            assertEquals(
                tag,
                NbtFormat.decodeAnyTagFromByteArray(encoded),
                "Random NBT sample $it failed",
            )
        }
    }

    @Test
    fun everyTagKindRejectsEveryTruncatedPrefix() {
        val samples = listOf(
            NbtEnd,
            NbtByte(Byte.MIN_VALUE),
            NbtShort(Short.MIN_VALUE),
            NbtInt(Int.MIN_VALUE),
            NbtLong(Long.MIN_VALUE),
            NbtFloat(Float.NEGATIVE_INFINITY),
            NbtDouble(Double.POSITIVE_INFINITY),
            NbtByteArray(byteArrayOf(1, 2)),
            NbtString("\u0000😀"),
            NbtList(listOf(NbtInt(1), NbtInt(2))),
            NbtCompound(mapOf("value" to NbtLong(1))),
            NbtIntArray(intArrayOf(1, 2)),
            NbtLongArray(longArrayOf(1, 2)),
        )

        samples.forEach { sample ->
            val encoded = NbtFormat.encodeAnyTagToByteArray(sample)
            for (endIndex in encoded.indices) {
                assertFailsWith<NbtSerializationException>(
                    "Accepted ${sample::class.simpleName} prefix $endIndex/${encoded.size}",
                ) {
                    NbtFormat.decodeAnyTagFromByteArray(
                        encoded.copyOf(endIndex),
                    )
                }
            }
        }
    }

    @Test
    fun byteArrayCollectionAndExactEncodedLimitsAreIndependent() {
        val byteFriendly = NbtFormat(
            NbtFormatConfiguration(
                maximumCollectionSize = 1,
                maximumByteArraySize = 3,
            ),
        )
        val bytes = NbtByteArray(byteArrayOf(1, 2, 3))
        assertEquals(
            bytes,
            byteFriendly.decodeAnyTagFromByteArray(
                byteFriendly.encodeAnyTagToByteArray(bytes),
            ),
        )
        assertFailsWith<NbtSerializationException> {
            byteFriendly.encodeAnyTagToByteArray(
                NbtIntArray(intArrayOf(1, 2)),
            )
        }

        val collectionFriendly = NbtFormat(
            NbtFormatConfiguration(
                maximumCollectionSize = 3,
                maximumByteArraySize = 1,
            ),
        )
        val list = NbtList(listOf(NbtInt(1), NbtInt(2), NbtInt(3)))
        assertEquals(
            list,
            collectionFriendly.decodeAnyTagFromByteArray(
                collectionFriendly.encodeAnyTagToByteArray(list),
            ),
        )
        assertFailsWith<NbtSerializationException> {
            collectionFriendly.encodeAnyTagToByteArray(
                NbtByteArray(byteArrayOf(1, 2)),
            )
        }

        val encoded = NbtFormat.encodeAnyTagToByteArray(NbtInt(1))
        val exact = NbtFormat(
            NbtFormatConfiguration(
                maximumEncodedBytes = encoded.size.toLong(),
            ),
        )
        assertEquals(NbtInt(1), exact.decodeAnyTagFromByteArray(encoded))
        assertContentEquals(encoded, exact.encodeAnyTagToByteArray(NbtInt(1)))
    }

    @Test
    fun modifiedUtfAcceptsItsExactMaximumAndRejectsOneByteMore() {
        val maximum = "a".repeat(65_535)
        assertEquals(
            NbtString(maximum),
            NbtFormat.decodeAnyTagFromByteArray(
                NbtFormat.encodeAnyTagToByteArray(NbtString(maximum)),
            ),
        )
        assertFailsWith<NbtSerializationException> {
            NbtFormat.encodeAnyTagToByteArray(
                NbtString("$maximum\u0000"),
            )
        }
    }

    private fun randomTag(random: Random, depth: Int): NbtTag {
        if (depth == 0) return randomLeaf(random, random.nextInt(10))
        return when (random.nextInt(12)) {
            0 -> {
                val size = random.nextInt(5)
                NbtList(List(size) { randomTag(random, depth - 1) })
            }

            1, 2 -> NbtCompound(
                buildMap {
                    repeat(random.nextInt(5)) { index ->
                        put("entry-$index\u0000", randomTag(random, depth - 1))
                    }
                },
            )

            else -> randomLeaf(random, random.nextInt(10))
        }
    }
}

@Serializable
private data class BinaryFormatSample(
    val number: Int,
    val names: List<String>,
)

private class TrackingRawSource(bytes: ByteArray) : RawSource {
    private val storage = Buffer().also { it.write(bytes) }
    var closed: Boolean = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        storage.readAtMostTo(sink, byteCount)

    override fun close() {
        closed = true
    }
}

private class TrackingRawSink : RawSink {
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

private fun randomLeaf(random: Random, kind: Int): NbtTag =
    when (kind) {
        0 -> NbtByte(random.nextInt().toByte())
        1 -> NbtShort(random.nextInt().toShort())
        2 -> NbtInt(random.nextInt())
        3 -> NbtLong(random.nextLong())
        4 -> {
            val value = random.nextDouble(-1.0e6, 1.0e6).toFloat()
            NbtFloat(Float.fromBits(value.toBits()))
        }

        5 -> NbtDouble(random.nextDouble(-1.0e100, 1.0e100))
        6 -> NbtByteArray(ByteArray(random.nextInt(9)) {
            random.nextInt().toByte()
        })

        7 -> NbtString(
            buildString {
                repeat(random.nextInt(9)) {
                    append(
                        listOf("a", "\u0000", "é", "😀")
                            [random.nextInt(4)],
                    )
                }
            },
        )

        8 -> NbtIntArray(IntArray(random.nextInt(9)) {
            random.nextInt()
        })

        else -> NbtLongArray(LongArray(random.nextInt(9)) {
            random.nextLong()
        })
    }
