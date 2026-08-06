package com.hiczp.minecraft.compression

import kotlinx.io.*

/** A malformed raw RFC 1951 DEFLATE stream or an exceeded decode limit. */
class RawDeflateException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Portable raw RFC 1951 DEFLATE.
 *
 * Encoding uses bounded blocks and deterministically chooses stored or
 * fixed-Huffman LZ77 representation for each block. Decoding accepts stored,
 * fixed-Huffman, and dynamic-Huffman blocks. Transform decorators do not close
 * their caller-owned downstream or upstream.
 */
object RawDeflate {
    /** Returns a sink that compresses bytes into [sink] as they are written. */
    fun compressingSink(sink: Sink): RawSink = AdaptiveDeflateSink(sink)

    /** Returns a source that incrementally inflates bytes from [source]. */
    fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return RawDeflateSource(source, maximumOutputBytes)
    }

    /** Compresses all remaining [source] bytes into [sink]. */
    fun encodeToSink(source: Source, sink: Sink) {
        val compressed = compressingSink(sink).buffered()
        try {
            source.transferTo(compressed)
        } finally {
            compressed.close()
        }
    }

    /**
     * Inflates one complete raw stream into [sink] and rejects trailing input.
     */
    fun decodeToSink(
        source: Source,
        sink: Sink,
        maximumOutputBytes: Int,
    ): Long {
        val decompressed =
            decompressingSource(source, maximumOutputBytes).buffered()
        return try {
            val count = decompressed.transferTo(sink)
            if (!source.exhausted()) {
                throw RawDeflateException(
                    "Trailing bytes after DEFLATE stream",
                )
            }
            count
        } finally {
            decompressed.close()
        }
    }

    fun encode(input: ByteArray): ByteArray {
        val source = Buffer()
        source.write(input)
        val sink = Buffer()
        encodeToSink(source, sink)
        return sink.readByteArray()
    }

    fun decode(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        val source = Buffer()
        source.write(input)
        val sink = Buffer()
        decodeToSink(source, sink, maximumOutputBytes)
        return sink.readByteArray()
    }
}

private class AdaptiveDeflateSink(
    private val downstream: Sink,
) : RawSink {
    private val writer = DeflateBitWriter(downstream)
    private val block = ByteArray(ENCODE_BLOCK_SIZE)
    private var blockSize = 0
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        checkOpen()
        require(byteCount >= 0 && byteCount <= source.size)
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(
                remaining,
                (block.size - blockSize).toLong(),
            ).toInt()
            val read = source.readAtMostTo(
                block,
                startIndex = blockSize,
                endIndex = blockSize + count,
            )
            check(read > 0)
            blockSize += read
            remaining -= read
            if (blockSize == block.size) writeBlock(final = false)
        }
    }

    override fun flush() {
        checkOpen()
        if (blockSize > 0) writeBlock(final = false)
        downstream.flush()
    }

    override fun close() {
        if (closed) return
        try {
            if (blockSize > 0) {
                writeBlock(final = true)
            } else {
                writeFixedBlock(final = true, size = 0)
            }
            writer.finish()
        } finally {
            closed = true
        }
    }

    private fun writeBlock(final: Boolean) {
        val fixedBits = fixedBlockBitCount(blockSize, final)
        val storedBits = storedBlockBitCount(blockSize)
        if (storedBits < fixedBits) {
            writeStoredBlock(final, blockSize)
        } else {
            writeFixedBlock(final, blockSize)
        }
        blockSize = 0
    }

    private fun fixedBlockBitCount(size: Int, final: Boolean): Long {
        var bits = 3L + fixedPayloadBitCount(block, size)
        if (final) {
            bits += bytePadding(writer.bitOffset, bits)
        }
        return bits
    }

    private fun storedBlockBitCount(size: Int): Long {
        val headerBits = 3L
        val padding = bytePadding(writer.bitOffset, headerBits)
        return headerBits + padding + 32L + size * Byte.SIZE_BITS
    }

    private fun writeFixedBlock(final: Boolean, size: Int) {
        writer.writeBits(if (final) 1 else 0, 1)
        writer.writeBits(FIXED_HUFFMAN_BLOCK, 2)
        tokenizeBlock(block, size) { literal, length, distance ->
            if (literal >= 0) {
                writer.writeFixedSymbol(literal)
            } else {
                writer.writeLength(length)
                writer.writeDistance(distance)
            }
        }
        writer.writeFixedSymbol(END_OF_BLOCK_SYMBOL)
    }

    private fun writeStoredBlock(final: Boolean, size: Int) {
        writer.writeBits(if (final) 1 else 0, 1)
        writer.writeBits(STORED_BLOCK, 2)
        writer.alignToByte()
        downstream.writeByte(size.toByte())
        downstream.writeByte((size ushr 8).toByte())
        val complement = size xor 0xFFFF
        downstream.writeByte(complement.toByte())
        downstream.writeByte((complement ushr 8).toByte())
        downstream.write(block, endIndex = size)
    }

    private fun checkOpen() {
        check(!closed) { "DEFLATE sink is closed" }
    }
}

