package com.hiczp.minecraft.nbt

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.*

class NbtBinaryFormatTest {
    @Test
    fun roundTripsEveryTagKindInNamedDocument() {
        val document = NbtDocument(
            rootName = "root\u0000😀",
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

        val encoded = NbtBinaryFormat.encodeDocumentToByteArray(document)

        assertEquals(document, NbtBinaryFormat.decodeDocumentFromByteArray(encoded))
    }

    @Test
    fun usesJavaModifiedUtfEncoding() {
        val encoded = NbtBinaryFormat.encodeTagToByteArray(
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
            NbtBinaryFormat.decodeTagFromByteArray(encoded),
        )
    }

    @Test
    fun streamDecodeLeavesFollowingBytesUnread() {
        val first = NbtBinaryFormat.encodeTagToByteArray(NbtInt(42))
        val buffer = Buffer()
        buffer.write(first)
        buffer.write(byteArrayOf(99, 100))

        assertEquals(NbtInt(42), NbtBinaryFormat.decodeTag(buffer))
        assertContentEquals(byteArrayOf(99, 100), buffer.readByteArray())
    }

    @Test
    fun byteArrayDecodeRejectsTrailingBytes() {
        val encoded = NbtBinaryFormat.encodeTagToByteArray(NbtByte(1)) +
                byteArrayOf(0)

        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeTagFromByteArray(encoded)
        }
    }

