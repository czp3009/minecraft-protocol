package com.hiczp.minecraft.compression

/**
 * A malformed raw RFC 1951 DEFLATE stream or an exceeded decode limit.
 */
class RawDeflateException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Portable raw RFC 1951 DEFLATE.
 *
 * Decoding accepts stored, fixed-Huffman, and dynamic-Huffman blocks.
 * Encoding deterministically chooses between stored blocks and a portable
 * fixed-Huffman LZ77 stream.
 */
object RawDeflate {
    fun encode(input: ByteArray): ByteArray {
        val stored = encodeStored(input)
        if (input.size < MINIMUM_FIXED_COMPRESSION_BYTES) return stored
        return encodeFixedHuffman(
            input = input,
            maximumOutputBytes = stored.size - 1,
        ) ?: stored
    }

    private fun encodeStored(input: ByteArray): ByteArray {
        val blockCount = if (input.isEmpty()) {
            1L
        } else {
            (input.size.toLong() + MAX_STORED_BLOCK_BYTES - 1) /
                    MAX_STORED_BLOCK_BYTES
        }
        val outputSize = input.size.toLong() + blockCount * 5
        if (outputSize > Int.MAX_VALUE) {
            throw RawDeflateException(
                "Stored DEFLATE output exceeds the byte-array range",
            )
        }

        val output = ByteArray(outputSize.toInt())
        var sourceOffset = 0
        var outputOffset = 0
        var final: Boolean
        do {
            val length = minOf(
                MAX_STORED_BLOCK_BYTES,
                input.size - sourceOffset,
            )
            final = sourceOffset + length == input.size
            output[outputOffset++] = if (final) 1 else 0
            output[outputOffset++] = length.toByte()
            output[outputOffset++] = (length ushr 8).toByte()
            val complement = length xor 0xFFFF
            output[outputOffset++] = complement.toByte()
            output[outputOffset++] = (complement ushr 8).toByte()
            input.copyInto(
                output,
                destinationOffset = outputOffset,
                startIndex = sourceOffset,
                endIndex = sourceOffset + length,
            )
            sourceOffset += length
            outputOffset += length
        } while (!final)
        return output
    }

