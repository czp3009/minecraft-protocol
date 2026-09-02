package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.random.Random
import kotlin.test.*

class NbtFormatBinaryTest {
    @Test
    fun rootFormsHaveExactBytesAndGenericFormatUsesConfiguredFraming() {
        val nbtInt = NbtInt(42)
        val anyBytes = byteArrayOf(3, 0, 0, 0, 42)
        val unnamedBytes = byteArrayOf(3, 0, 0, 0, 0, 0, 42)
        val namedBytes = byteArrayOf(3, 0, 1, 'x'.code.toByte(), 0, 0, 0, 42)

        assertContentEquals(anyBytes, NbtFormat.encodeAnyTagToByteArray(nbtInt))
        assertContentEquals(
            unnamedBytes,
            NbtFormat.encodeUnnamedTagToByteArray(nbtInt),
        )
        assertContentEquals(
            namedBytes,
            NbtFormat.encodeNamedTagToByteArray(NamedNbtTag("x", nbtInt)),
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
        assertEquals(nbtInt, NbtFormat.decodeUnnamedTagFromByteArray(namedBytes))

        assertContentEquals(
            byteArrayOf(3, 0, 0, 0, 42),
            NbtFormat.encodeToByteArray(42),
        )
        assertContentEquals(
            unnamedBytes,
            NbtFormat(
                NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
            ).encodeToByteArray(42),
        )
        val namedFormat = NbtFormat(
            NbtFormatConfiguration(
                nbtRootEncoding = NbtRootEncoding.NAMED,
                rootName = "x",
            ),
        )
        assertContentEquals(namedBytes, namedFormat.encodeToByteArray(42))
        assertEquals(42, namedFormat.decodeFromByteArray<Int>(namedBytes))
        assertEquals(
            42,
            NbtFormat(
                NbtFormatConfiguration(
                    nbtRootEncoding = NbtRootEncoding.NAMED,
                    rootName = "other",
                ),
            ).decodeFromByteArray<Int>(namedBytes),
        )
    }

    @Test
    fun genericBinaryFormatRoundTripsClassesAndRejectsNamedEndRoots() {
        val nbtFormat = NbtFormat(
            NbtFormatConfiguration(
                nbtRootEncoding = NbtRootEncoding.NAMED,
                rootName = "sample",
            ),
        )
        val binaryFormatSample = BinaryFormatSample(
            number = 42,
            names = listOf("first", "second"),
        )

        val encoded = nbtFormat.encodeToByteArray(
            binaryFormatSample,
        )
        assertEquals(
            binaryFormatSample,
            nbtFormat.decodeFromByteArray<BinaryFormatSample>(
                encoded,
            ),
        )

        val sink = Buffer()
        assertFailsWith<NbtEncodingException> {
            nbtFormat.encodeToSink(NbtEnd, sink)
        }
        assertTrue(sink.exhausted())
    }

    @Test
    fun genericStreamEncodingEmitsDuringSerializerTraversal() {
        val sink = Buffer()
        val streamingProbe = StreamingProbe(7, "after")
        val streamingProbeSerializer = StreamingProbeSerializer {
            assertTrue(
                sink.size > 0,
                "The first field should reach the sink before the serializer emits the second field",
            )
        }

        NbtFormat.encodeToSink(streamingProbeSerializer, streamingProbe, sink)
        val streamed = sink.readByteArray()

        assertContentEquals(
            NbtFormat.encodeToByteArray(StreamingProbeSerializer {}, streamingProbe),
            streamed,
        )
        assertEquals(
            streamingProbe,
            NbtFormat.decodeFromByteArray(streamingProbeSerializer, streamed),
        )
    }

    @Test
    fun directBinaryMappingRoundTripsMapsArraysNullsAndMixedRawLists() {
        val directBinarySample = DirectBinarySample(
            mapping = linkedMapOf("first" to 1L, "second\u0000" to -2L),
            bytes = byteArrayOf(0, 1, -1),
            requiredNullable = null,
            raw = NbtList(listOf(NbtInt(1), NbtString("two"))),
        )

        val encoded = NbtFormat.encodeToByteArray(
            directBinarySample,
        )
        val decoded = NbtFormat.decodeFromByteArray<DirectBinarySample>(
            encoded,
        )

        assertEquals(directBinarySample, decoded)
        assertEquals(directBinarySample.mapping, decoded.mapping)
        assertContentEquals(directBinarySample.bytes, decoded.bytes)
        assertNull(decoded.requiredNullable)
        assertEquals(directBinarySample.raw, decoded.raw)
    }

    @Test
    fun directBinaryDecoderSkipsUnknownPayloadsWithoutChangingKnownFields() {
        val encoded = NbtFormat.encodeAnyTagToByteArray(
            NbtCompound(
                linkedMapOf(
                    "unknown" to NbtByteArray(ByteArray(32_768) { it.toByte() }),
                    "number" to NbtInt(42),
                ),
            ),
        )
        val nbtFormat = NbtFormat(
            NbtFormatConfiguration(ignoreUnknownKeys = true),
        )

        assertEquals(
            KnownBinaryValue(42),
            nbtFormat.decodeFromByteArray<KnownBinaryValue>(encoded),
        )
    }

    @Test
    fun roundTripsEveryTagKindInDocumentAndNamedRoot() {
        val nbtDocument = NbtDocument(
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

        val encoded = NbtFormat.encodeDocumentToByteArray(nbtDocument)

        assertEquals(nbtDocument, NbtFormat.decodeDocumentFromByteArray(encoded))

        val namedNbtTag = NamedNbtTag("root\u0000😀", nbtDocument.root)
        assertEquals(
            namedNbtTag,
            NbtFormat.decodeNamedTagFromByteArray(
                NbtFormat.encodeNamedTagToByteArray(namedNbtTag),
            ),
        )
    }

    @Test
    fun documentFluentAdaptersWriteAndDecodeWithoutChangingOwnership() {
        val nbtDocument = NbtDocument(NbtCompound(mapOf("number" to NbtInt(42))))
        val nbtSink = Buffer()

        nbtDocument.writeTo(nbtSink)

        assertEquals(nbtDocument, NbtFormat.decodeDocumentFromSource(nbtSink))
        assertEquals(KnownBinaryValue(42), nbtDocument.decodeNbt<KnownBinaryValue>())
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

        assertEquals(NbtInt(42), NbtFormat.decodeAnyTagFromSource(buffer))
        assertContentEquals(byteArrayOf(99, 100), buffer.readByteArray())
    }

    @Test
    fun streamMethodsLeaveCallerOwnedSourceAndSinkOpen() {
        val trackingRawSink = TrackingRawSink()
        val sink = trackingRawSink.buffered()

        NbtFormat.encodeAnyTagToSink(NbtInt(42), sink)

        assertFalse(trackingRawSink.closed)
        sink.flush()
        val encoded = trackingRawSink.storage.readByteArray()

        val trackingRawSource = TrackingRawSource(encoded)
        val source = trackingRawSource.buffered()
        assertEquals(NbtInt(42), NbtFormat.decodeAnyTagFromSource(source))
        assertFalse(trackingRawSource.closed)
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
        assertFailsWith<NbtBinaryFormatException> {
            NbtFormat.decodeAnyTagFromByteArray(byteArrayOf(3, 0, 0))
        }
        assertFailsWith<NbtBinaryFormatException> {
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
            assertFailsWith<NbtBinaryFormatException> {
                NbtFormat.decodeAnyTagFromByteArray(encoded)
            }
        }
        assertFailsWith<NbtBinaryFormatException> {
            NbtFormat.decodeAnyTagFromByteArray(
                byteArrayOf(9, 0, 0, 0, 0, 1),
            )
        }
        assertFailsWith<NbtBinaryFormatException> {
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
    fun doesNotImposePolicyLimitsOnNestedValuesCollectionsStringsOrBytes() {
        val nbtCompound = NbtCompound(
            mapOf(
                "nested" to NbtCompound(
                    mapOf(
                        "list" to NbtList(List(128) { NbtInt(it) }),
                        "bytes" to NbtByteArray(ByteArray(4_096) { it.toByte() }),
                    ),
                ),
                "string" to NbtString("value".repeat(1_024)),
            ),
        )
        val encoded = NbtFormat.encodeAnyTagToByteArray(nbtCompound)

        assertEquals(nbtCompound, NbtFormat.decodeAnyTagFromByteArray(encoded))
    }

    @Test
    fun rejectsNamedEndAndNonCompoundDocuments() {
        assertFailsWith<IllegalArgumentException> {
            NamedNbtTag("", NbtEnd)
        }

        val namedString = NbtFormat.encodeNamedTagToByteArray(
            NamedNbtTag("", NbtString("not a compound")),
        )
        assertFailsWith<NbtBinaryFormatException> {
            NbtFormat.decodeDocumentFromByteArray(namedString)
        }
    }

    @Test
    fun binaryCorruptionIsDistinctFromSerializerMappingFailure() {
        val validNbt = NbtFormat.encodeAnyTagToByteArray(
            NbtCompound(mapOf("other" to NbtInt(42))),
        )

        val failure = assertFailsWith<NbtDecodingException> {
            NbtFormat.decodeFromByteArray<KnownBinaryValue>(validNbt)
        }

        assertFalse(failure is NbtBinaryFormatException)
    }

    @Test
    fun doesNotConsumeFollowingValueWhenReadingFromStream() {
        val buffer = Buffer()
        NbtFormat.encodeAnyTagToSink(NbtByte(1), buffer)
        NbtFormat.encodeAnyTagToSink(NbtByte(2), buffer)

        assertEquals(NbtByte(1), NbtFormat.decodeAnyTagFromSource(buffer))
        assertFalse(buffer.exhausted())
        assertEquals(NbtByte(2), NbtFormat.decodeAnyTagFromSource(buffer))
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
            assertFailsWith<NbtBinaryFormatException> {
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
    fun declaredSizesStillRequireTheirPayloads() {
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
            assertFailsWith<NbtSerializationException> {
                NbtFormat.decodeAnyTagFromByteArray(encoded)
            }
        }
    }

    @Test
    fun deterministicallyRoundTripsRandomNestedValues() {
        val random = Random(0x4E4254)

        repeat(250) {
            val nbtTag = randomTag(random, depth = 3)
            val encoded = NbtFormat.encodeAnyTagToByteArray(nbtTag)
            assertEquals(
                nbtTag,
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
    fun specializedArraysRoundTripWithoutSharedAllocationBudgets() {
        val values = listOf(
            NbtByteArray(ByteArray(257) { it.toByte() }),
            NbtIntArray(IntArray(257) { it }),
            NbtLongArray(LongArray(257) { it.toLong() }),
        )

        values.forEach { value ->
            assertEquals(
                value,
                NbtFormat.decodeAnyTagFromByteArray(NbtFormat.encodeAnyTagToByteArray(value)),
            )
        }
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

@Serializable
private data class DirectBinarySample(
    val mapping: Map<String, Long>,
    val bytes: ByteArray,
    val requiredNullable: String?,
    val raw: NbtTag,
) {
    override fun equals(other: Any?): Boolean =
        other is DirectBinarySample &&
                mapping == other.mapping &&
                bytes.contentEquals(other.bytes) &&
                requiredNullable == other.requiredNullable &&
                raw == other.raw

    override fun hashCode(): Int {
        var result = mapping.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + requiredNullable.hashCode()
        return 31 * result + raw.hashCode()
    }
}

@Serializable
private data class KnownBinaryValue(val number: Int)

private data class StreamingProbe(
    val first: Int,
    val second: String,
)

private class StreamingProbeSerializer(
    private val afterFirst: () -> Unit,
) : KSerializer<StreamingProbe> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("StreamingProbe") {
            element<Int>("first")
            element<String>("second")
        }

    override fun serialize(encoder: Encoder, value: StreamingProbe) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeIntElement(descriptor, 0, value.first)
        afterFirst()
        compositeEncoder.encodeStringElement(descriptor, 1, value.second)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): StreamingProbe {
        val compositeDecoder = decoder.beginStructure(descriptor)
        var first = 0
        var second = ""
        while (true) {
            when (val index = compositeDecoder.decodeElementIndex(descriptor)) {
                0 -> first = compositeDecoder.decodeIntElement(descriptor, index)
                1 -> second = compositeDecoder.decodeStringElement(descriptor, index)
                else -> break
            }
        }
        compositeDecoder.endStructure(descriptor)
        return StreamingProbe(first, second)
    }
}

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