    @Test
    fun rejectsTruncatedAndUnknownTags() {
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeTagFromByteArray(byteArrayOf(3, 0, 0))
        }
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeTagFromByteArray(byteArrayOf(13))
        }
    }

    @Test
    fun rejectsInvalidLengthsAndListElementTypes() {
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeTagFromByteArray(
                byteArrayOf(7, -1, -1, -1, -1),
            )
        }
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeTagFromByteArray(
                byteArrayOf(9, 0, 0, 0, 0, 1),
            )
        }
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeTagFromByteArray(
                byteArrayOf(9, 13, 0, 0, 0, 0),
            )
        }
    }

    @Test
    fun appliesDepthCollectionStringAndByteLimits() {
        val limited = NbtBinaryFormat(
            NbtBinaryFormatConfiguration(
                maximumDepth = 1,
                maximumCollectionSize = 1,
                maximumByteArraySize = 1,
                maximumStringBytes = 2,
                maximumEncodedBytes = 32,
            ),
        )

        assertFailsWith<NbtFormatException> {
            limited.encodeTagToByteArray(
                NbtCompound(
                    mapOf(
                        "a" to NbtCompound(
                            mapOf("b" to NbtInt(1)),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<NbtFormatException> {
            limited.encodeTagToByteArray(
                NbtCompound(mapOf("a" to NbtInt(1), "b" to NbtInt(2))),
            )
        }
        assertFailsWith<NbtFormatException> {
            limited.encodeTagToByteArray(NbtByteArray(byteArrayOf(1, 2)))
        }
        assertFailsWith<NbtFormatException> {
            limited.encodeTagToByteArray(NbtString("abc"))
        }
    }

    @Test
    fun rejectsNamedEndAndNonCompoundDocuments() {
        assertFailsWith<IllegalArgumentException> {
            NamedNbtTag("", NbtEnd)
        }

        val namedString = NbtBinaryFormat.encodeNamedTagToByteArray(
            NamedNbtTag("", NbtString("not a compound")),
        )
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeDocumentFromByteArray(namedString)
        }
    }

    @Test
    fun doesNotConsumeFollowingValueWhenReadingFromStream() {
        val buffer = Buffer()
        NbtBinaryFormat.encodeTag(buffer, NbtByte(1))
        NbtBinaryFormat.encodeTag(buffer, NbtByte(2))

        assertEquals(NbtByte(1), NbtBinaryFormat.decodeTag(buffer))
        assertFalse(buffer.exhausted())
        assertEquals(NbtByte(2), NbtBinaryFormat.decodeTag(buffer))
    }

    @Test
    fun validatesEveryConfiguredResourceLimit() {
        assertFailsWith<IllegalArgumentException> {
            NbtBinaryFormatConfiguration(maximumDepth = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtBinaryFormatConfiguration(maximumCollectionSize = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtBinaryFormatConfiguration(maximumByteArraySize = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtBinaryFormatConfiguration(maximumStringBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtBinaryFormatConfiguration(maximumStringBytes = 65_536)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtBinaryFormatConfiguration(maximumEncodedBytes = -1)
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
            assertFailsWith<NbtFormatException> {
                NbtBinaryFormat.decodeTagFromByteArray(encoded)
            }
        }
    }

    @Test
    fun rejectsInvalidWriterStructuresAndNamedEndInput() {
        assertFailsWith<IllegalArgumentException> {
            NbtList(listOf(NbtInt(1), NbtLong(2)))
        }
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.encodeTagToByteArray(
                NbtCompound(mapOf("end" to NbtEnd)),
            )
        }
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.decodeNamedTagFromByteArray(byteArrayOf(0))
        }
    }

    @Test
    fun encodedByteLimitAppliesToStreamAndArrayReadsAndWrites() {
        val limited = NbtBinaryFormat(
            NbtBinaryFormatConfiguration(
                maximumEncodedBytes = 4,
            ),
        )
        val encodedInt = NbtBinaryFormat.encodeTagToByteArray(NbtInt(1))

        assertFailsWith<NbtFormatException> {
            limited.decodeTagFromByteArray(encodedInt)
        }
        assertFailsWith<NbtFormatException> {
            val source = Buffer().also { it.write(encodedInt) }
            limited.decodeTag(source)
        }
        assertFailsWith<NbtFormatException> {
            limited.encodeTagToByteArray(NbtInt(1))
        }
        assertFailsWith<NbtFormatException> {
            val sink = Buffer()
            limited.encodeTag(sink, NbtInt(1))
        }
    }

    @Test
    fun depthAndAllocationLimitsAlsoApplyWhileDecoding() {
        val nested = NbtBinaryFormat.encodeTagToByteArray(
            NbtCompound(
                mapOf(
                    "nested" to NbtCompound(
                        mapOf("value" to NbtInt(1)),
                    ),
                ),
            ),
        )
        val depthLimited = NbtBinaryFormat(
            NbtBinaryFormatConfiguration(maximumDepth = 0),
        )
        assertFailsWith<NbtFormatException> {
            depthLimited.decodeTagFromByteArray(nested)
        }

        val declaredIntArray = byteArrayOf(
            11,
            0,
            0,
            0,
            2,
        )
        val allocationLimited = NbtBinaryFormat(
            NbtBinaryFormatConfiguration(
                maximumCollectionSize = 2,
                maximumEncodedBytes = declaredIntArray.size.toLong(),
            ),
        )
        assertFailsWith<NbtFormatException> {
            allocationLimited.decodeTagFromByteArray(declaredIntArray)
        }
    }

    @Test
    fun deterministicallyRoundTripsRandomNestedValues() {
        val random = Random(0x4E4254)

        repeat(250) {
            val tag = randomTag(random, depth = 3)
            val encoded = NbtBinaryFormat.encodeTagToByteArray(tag)
            assertEquals(
                tag,
                NbtBinaryFormat.decodeTagFromByteArray(encoded),
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
            val encoded = NbtBinaryFormat.encodeTagToByteArray(sample)
            for (endIndex in encoded.indices) {
                assertFailsWith<NbtFormatException>(
                    "Accepted ${sample::class.simpleName} prefix $endIndex/${encoded.size}",
                ) {
                    NbtBinaryFormat.decodeTagFromByteArray(
                        encoded.copyOf(endIndex),
                    )
                }
            }
        }
    }

    @Test
    fun byteArrayCollectionAndExactEncodedLimitsAreIndependent() {
        val byteFriendly = NbtBinaryFormat(
            NbtBinaryFormatConfiguration(
                maximumCollectionSize = 1,
                maximumByteArraySize = 3,
            ),
        )
        val bytes = NbtByteArray(byteArrayOf(1, 2, 3))
        assertEquals(
            bytes,
            byteFriendly.decodeTagFromByteArray(
                byteFriendly.encodeTagToByteArray(bytes),
            ),
        )
        assertFailsWith<NbtFormatException> {
            byteFriendly.encodeTagToByteArray(
                NbtIntArray(intArrayOf(1, 2)),
            )
        }

        val collectionFriendly = NbtBinaryFormat(
            NbtBinaryFormatConfiguration(
                maximumCollectionSize = 3,
                maximumByteArraySize = 1,
            ),
        )
        val list = NbtList(listOf(NbtInt(1), NbtInt(2), NbtInt(3)))
        assertEquals(
            list,
            collectionFriendly.decodeTagFromByteArray(
                collectionFriendly.encodeTagToByteArray(list),
            ),
        )
        assertFailsWith<NbtFormatException> {
            collectionFriendly.encodeTagToByteArray(
                NbtByteArray(byteArrayOf(1, 2)),
            )
        }

        val encoded = NbtBinaryFormat.encodeTagToByteArray(NbtInt(1))
        val exact = NbtBinaryFormat(
            NbtBinaryFormatConfiguration(
                maximumEncodedBytes = encoded.size.toLong(),
            ),
        )
        assertEquals(NbtInt(1), exact.decodeTagFromByteArray(encoded))
        assertContentEquals(encoded, exact.encodeTagToByteArray(NbtInt(1)))
    }

    @Test
    fun modifiedUtfAcceptsItsExactMaximumAndRejectsOneByteMore() {
        val maximum = "a".repeat(65_535)
        assertEquals(
            NbtString(maximum),
            NbtBinaryFormat.decodeTagFromByteArray(
                NbtBinaryFormat.encodeTagToByteArray(NbtString(maximum)),
            ),
        )
        assertFailsWith<NbtFormatException> {
            NbtBinaryFormat.encodeTagToByteArray(
                NbtString("$maximum\u0000"),
            )
        }
    }

    private fun randomTag(random: Random, depth: Int): NbtTag {
        if (depth == 0) return randomLeaf(random, random.nextInt(10))
        return when (random.nextInt(12)) {
            0 -> {
                val size = random.nextInt(5)
                val leafKind = random.nextInt(10)
                NbtList(List(size) { randomLeaf(random, leafKind) })
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
}