private data class Lz77Match(
    val length: Int,
    val distance: Int,
) {
    companion object {
        val NONE = Lz77Match(0, 0)
    }
}

private inline fun tokenizeBlock(
    bytes: ByteArray,
    size: Int,
    token: (literal: Int, length: Int, distance: Int) -> Unit,
) {
    val latestHashOffsets = IntArray(LZ77_HASH_SIZE) { -1 }
    var position = 0
    while (position < size) {
        val match = findBlockMatch(
            bytes,
            size,
            position,
            latestHashOffsets,
        )
        if (match.length >= MINIMUM_MATCH_LENGTH) {
            token(-1, match.length, match.distance)
            repeat(match.length) { relative ->
                recordBlockHash(
                    bytes,
                    size,
                    position + relative,
                    latestHashOffsets,
                )
            }
            position += match.length
        } else {
            token(bytes[position].toInt() and 0xFF, 0, 0)
            recordBlockHash(bytes, size, position, latestHashOffsets)
            position++
        }
    }
}

private fun fixedPayloadBitCount(bytes: ByteArray, size: Int): Long {
    var bits = fixedSymbolBitCount(END_OF_BLOCK_SYMBOL).toLong()
    tokenizeBlock(bytes, size) { literal, length, distance ->
        bits += if (literal >= 0) {
            fixedSymbolBitCount(literal).toLong()
        } else {
            val lengthIndex = lengthCodeIndex(length)
            val distanceIndex = distanceCodeIndex(distance)
            fixedSymbolBitCount(257 + lengthIndex).toLong() +
                    LENGTH_EXTRA_BITS[lengthIndex] +
                    5 + DISTANCE_EXTRA_BITS[distanceIndex]
        }
    }
    return bits
}

private fun findBlockMatch(
    bytes: ByteArray,
    size: Int,
    position: Int,
    latestHashOffsets: IntArray,
): Lz77Match {
    if (position > size - MINIMUM_MATCH_LENGTH) return Lz77Match.NONE
    val candidate = latestHashOffsets[blockHash(bytes, position)]
    val distance = position - candidate
    if (
        candidate < 0 ||
        distance !in 1..MAXIMUM_MATCH_DISTANCE
    ) {
        return Lz77Match.NONE
    }
    val maximumLength = minOf(MAXIMUM_MATCH_LENGTH, size - position)
    var length = 0
    while (
        length < maximumLength &&
        bytes[candidate + length] == bytes[position + length]
    ) {
        length++
    }
    return if (length >= MINIMUM_MATCH_LENGTH) {
        Lz77Match(length, distance)
    } else {
        Lz77Match.NONE
    }
}

private fun recordBlockHash(
    bytes: ByteArray,
    size: Int,
    position: Int,
    latestHashOffsets: IntArray,
) {
    if (position <= size - MINIMUM_MATCH_LENGTH) {
        latestHashOffsets[blockHash(bytes, position)] = position
    }
}

private fun blockHash(bytes: ByteArray, offset: Int): Int {
    val value =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                (bytes[offset + 2].toInt() and 0xFF)
    return (value * LZ77_HASH_MULTIPLIER) ushr
            (Int.SIZE_BITS - LZ77_HASH_BITS)
}

private fun fixedSymbolBitCount(symbol: Int): Int = when (symbol) {
    in 0..143 -> 8
    in 144..255 -> 9
    in 256..279 -> 7
    in 280..287 -> 8
    else -> throw RawDeflateException(
        "Invalid fixed DEFLATE symbol $symbol",
    )
}

private fun bytePadding(initialBitOffset: Int, writtenBits: Long): Long {
    val resultingBitOffset =
        ((initialBitOffset + writtenBits) % Byte.SIZE_BITS).toInt()
    return ((Byte.SIZE_BITS - resultingBitOffset) % Byte.SIZE_BITS).toLong()
}