    private fun encodeFixedHuffman(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray? {
        val writer = DeflateBitWriter(maximumOutputBytes)
        if (
            !writer.writeBits(1, 1) ||
            !writer.writeBits(FIXED_HUFFMAN_BLOCK, 2)
        ) {
            return null
        }

        val latestHashOffsets = IntArray(LZ77_HASH_SIZE) { -1 }
        var offset = 0
        while (offset < input.size) {
            var matchLength = 0
            var matchDistance = 0
            if (offset <= input.size - MINIMUM_MATCH_LENGTH) {
                val candidate = latestHashOffsets[lz77Hash(input, offset)]
                if (
                    candidate >= 0 &&
                    offset - candidate <= MAXIMUM_MATCH_DISTANCE
                ) {
                    val maximumLength = minOf(
                        MAXIMUM_MATCH_LENGTH,
                        input.size - offset,
                    )
                    while (
                        matchLength < maximumLength &&
                        input[candidate + matchLength] ==
                        input[offset + matchLength]
                    ) {
                        matchLength++
                    }
                    if (matchLength >= MINIMUM_MATCH_LENGTH) {
                        matchDistance = offset - candidate
                    } else {
                        matchLength = 0
                    }
                }
            }

            if (matchLength == 0) {
                if (!writer.writeFixedSymbol(input[offset].toInt() and 0xFF)) {
                    return null
                }
                recordLz77Offset(input, offset, latestHashOffsets)
                offset++
            } else {
                if (
                    !writer.writeLength(matchLength) ||
                    !writer.writeDistance(matchDistance)
                ) {
                    return null
                }
                val matchEnd = offset + matchLength
                while (offset < matchEnd) {
                    recordLz77Offset(input, offset, latestHashOffsets)
                    offset++
                }
            }
        }
        if (!writer.writeFixedSymbol(END_OF_BLOCK_SYMBOL)) return null
        return writer.toByteArray()
    }

    fun decode(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        require(maximumOutputBytes >= 0)
        val reader = DeflateBitReader(input)
        val output = DeflateByteAccumulator(maximumOutputBytes)
        var final: Boolean
        do {
            final = reader.readBits(1) != 0
            when (val blockType = reader.readBits(2)) {
                STORED_BLOCK ->
                    decodeStoredBlock(reader, output)

                FIXED_HUFFMAN_BLOCK ->
                    decodeHuffmanBlock(
                        reader,
                        output,
                        FIXED_LITERAL_LENGTH_TREE,
                        FIXED_DISTANCE_TREE,
                    )

                DYNAMIC_HUFFMAN_BLOCK -> {
                    val (literalLengthTree, distanceTree) =
                        readDynamicTrees(reader)
                    decodeHuffmanBlock(
                        reader,
                        output,
                        literalLengthTree,
                        distanceTree,
                    )
                }

                else -> throw RawDeflateException(
                    "Reserved DEFLATE block type $blockType",
                )
            }
        } while (!final)
        reader.requireEnd()
        return output.toByteArray()
    }

    private fun decodeStoredBlock(
        reader: DeflateBitReader,
        output: DeflateByteAccumulator,
    ) {
        reader.alignToByte()
        val length = reader.readUnsignedShortLittleEndian()
        val complement = reader.readUnsignedShortLittleEndian()
        if (length xor 0xFFFF != complement) {
            throw RawDeflateException(
                "Stored DEFLATE block length complement is invalid",
            )
        }
        reader.copyAlignedBytes(output, length)
    }

    private fun readDynamicTrees(
        reader: DeflateBitReader,
    ): Pair<DeflateHuffmanTree, DeflateHuffmanTree> {
        val literalLengthCount = reader.readBits(5) + 257
        if (literalLengthCount > MAXIMUM_LITERAL_LENGTH_CODE_COUNT) {
            throw RawDeflateException(
                "Reserved DEFLATE literal/length code count $literalLengthCount",
            )
        }
        val distanceCount = reader.readBits(5) + 1
        val codeLengthCount = reader.readBits(4) + 4
        val codeLengthLengths = IntArray(CODE_LENGTH_ALPHABET_SIZE)
        repeat(codeLengthCount) { index ->
            codeLengthLengths[CODE_LENGTH_ORDER[index]] =
                reader.readBits(3)
        }
        val codeLengthTree = DeflateHuffmanTree(
            codeLengthLengths,
            "code-length",
            allowSingleSymbolIncomplete = false,
        )

        val expectedLengthCount = literalLengthCount + distanceCount
        val lengths = ArrayList<Int>(expectedLengthCount)
        while (lengths.size < expectedLengthCount) {
            when (val symbol = codeLengthTree.decode(reader)) {
                in 0..15 ->
                    lengths.add(symbol)

                16 -> {
                    if (lengths.isEmpty()) {
                        throw RawDeflateException(
                            "DEFLATE repeat code has no previous length",
                        )
                    }
                    appendRepeatedLength(
                        lengths,
                        expectedLengthCount,
                        lengths.last(),
                        reader.readBits(2) + 3,
                    )
                }

                17 -> appendRepeatedLength(
                    lengths,
                    expectedLengthCount,
                    0,
                    reader.readBits(3) + 3,
                )

                18 -> appendRepeatedLength(
                    lengths,
                    expectedLengthCount,
                    0,
                    reader.readBits(7) + 11,
                )

                else -> throw RawDeflateException(
                    "Invalid DEFLATE code-length symbol $symbol",
                )
            }
        }

        val literalLengthLengths = lengths
            .subList(0, literalLengthCount)
            .toIntArray()
        if (literalLengthLengths[END_OF_BLOCK_SYMBOL] == 0) {
            throw RawDeflateException(
                "Dynamic DEFLATE tree has no end-of-block symbol",
            )
        }
        val distanceLengths = lengths
            .subList(literalLengthCount, expectedLengthCount)
            .toIntArray()
        return DeflateHuffmanTree(
            literalLengthLengths,
            "literal/length",
        ) to DeflateHuffmanTree(
            distanceLengths,
            "distance",
            allowEmpty = true,
        )
    }

    private fun appendRepeatedLength(
        lengths: MutableList<Int>,
        expectedLengthCount: Int,
        value: Int,
        count: Int,
    ) {
        if (count > expectedLengthCount - lengths.size) {
            throw RawDeflateException(
                "DEFLATE code-length repeat exceeds its alphabet",
            )
        }
        repeat(count) { lengths.add(value) }
    }

    private fun decodeHuffmanBlock(
        reader: DeflateBitReader,
        output: DeflateByteAccumulator,
        literalLengthTree: DeflateHuffmanTree,
        distanceTree: DeflateHuffmanTree,
    ) {
        while (true) {
            when (val symbol = literalLengthTree.decode(reader)) {
                in 0..255 ->
                    output.writeByte(symbol)

                END_OF_BLOCK_SYMBOL ->
                    return

                in 257..285 -> {
                    val lengthIndex = symbol - 257
                    val length = LENGTH_BASE[lengthIndex] +
                            reader.readBits(LENGTH_EXTRA_BITS[lengthIndex])
                    val distanceSymbol = distanceTree.decode(reader)
                    if (distanceSymbol !in DISTANCE_BASE.indices) {
                        throw RawDeflateException(
                            "Invalid DEFLATE distance symbol $distanceSymbol",
                        )
                    }
                    val distance = DISTANCE_BASE[distanceSymbol] +
                            reader.readBits(
                                DISTANCE_EXTRA_BITS[distanceSymbol],
                            )
                    output.copyFromDistance(distance, length)
                }

                else -> throw RawDeflateException(
                    "Invalid DEFLATE literal/length symbol $symbol",
                )
            }
        }
    }
}

private class DeflateBitWriter(
    private val maximumBytes: Int,
) {
    private var bytes = ByteArray(minOf(8_192, maximumBytes))
    private var completeBytes: Int = 0
    private var bitOffset: Int = 0

    fun writeBits(value: Int, count: Int): Boolean {
        require(count in 0..16)
        repeat(count) { bit ->
            if (!writeBit((value ushr bit) and 1)) return false
        }
        return true
    }

    fun writeFixedSymbol(symbol: Int): Boolean = when (symbol) {
        in 0..143 -> writeHuffmanCode(0x30 + symbol, 8)
        in 144..255 -> writeHuffmanCode(0x190 + symbol - 144, 9)
        in 256..279 -> writeHuffmanCode(symbol - 256, 7)
        in 280..287 -> writeHuffmanCode(0xC0 + symbol - 280, 8)
        else -> throw RawDeflateException(
            "Invalid fixed DEFLATE symbol $symbol",
        )
    }

    fun writeLength(length: Int): Boolean {
        val index = if (length == MAXIMUM_MATCH_LENGTH) {
            LENGTH_BASE.lastIndex
        } else {
            LENGTH_BASE.indices.firstOrNull { candidate ->
                val extraBits = LENGTH_EXTRA_BITS[candidate]
                length <= LENGTH_BASE[candidate] +
                        ((1 shl extraBits) - 1)
            }
        } ?: throw RawDeflateException(
            "Invalid DEFLATE match length $length",
        )
        return writeFixedSymbol(257 + index) &&
                writeBits(
                    length - LENGTH_BASE[index],
                    LENGTH_EXTRA_BITS[index],
                )
    }

    fun writeDistance(distance: Int): Boolean {
        val index = DISTANCE_BASE.indices.firstOrNull { candidate ->
            val extraBits = DISTANCE_EXTRA_BITS[candidate]
            distance <= DISTANCE_BASE[candidate] +
                    ((1 shl extraBits) - 1)
        } ?: throw RawDeflateException(
            "Invalid DEFLATE match distance $distance",
        )
        return writeHuffmanCode(index, 5) &&
                writeBits(
                    distance - DISTANCE_BASE[index],
                    DISTANCE_EXTRA_BITS[index],
                )
    }

    fun toByteArray(): ByteArray =
        bytes.copyOf(completeBytes + if (bitOffset == 0) 0 else 1)

    private fun writeHuffmanCode(code: Int, length: Int): Boolean {
        for (bit in length - 1 downTo 0) {
            if (!writeBit((code ushr bit) and 1)) return false
        }
        return true
    }

    private fun writeBit(value: Int): Boolean {
        if (bitOffset == 0) {
            if (completeBytes >= maximumBytes) return false
            ensureCapacity(completeBytes + 1)
            bytes[completeBytes] = 0
        }
        if (value != 0) {
            bytes[completeBytes] = (
                    bytes[completeBytes].toInt() or (1 shl bitOffset)
                    ).toByte()
        }
        bitOffset++
        if (bitOffset == Byte.SIZE_BITS) {
            bitOffset = 0
            completeBytes++
        }
        return true
    }

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) return
        val doubled = (bytes.size.toLong().coerceAtLeast(1) * 2)
            .coerceAtMost(maximumBytes.toLong())
            .toInt()
        bytes = bytes.copyOf(maxOf(required, doubled))
    }
}