private class DeflateBitWriter(
    private val sink: Sink,
) {
    private var currentByte = 0
    var bitOffset = 0
        private set

    fun writeBits(value: Int, count: Int) {
        require(count in 0..16)
        repeat(count) { bit -> writeBit((value ushr bit) and 1) }
    }

    fun writeFixedSymbol(symbol: Int) {
        when (symbol) {
            in 0..143 -> writeHuffmanCode(0x30 + symbol, 8)
            in 144..255 -> writeHuffmanCode(0x190 + symbol - 144, 9)
            in 256..279 -> writeHuffmanCode(symbol - 256, 7)
            in 280..287 -> writeHuffmanCode(0xC0 + symbol - 280, 8)
            else -> throw RawDeflateException(
                "Invalid fixed DEFLATE symbol $symbol",
            )
        }
    }

    fun writeLength(length: Int) {
        val index = lengthCodeIndex(length)
        writeFixedSymbol(257 + index)
        writeBits(
            length - LENGTH_BASE[index],
            LENGTH_EXTRA_BITS[index],
        )
    }

    fun writeDistance(distance: Int) {
        val index = distanceCodeIndex(distance)
        writeHuffmanCode(index, 5)
        writeBits(
            distance - DISTANCE_BASE[index],
            DISTANCE_EXTRA_BITS[index],
        )
    }

    fun alignToByte() {
        if (bitOffset != 0) sink.writeByte(currentByte.toByte())
        currentByte = 0
        bitOffset = 0
    }

    fun finish() = alignToByte()

    private fun writeHuffmanCode(code: Int, length: Int) {
        for (bit in length - 1 downTo 0) {
            writeBit((code ushr bit) and 1)
        }
    }

    private fun writeBit(value: Int) {
        if (value != 0) currentByte = currentByte or (1 shl bitOffset)
        bitOffset++
        if (bitOffset == Byte.SIZE_BITS) {
            sink.writeByte(currentByte.toByte())
            currentByte = 0
            bitOffset = 0
        }
    }
}

private fun lengthCodeIndex(length: Int): Int =
    if (length == MAXIMUM_MATCH_LENGTH) {
        LENGTH_BASE.lastIndex
    } else {
        LENGTH_BASE.indices.firstOrNull { candidate ->
            val extraBits = LENGTH_EXTRA_BITS[candidate]
            length <= LENGTH_BASE[candidate] + ((1 shl extraBits) - 1)
        }
    } ?: throw RawDeflateException(
        "Invalid DEFLATE match length $length",
    )

private fun distanceCodeIndex(distance: Int): Int =
    DISTANCE_BASE.indices.firstOrNull { candidate ->
        val extraBits = DISTANCE_EXTRA_BITS[candidate]
        distance <= DISTANCE_BASE[candidate] +
                ((1 shl extraBits) - 1)
    } ?: throw RawDeflateException(
        "Invalid DEFLATE match distance $distance",
    )

private class RawDeflateSource(
    source: Source,
    private val maximumOutputBytes: Int,
) : RawSource {
    private val reader = DeflateBitReader(source)
    private val window = ByteArray(MAXIMUM_MATCH_DISTANCE)
    private var block: DecodeBlock? = null
    private var finalBlock = false
    private var pendingMatchLength = 0
    private var pendingMatchDistance = 0
    private var outputSize = 0L
    private var finished = false
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = try {
        readDecodedAtMostTo(sink, byteCount)
    } catch (failure: RawDeflateException) {
        throw failure
    } catch (failure: EOFException) {
        throw RawDeflateException("Truncated DEFLATE stream", failure)
    }

    private fun readDecodedAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "DEFLATE source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (finished) return -1

        var emitted = 0L
        while (emitted < byteCount && !finished) {
            if (pendingMatchLength > 0) {
                val sourceIndex =
                    ((outputSize - pendingMatchDistance) % window.size)
                        .toInt()
                emit(sink, window[sourceIndex])
                pendingMatchLength--
                emitted++
                continue
            }

            when (val active = block ?: beginBlock()) {
                is DecodeBlock.Stored -> {
                    if (active.remaining == 0) {
                        endBlock()
                    } else {
                        emit(sink, reader.readAlignedByte())
                        active.remaining--
                        emitted++
                    }
                }

                is DecodeBlock.Huffman -> {
                    when (val symbol = active.literalLength.decode(reader)) {
                        in 0..255 -> {
                            emit(sink, symbol.toByte())
                            emitted++
                        }

                        END_OF_BLOCK_SYMBOL -> endBlock()
                        in 257..285 -> {
                            val lengthIndex = symbol - 257
                            pendingMatchLength =
                                LENGTH_BASE[lengthIndex] +
                                        reader.readBits(
                                            LENGTH_EXTRA_BITS[lengthIndex],
                                        )
                            val distanceSymbol = active.distance.decode(reader)
                            if (distanceSymbol !in DISTANCE_BASE.indices) {
                                throw RawDeflateException(
                                    "Invalid DEFLATE distance symbol $distanceSymbol",
                                )
                            }
                            pendingMatchDistance =
                                DISTANCE_BASE[distanceSymbol] +
                                        reader.readBits(
                                            DISTANCE_EXTRA_BITS[distanceSymbol],
                                        )
                            if (
                                pendingMatchDistance > outputSize ||
                                pendingMatchDistance > window.size
                            ) {
                                throw RawDeflateException(
                                    "DEFLATE match distance $pendingMatchDistance exceeds output size $outputSize",
                                )
                            }
                        }

                        else -> throw RawDeflateException(
                            "Invalid DEFLATE literal/length symbol $symbol",
                        )
                    }
                }
            }
        }
        return if (emitted == 0L && finished) -1 else emitted
    }

    override fun close() {
        closed = true
    }

    private fun beginBlock(): DecodeBlock {
        finalBlock = reader.readBits(1) != 0
        val decoded = when (val type = reader.readBits(2)) {
            STORED_BLOCK -> {
                reader.alignToByte()
                val length = reader.readUnsignedShortLittleEndian()
                val complement = reader.readUnsignedShortLittleEndian()
                if (length xor 0xFFFF != complement) {
                    throw RawDeflateException(
                        "Stored DEFLATE block length complement is invalid",
                    )
                }
                DecodeBlock.Stored(length)
            }

            FIXED_HUFFMAN_BLOCK -> DecodeBlock.Huffman(
                FIXED_LITERAL_LENGTH_TREE,
                FIXED_DISTANCE_TREE,
            )

            DYNAMIC_HUFFMAN_BLOCK -> readDynamicBlock(reader)
            else -> throw RawDeflateException(
                "Reserved DEFLATE block type $type",
            )
        }
        block = decoded
        return decoded
    }

    private fun endBlock() {
        block = null
        if (finalBlock) {
            reader.alignToByte()
            finished = true
        }
    }

    private fun emit(sink: Buffer, value: Byte) {
        if (outputSize >= maximumOutputBytes) {
            throw RawDeflateException(
                "DEFLATE output exceeds configured limit $maximumOutputBytes",
            )
        }
        sink.writeByte(value)
        window[(outputSize % window.size).toInt()] = value
        outputSize++
    }
}

private sealed interface DecodeBlock {
    class Stored(var remaining: Int) : DecodeBlock

    class Huffman(
        val literalLength: DeflateHuffmanTree,
        val distance: DeflateHuffmanTree,
    ) : DecodeBlock
}

private class DeflateBitReader(
    private val source: Source,
) : DeflateBitInput {
    private var currentByte = 0
    private var bitOffset = Byte.SIZE_BITS

    override fun readBits(count: Int): Int {
        require(count in 0..16)
        var value = 0
        repeat(count) { bit ->
            if (bitOffset == Byte.SIZE_BITS) {
                currentByte = source.readByte().toInt() and 0xFF
                bitOffset = 0
            }
            value = value or
                    (((currentByte ushr bitOffset) and 1) shl bit)
            bitOffset++
        }
        return value
    }

    fun alignToByte() {
        bitOffset = Byte.SIZE_BITS
    }

    fun readUnsignedShortLittleEndian(): Int {
        check(bitOffset == Byte.SIZE_BITS)
        return (source.readByte().toInt() and 0xFF) or
                ((source.readByte().toInt() and 0xFF) shl 8)
    }

    fun readAlignedByte(): Byte {
        check(bitOffset == Byte.SIZE_BITS)
        return source.readByte()
    }
}

private interface DeflateBitInput {
    fun readBits(count: Int): Int
}