private class DeflateBitReader(
    private val bytes: ByteArray,
) {
    private var byteIndex: Int = 0
    private var bitOffset: Int = 0

    fun readBits(count: Int): Int {
        require(count in 0..16)
        var value = 0
        repeat(count) { bit ->
            if (byteIndex >= bytes.size) {
                throw RawDeflateException("Truncated DEFLATE bit stream")
            }
            value = value or (
                    ((bytes[byteIndex].toInt() ushr bitOffset) and 1) shl bit
                    )
            bitOffset++
            if (bitOffset == Byte.SIZE_BITS) {
                bitOffset = 0
                byteIndex++
            }
        }
        return value
    }

    fun alignToByte() {
        if (bitOffset != 0) {
            bitOffset = 0
            byteIndex++
        }
    }

    fun readUnsignedShortLittleEndian(): Int {
        checkAligned()
        requireAlignedRange(2)
        val value =
            (bytes[byteIndex].toInt() and 0xFF) or
                    ((bytes[byteIndex + 1].toInt() and 0xFF) shl 8)
        byteIndex += 2
        return value
    }

    fun copyAlignedBytes(
        output: DeflateByteAccumulator,
        length: Int,
    ) {
        checkAligned()
        requireAlignedRange(length)
        output.write(bytes, byteIndex, length)
        byteIndex += length
    }

    fun requireEnd() {
        val consumedBytes = byteIndex + if (bitOffset == 0) 0 else 1
        if (consumedBytes != bytes.size) {
            throw RawDeflateException("Trailing bytes after DEFLATE stream")
        }
    }

    private fun checkAligned() {
        if (bitOffset != 0) {
            throw RawDeflateException("DEFLATE reader is not byte-aligned")
        }
    }

    private fun requireAlignedRange(length: Int) {
        if (
            length < 0 ||
            byteIndex < 0 ||
            byteIndex > bytes.size - length
        ) {
            throw RawDeflateException("Truncated stored DEFLATE block")
        }
    }
}