private fun readDynamicBlock(reader: DeflateBitInput): DecodeBlock.Huffman {
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
        codeLengthLengths[CODE_LENGTH_ORDER[index]] = reader.readBits(3)
    }
    val codeLengthTree = DeflateHuffmanTree(
        codeLengthLengths,
        "code-length",
        allowSingleSymbolIncomplete = false,
    )

    val expectedCount = literalLengthCount + distanceCount
    val lengths = ArrayList<Int>(expectedCount)
    while (lengths.size < expectedCount) {
        when (val symbol = codeLengthTree.decode(reader)) {
            in 0..15 -> lengths += symbol
            16 -> {
                if (lengths.isEmpty()) {
                    throw RawDeflateException(
                        "DEFLATE repeat code has no previous length",
                    )
                }
                appendRepeatedLength(
                    lengths,
                    expectedCount,
                    lengths.last(),
                    reader.readBits(2) + 3,
                )
            }

            17 -> appendRepeatedLength(
                lengths,
                expectedCount,
                0,
                reader.readBits(3) + 3,
            )

            18 -> appendRepeatedLength(
                lengths,
                expectedCount,
                0,
                reader.readBits(7) + 11,
            )

            else -> throw RawDeflateException(
                "Invalid DEFLATE code-length symbol $symbol",
            )
        }
    }

    val literalLengths = lengths.subList(0, literalLengthCount).toIntArray()
    if (literalLengths[END_OF_BLOCK_SYMBOL] == 0) {
        throw RawDeflateException(
            "Dynamic DEFLATE tree has no end-of-block symbol",
        )
    }
    val distanceLengths =
        lengths.subList(literalLengthCount, expectedCount).toIntArray()
    return DecodeBlock.Huffman(
        DeflateHuffmanTree(literalLengths, "literal/length"),
        DeflateHuffmanTree(
            distanceLengths,
            "distance",
            allowEmpty = true,
        ),
    )
}

private fun appendRepeatedLength(
    lengths: MutableList<Int>,
    expectedCount: Int,
    value: Int,
    count: Int,
) {
    if (count > expectedCount - lengths.size) {
        throw RawDeflateException(
            "DEFLATE code-length repeat exceeds its alphabet",
        )
    }
    repeat(count) { lengths += value }
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
                    put(huffmanKey(length, nextCode[length]++), symbol)
                }
            }
        }
        maximumCodeLength = maximumLength
    }

    fun decode(reader: DeflateBitInput): Int {
        var code = 0
        for (length in 1..maximumCodeLength) {
            code = (code shl 1) or reader.readBits(1)
            symbols[huffmanKey(length, code)]?.let { return it }
        }
        throw RawDeflateException("Invalid DEFLATE Huffman code")
    }
}

private fun huffmanKey(length: Int, code: Int): Int =
    (length shl 16) or code

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
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5,
    11, 4, 12, 3, 13, 2, 14, 1, 15,
)

private val LENGTH_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10,
    11, 13, 15, 17,
    19, 23, 27, 31,
    35, 43, 51, 59,
    67, 83, 99, 115,
    131, 163, 195, 227, 258,
)

private val LENGTH_EXTRA_BITS = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1,
    2, 2, 2, 2,
    3, 3, 3, 3,
    4, 4, 4, 4,
    5, 5, 5, 5, 0,
)

private val DISTANCE_BASE = intArrayOf(
    1, 2, 3, 4,
    5, 7,
    9, 13,
    17, 25,
    33, 49,
    65, 97,
    129, 193,
    257, 385,
    513, 769,
    1_025, 1_537,
    2_049, 3_073,
    4_097, 6_145,
    8_193, 12_289,
    16_385, 24_577,
)

private val DISTANCE_EXTRA_BITS = intArrayOf(
    0, 0, 0, 0,
    1, 1,
    2, 2,
    3, 3,
    4, 4,
    5, 5,
    6, 6,
    7, 7,
    8, 8,
    9, 9,
    10, 10,
    11, 11,
    12, 12,
    13, 13,
)

private const val STORED_BLOCK = 0
private const val FIXED_HUFFMAN_BLOCK = 1
private const val DYNAMIC_HUFFMAN_BLOCK = 2
private const val END_OF_BLOCK_SYMBOL = 256
private const val CODE_LENGTH_ALPHABET_SIZE = 19
private const val MAXIMUM_CODE_BITS = 15
private const val MAXIMUM_LITERAL_LENGTH_CODE_COUNT = 286
private const val MINIMUM_MATCH_LENGTH = 3
private const val MAXIMUM_MATCH_LENGTH = 258
private const val MAXIMUM_MATCH_DISTANCE = 32_768
private const val ENCODE_BLOCK_SIZE = MAXIMUM_MATCH_DISTANCE
private const val LZ77_HASH_BITS = 16
private const val LZ77_HASH_SIZE = 1 shl LZ77_HASH_BITS
private const val LZ77_HASH_MULTIPLIER = 0x1E35_A7BD