private class DeflateHuffmanTree(
    lengths: IntArray,
    kind: String,
    allowEmpty: Boolean = false,
    allowSingleSymbolIncomplete: Boolean = true,
) {
    private val symbols: Map<Int, Int>
    private val maximumCodeLength: Int

    init {
        val counts = IntArray(MAXIMUM_CODE_BITS + 1)
        lengths.forEach { length ->
            if (length !in 0..MAXIMUM_CODE_BITS) {
                throw RawDeflateException(
                    "Invalid $kind DEFLATE code length $length",
                )
            }
            if (length != 0) counts[length]++
        }
        val symbolCount = counts.sum()
        if (symbolCount == 0 && !allowEmpty) {
            throw RawDeflateException("Empty $kind DEFLATE Huffman tree")
        }

        var remainingCodes = 1
        for (bits in 1..MAXIMUM_CODE_BITS) {
            remainingCodes = (remainingCodes shl 1) - counts[bits]
            if (remainingCodes < 0) {
                throw RawDeflateException(
                    "Oversubscribed $kind DEFLATE Huffman tree",
                )
            }
        }
        val maximumLength = lengths.maxOrNull() ?: 0
        if (
            symbolCount != 0 &&
            remainingCodes > 0 &&
            !(
                    allowSingleSymbolIncomplete &&
                            symbolCount == 1 &&
                            maximumLength == 1
                    )
        ) {
            throw RawDeflateException(
                "Incomplete $kind DEFLATE Huffman tree",
            )
        }

        val nextCode = IntArray(MAXIMUM_CODE_BITS + 1)
        var code = 0
        for (bits in 1..MAXIMUM_CODE_BITS) {
            code = (code + counts[bits - 1]) shl 1
            nextCode[bits] = code
        }
        symbols = buildMap(symbolCount) {
            lengths.forEachIndexed { symbol, length ->
                if (length != 0) {
                    put(
                        huffmanKey(length, nextCode[length]++),
                        symbol,
                    )
                }
            }
        }
        maximumCodeLength = maximumLength
    }

    fun decode(reader: DeflateBitReader): Int {
        var code = 0
        for (length in 1..maximumCodeLength) {
            code = (code shl 1) or reader.readBits(1)
            symbols[huffmanKey(length, code)]?.let { return it }
        }
        throw RawDeflateException("Invalid DEFLATE Huffman code")
    }
}

private class DeflateByteAccumulator(
    private val maximumSize: Int,
) {
    private var bytes = ByteArray(minOf(8_192, maximumSize))
    var size: Int = 0
        private set

    fun writeByte(value: Int) {
        reserve(1)
        bytes[size++] = value.toByte()
    }

    fun write(
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length)
        reserve(length)
        source.copyInto(
            bytes,
            destinationOffset = size,
            startIndex = offset,
            endIndex = offset + length,
        )
        size += length
    }

    fun copyFromDistance(
        distance: Int,
        length: Int,
    ) {
        if (distance !in 1..size) {
            throw RawDeflateException(
                "DEFLATE match distance $distance exceeds output size $size",
            )
        }
        reserve(length)
        repeat(length) {
            bytes[size] = bytes[size - distance]
            size++
        }
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun reserve(additional: Int) {
        if (additional < 0 || size > maximumSize - additional) {
            throw RawDeflateException(
                "DEFLATE output exceeds configured limit $maximumSize",
            )
        }
        val required = size + additional
        if (required <= bytes.size) return
        val doubled = (bytes.size.toLong().coerceAtLeast(1) * 2)
            .coerceAtMost(maximumSize.toLong())
            .toInt()
        val capacity = maxOf(required, doubled)
        bytes = bytes.copyOf(capacity)
    }
}

private fun huffmanKey(length: Int, code: Int): Int =
    (length shl 16) or code

private fun lz77Hash(input: ByteArray, offset: Int): Int {
    val value =
        ((input[offset].toInt() and 0xFF) shl 16) or
                ((input[offset + 1].toInt() and 0xFF) shl 8) or
                (input[offset + 2].toInt() and 0xFF)
    return (value * LZ77_HASH_MULTIPLIER) ushr
            (Int.SIZE_BITS - LZ77_HASH_BITS)
}

private fun recordLz77Offset(
    input: ByteArray,
    offset: Int,
    latestHashOffsets: IntArray,
) {
    if (offset <= input.size - MINIMUM_MATCH_LENGTH) {
        latestHashOffsets[lz77Hash(input, offset)] = offset
    }
}

private val FIXED_LITERAL_LENGTH_TREE = DeflateHuffmanTree(
    IntArray(288) { symbol ->
        when (symbol) {
            in 0..143 -> 8
            in 144..255 -> 9
            in 256..279 -> 7
            else -> 8
        }
    },
    "fixed literal/length",
)

private val FIXED_DISTANCE_TREE = DeflateHuffmanTree(
    IntArray(32) { 5 },
    "fixed distance",
)

private val CODE_LENGTH_ORDER = intArrayOf(
    16,
    17,
    18,
    0,
    8,
    7,
    9,
    6,
    10,
    5,
    11,
    4,
    12,
    3,
    13,
    2,
    14,
    1,
    15,
)

private val LENGTH_BASE = intArrayOf(
    3,
    4,
    5,
    6,
    7,
    8,
    9,
    10,
    11,
    13,
    15,
    17,
    19,
    23,
    27,
    31,
    35,
    43,
    51,
    59,
    67,
    83,
    99,
    115,
    131,
    163,
    195,
    227,
    258,
)

private val LENGTH_EXTRA_BITS = intArrayOf(
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    1,
    1,
    1,
    2,
    2,
    2,
    2,
    3,
    3,
    3,
    3,
    4,
    4,
    4,
    4,
    5,
    5,
    5,
    5,
    0,
)

private val DISTANCE_BASE = intArrayOf(
    1,
    2,
    3,
    4,
    5,
    7,
    9,
    13,
    17,
    25,
    33,
    49,
    65,
    97,
    129,
    193,
    257,
    385,
    513,
    769,
    1_025,
    1_537,
    2_049,
    3_073,
    4_097,
    6_145,
    8_193,
    12_289,
    16_385,
    24_577,
)

private val DISTANCE_EXTRA_BITS = intArrayOf(
    0,
    0,
    0,
    0,
    1,
    1,
    2,
    2,
    3,
    3,
    4,
    4,
    5,
    5,
    6,
    6,
    7,
    7,
    8,
    8,
    9,
    9,
    10,
    10,
    11,
    11,
    12,
    12,
    13,
    13,
)

private const val STORED_BLOCK = 0
private const val FIXED_HUFFMAN_BLOCK = 1
private const val DYNAMIC_HUFFMAN_BLOCK = 2
private const val END_OF_BLOCK_SYMBOL = 256
private const val CODE_LENGTH_ALPHABET_SIZE = 19
private const val MAXIMUM_CODE_BITS = 15
private const val MAXIMUM_LITERAL_LENGTH_CODE_COUNT = 286
private const val MAX_STORED_BLOCK_BYTES = 65_535
private const val MINIMUM_FIXED_COMPRESSION_BYTES = 32
private const val MINIMUM_MATCH_LENGTH = 3
private const val MAXIMUM_MATCH_LENGTH = 258
private const val MAXIMUM_MATCH_DISTANCE = 32_768
private const val LZ77_HASH_BITS = 16
private const val LZ77_HASH_SIZE = 1 shl LZ77_HASH_BITS
private const val LZ77_HASH_MULTIPLIER = 0x1E35_A7BD
