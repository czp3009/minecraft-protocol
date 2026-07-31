package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.compression.RawDeflate
import com.hiczp.minecraft.compression.RawDeflateException

interface RegionCompressionCodec {
    suspend fun compress(input: ByteArray): ByteArray

    suspend fun decompress(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray
}

/**
 * Compression registry for region chunks.
 *
 * Built-in vanilla codecs are available automatically. Overrides primarily
 * exist for ID 127 custom compression, but may replace any implementation.
 */
sealed class RegionCompressionCodecs(
    private val overrides: Map<RegionCompression, RegionCompressionCodec>,
) {
    companion object Default : RegionCompressionCodecs(emptyMap()) {
        operator fun invoke(
            overrides: Map<RegionCompression, RegionCompressionCodec> =
                emptyMap(),
        ): RegionCompressionCodecs =
            ConfiguredRegionCompressionCodecs(overrides.toMap())
    }

    suspend fun compress(
        compression: RegionCompression,
        input: ByteArray,
    ): ByteArray = codec(compression).compress(input)

    suspend fun decompress(
        compression: RegionCompression,
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        require(maximumOutputBytes >= 0)
        val output = codec(compression).decompress(
            input,
            maximumOutputBytes,
        )
        if (output.size > maximumOutputBytes) {
            throw RegionFormatException(
                "Decompressed chunk size ${output.size} exceeds configured " +
                        "limit $maximumOutputBytes",
            )
        }
        return output
    }

    private fun codec(compression: RegionCompression): RegionCompressionCodec =
        overrides[compression] ?: when (compression) {
            RegionCompression.GZIP -> GzipCodec
            RegionCompression.ZLIB -> ZlibCodec
            RegionCompression.NONE -> NoneCodec
            RegionCompression.LZ4 -> Lz4BlockCodec
            RegionCompression.CUSTOM -> throw RegionFormatException(
                "Custom region compression requires a registered codec",
            )
        }
}

private class ConfiguredRegionCompressionCodecs(
    overrides: Map<RegionCompression, RegionCompressionCodec>,
) : RegionCompressionCodecs(overrides)

private object NoneCodec : RegionCompressionCodec {
    override suspend fun compress(input: ByteArray): ByteArray = input.copyOf()

    override suspend fun decompress(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        if (input.size > maximumOutputBytes) {
            throw RegionFormatException(
                "Uncompressed chunk size ${input.size} exceeds configured " +
                        "limit $maximumOutputBytes",
            )
        }
        return input.copyOf()
    }
}

private object ZlibCodec : RegionCompressionCodec {
    override suspend fun compress(input: ByteArray): ByteArray {
        val rawDeflate = compressRawDeflate(input)
        val output = ByteArray(rawDeflate.size + 6)
        output[0] = 0x78
        output[1] = 0x9C.toByte()
        rawDeflate.copyInto(output, destinationOffset = 2)
        writeIntBigEndian(output, output.size - 4, adler32(input))
        return output
    }

    override suspend fun decompress(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        if (input.size < 6) {
            throw RegionFormatException("Truncated zlib stream")
        }
        val methodAndInfo = input[0].toInt() and 0xFF
        val flags = input[1].toInt() and 0xFF
        if (methodAndInfo and 0x0F != DEFLATE_METHOD) {
            throw RegionFormatException("Unsupported zlib compression method")
        }
        if (methodAndInfo ushr 4 > 7) {
            throw RegionFormatException("Invalid zlib window size")
        }
        if ((methodAndInfo shl 8 or flags) % 31 != 0) {
            throw RegionFormatException("Invalid zlib header checksum")
        }
        if (flags and ZLIB_PRESET_DICTIONARY != 0) {
            throw RegionFormatException(
                "Preset zlib dictionaries are not supported by vanilla regions",
            )
        }

        val output = decompressRawDeflate(
            input.copyOfRange(2, input.size - 4),
            maximumOutputBytes,
        )
        val expected = readIntBigEndian(input, input.size - 4)
        if (adler32(output) != expected) {
            throw RegionFormatException("Invalid zlib Adler-32 checksum")
        }
        return output
    }
}

private object GzipCodec : RegionCompressionCodec {
    override suspend fun compress(input: ByteArray): ByteArray {
        val deflated = compressRawDeflate(input)
        val output = ByteArray(GZIP_FIXED_HEADER.size + deflated.size + 8)
        GZIP_FIXED_HEADER.copyInto(output)
        deflated.copyInto(output, destinationOffset = GZIP_FIXED_HEADER.size)
        writeIntLittleEndian(output, output.size - 8, crc32(input))
        writeIntLittleEndian(output, output.size - 4, input.size)
        return output
    }

    override suspend fun decompress(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        if (input.size < GZIP_FIXED_HEADER.size + 8) {
            throw RegionFormatException("Truncated gzip stream")
        }
        if (
            input[0].toInt() and 0xFF != 0x1F ||
            input[1].toInt() and 0xFF != 0x8B
        ) {
            throw RegionFormatException("Invalid gzip magic")
        }
        if (input[2].toInt() and 0xFF != DEFLATE_METHOD) {
            throw RegionFormatException("Unsupported gzip compression method")
        }
        val flags = input[3].toInt() and 0xFF
        if (flags and GZIP_RESERVED_FLAGS != 0) {
            throw RegionFormatException("Invalid reserved gzip flags")
        }

        var offset = GZIP_FIXED_HEADER.size
        if (flags and GZIP_EXTRA != 0) {
            requireRange(input, offset, 2, "gzip extra-field length")
            val length = readUnsignedShortLittleEndian(input, offset)
            offset += 2
            requireRange(input, offset, length, "gzip extra field")
            offset += length
        }
        if (flags and GZIP_NAME != 0) {
            offset = skipZeroTerminated(input, offset, "gzip file name")
        }
        if (flags and GZIP_COMMENT != 0) {
            offset = skipZeroTerminated(input, offset, "gzip comment")
        }
        if (flags and GZIP_HEADER_CRC != 0) {
            requireRange(input, offset, 2, "gzip header CRC")
            val expected = readUnsignedShortLittleEndian(input, offset)
            val actual = crc32(input, endIndex = offset) and 0xFFFF
            if (actual != expected) {
                throw RegionFormatException("Invalid gzip header CRC")
            }
            offset += 2
        }
        if (offset > input.size - 8) {
            throw RegionFormatException("Truncated gzip body")
        }

        val output = decompressRawDeflate(
            input.copyOfRange(offset, input.size - 8),
            maximumOutputBytes,
        )
        val expectedCrc = readIntLittleEndian(input, input.size - 8)
        if (crc32(output) != expectedCrc) {
            throw RegionFormatException("Invalid gzip CRC-32 checksum")
        }
        val expectedSize = readIntLittleEndian(input, input.size - 4)
        if (output.size != expectedSize) {
            throw RegionFormatException("Invalid gzip uncompressed size")
        }
        return output
    }
}

/**
 * Vanilla uses lz4-java's legacy `LZ4Block` stream, not the standard LZ4 frame
 * format. Encoding raw blocks is valid for that stream and deterministic;
 * decoding accepts both raw and LZ4-compressed blocks.
 */
private object Lz4BlockCodec : RegionCompressionCodec {
    override suspend fun compress(input: ByteArray): ByteArray {
        val output = ByteArrayAccumulator()
        var offset = 0
        while (offset < input.size) {
            val length = minOf(LZ4_BLOCK_SIZE, input.size - offset)
            output.write(LZ4_MAGIC)
            output.writeByte(LZ4_RAW_METHOD or LZ4_COMPRESSION_LEVEL)
            output.writeIntLittleEndian(length)
            output.writeIntLittleEndian(length)
            output.writeIntLittleEndian(
                xxHash32(input, offset, length, LZ4_XXHASH_SEED) and
                        LZ4_CHECKSUM_MASK,
            )
            output.write(input, offset, length)
            offset += length
        }
        output.write(LZ4_MAGIC)
        output.writeByte(LZ4_RAW_METHOD or LZ4_COMPRESSION_LEVEL)
        output.writeIntLittleEndian(0)
        output.writeIntLittleEndian(0)
        output.writeIntLittleEndian(0)
        return output.toByteArray()
    }

    override suspend fun decompress(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        val output = ByteArrayAccumulator()
        var offset = 0
        while (true) {
            requireRange(input, offset, LZ4_HEADER_LENGTH, "LZ4 block header")
            if (!input.regionMatches(offset, LZ4_MAGIC)) {
                throw RegionFormatException("Invalid LZ4Block magic")
            }
            val token = input[offset + LZ4_MAGIC.size].toInt() and 0xFF
            val method = token and 0xF0
            val compressionLevel = 10 + (token and 0x0F)
            if (method != LZ4_RAW_METHOD && method != LZ4_METHOD) {
                throw RegionFormatException("Invalid LZ4Block method")
            }
            val compressedLength =
                readIntLittleEndian(input, offset + LZ4_MAGIC.size + 1)
            val originalLength =
                readIntLittleEndian(input, offset + LZ4_MAGIC.size + 5)
            val expectedChecksum =
                readIntLittleEndian(input, offset + LZ4_MAGIC.size + 9)
            offset += LZ4_HEADER_LENGTH

            if (
                compressedLength < 0 ||
                originalLength < 0 ||
                originalLength > 1 shl compressionLevel ||
                (originalLength == 0) != (compressedLength == 0) ||
                (method == LZ4_RAW_METHOD &&
                        originalLength != compressedLength)
            ) {
                throw RegionFormatException("Invalid LZ4Block lengths")
            }
            if (originalLength == 0) {
                if (expectedChecksum != 0) {
                    throw RegionFormatException(
                        "Invalid LZ4Block end checksum",
                    )
                }
                if (offset != input.size) {
                    throw RegionFormatException(
                        "Trailing bytes after LZ4Block end marker",
                    )
                }
                return output.toByteArray()
            }
            if (output.size > maximumOutputBytes - originalLength) {
                throw RegionFormatException(
                    "LZ4 output exceeds configured limit $maximumOutputBytes",
                )
            }
            requireRange(
                input,
                offset,
                compressedLength,
                "LZ4 block payload",
            )
            val decoded = if (method == LZ4_RAW_METHOD) {
                input.copyOfRange(offset, offset + compressedLength)
            } else {
                decodeLz4Block(
                    input,
                    offset,
                    compressedLength,
                    originalLength,
                )
            }
            if (
                (xxHash32(decoded, 0, decoded.size, LZ4_XXHASH_SEED) and
                        LZ4_CHECKSUM_MASK) !=
                expectedChecksum
            ) {
                throw RegionFormatException("Invalid LZ4Block XXHash-32")
            }
            output.write(decoded)
            offset += compressedLength
        }
    }
}

private fun compressRawDeflate(input: ByteArray): ByteArray =
    try {
        RawDeflate.encode(input)
    } catch (failure: RawDeflateException) {
        throw RegionFormatException("Cannot deflate region chunk", failure)
    }

private fun decompressRawDeflate(
    input: ByteArray,
    maximumOutputBytes: Int,
): ByteArray =
    try {
        RawDeflate.decode(input, maximumOutputBytes)
    } catch (failure: RawDeflateException) {
        throw RegionFormatException("Invalid DEFLATE stream", failure)
    }

private fun decodeLz4Block(
    input: ByteArray,
    inputOffset: Int,
    inputLength: Int,
    outputLength: Int,
): ByteArray {
    val output = ByteArray(outputLength)
    val inputEnd = inputOffset + inputLength
    var source = inputOffset
    var destination = 0

    while (source < inputEnd) {
        val token = input[source++].toInt() and 0xFF
        var literalLength = token ushr 4
        if (literalLength == 15) {
            var extension: Int
            do {
                if (source >= inputEnd) {
                    throw RegionFormatException("Truncated LZ4 literal length")
                }
                extension = input[source++].toInt() and 0xFF
                literalLength += extension
            } while (extension == 255)
        }
        if (
            literalLength > inputEnd - source ||
            literalLength > outputLength - destination
        ) {
            throw RegionFormatException("Invalid LZ4 literal range")
        }
        input.copyInto(
            output,
            destinationOffset = destination,
            startIndex = source,
            endIndex = source + literalLength,
        )
        source += literalLength
        destination += literalLength
        if (source == inputEnd) break

        if (source > inputEnd - 2) {
            throw RegionFormatException("Truncated LZ4 match offset")
        }
        val matchOffset =
            (input[source].toInt() and 0xFF) or
                    ((input[source + 1].toInt() and 0xFF) shl 8)
        source += 2
        if (matchOffset == 0 || matchOffset > destination) {
            throw RegionFormatException("Invalid LZ4 match offset")
        }

        var matchLength = token and 0x0F
        if (matchLength == 15) {
            var extension: Int
            do {
                if (source >= inputEnd) {
                    throw RegionFormatException("Truncated LZ4 match length")
                }
                extension = input[source++].toInt() and 0xFF
                matchLength += extension
            } while (extension == 255)
        }
        matchLength += 4
        if (matchLength > outputLength - destination) {
            throw RegionFormatException("LZ4 match exceeds output block")
        }
        var matchSource = destination - matchOffset
        repeat(matchLength) {
            output[destination++] = output[matchSource++]
        }
    }
    if (source != inputEnd || destination != outputLength) {
        throw RegionFormatException("LZ4 block length mismatch")
    }
    return output
}

private fun adler32(bytes: ByteArray): Int {
    var first = 1
    var second = 0
    bytes.forEach { byte ->
        first = (first + (byte.toInt() and 0xFF)) % ADLER_MODULUS
        second = (second + first) % ADLER_MODULUS
    }
    return second shl 16 or first
}

private fun crc32(
    bytes: ByteArray,
    startIndex: Int = 0,
    endIndex: Int = bytes.size,
): Int {
    var crc = -1
    for (index in startIndex until endIndex) {
        crc = crc xor (bytes[index].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) {
                (crc ushr 1) xor CRC32_POLYNOMIAL
            } else {
                crc ushr 1
            }
        }
    }
    return crc.inv()
}

private fun xxHash32(
    bytes: ByteArray,
    startIndex: Int,
    length: Int,
    seed: Int,
): Int {
    var offset = startIndex
    val end = startIndex + length
    var hash: Int
    if (length >= 16) {
        var first = seed + XXHASH_PRIME_1 + XXHASH_PRIME_2
        var second = seed + XXHASH_PRIME_2
        var third = seed
        var fourth = seed - XXHASH_PRIME_1
        val limit = end - 16
        while (offset <= limit) {
            first = xxHashRound(first, readIntLittleEndian(bytes, offset))
            second = xxHashRound(second, readIntLittleEndian(bytes, offset + 4))
            third = xxHashRound(third, readIntLittleEndian(bytes, offset + 8))
            fourth = xxHashRound(fourth, readIntLittleEndian(bytes, offset + 12))
            offset += 16
        }
        hash =
            first.rotateLeft(1) +
                    second.rotateLeft(7) +
                    third.rotateLeft(12) +
                    fourth.rotateLeft(18)
    } else {
        hash = seed + XXHASH_PRIME_5
    }
    hash += length
    while (offset <= end - 4) {
        hash += readIntLittleEndian(bytes, offset) * XXHASH_PRIME_3
        hash = hash.rotateLeft(17) * XXHASH_PRIME_4
        offset += 4
    }
    while (offset < end) {
        hash += (bytes[offset].toInt() and 0xFF) * XXHASH_PRIME_5
        hash = hash.rotateLeft(11) * XXHASH_PRIME_1
        offset++
    }
    hash = (hash xor (hash ushr 15)) * XXHASH_PRIME_2
    hash = (hash xor (hash ushr 13)) * XXHASH_PRIME_3
    return hash xor (hash ushr 16)
}

private fun xxHashRound(accumulator: Int, value: Int): Int =
    (accumulator + value * XXHASH_PRIME_2)
        .rotateLeft(13) * XXHASH_PRIME_1

private fun ByteArray.regionMatches(
    offset: Int,
    expected: ByteArray,
): Boolean =
    offset in 0..size - expected.size &&
            expected.indices.all { this[offset + it] == expected[it] }

private fun requireRange(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    kind: String,
) {
    if (offset < 0 || length < 0 || offset > bytes.size - length) {
        throw RegionFormatException("Truncated $kind")
    }
}

private fun skipZeroTerminated(
    bytes: ByteArray,
    startIndex: Int,
    kind: String,
): Int {
    for (index in startIndex until bytes.size - 8) {
        if (bytes[index] == 0.toByte()) return index + 1
    }
    throw RegionFormatException("Unterminated $kind")
}

private fun readUnsignedShortLittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

private fun writeIntLittleEndian(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

private fun writeIntBigEndian(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private class ByteArrayAccumulator {
    private var bytes = ByteArray(8_192)
    var size: Int = 0
        private set

    fun writeByte(value: Int) {
        ensureCapacity(size + 1)
        bytes[size++] = value.toByte()
    }

    fun writeIntLittleEndian(value: Int) {
        ensureCapacity(size + 4)
        writeIntLittleEndian(bytes, size, value)
        size += 4
    }

    fun write(value: ByteArray) = write(value, 0, value.size)

    fun write(value: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= value.size - length)
        ensureCapacity(size + length)
        value.copyInto(
            bytes,
            destinationOffset = size,
            startIndex = offset,
            endIndex = offset + length,
        )
        size += length
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) return
        var capacity = bytes.size
        while (capacity < required) {
            capacity = (capacity * 2).coerceAtLeast(required)
        }
        bytes = bytes.copyOf(capacity)
    }
}

private val GZIP_FIXED_HEADER = byteArrayOf(
    0x1F,
    0x8B.toByte(),
    DEFLATE_METHOD.toByte(),
    0,
    0,
    0,
    0,
    0,
    0,
    0xFF.toByte(),
)
private val LZ4_MAGIC = byteArrayOf(
    'L'.code.toByte(),
    'Z'.code.toByte(),
    '4'.code.toByte(),
    'B'.code.toByte(),
    'l'.code.toByte(),
    'o'.code.toByte(),
    'c'.code.toByte(),
    'k'.code.toByte(),
)

private const val DEFLATE_METHOD = 8
private const val ZLIB_PRESET_DICTIONARY = 0x20
private const val ADLER_MODULUS = 65_521
private const val GZIP_HEADER_CRC = 0x02
private const val GZIP_EXTRA = 0x04
private const val GZIP_NAME = 0x08
private const val GZIP_COMMENT = 0x10
private const val GZIP_RESERVED_FLAGS = 0xE0
private const val CRC32_POLYNOMIAL = 0xEDB88320.toInt()
private const val LZ4_HEADER_LENGTH = 21
private const val LZ4_BLOCK_SIZE = 1 shl 16
private const val LZ4_COMPRESSION_LEVEL = 6
private const val LZ4_RAW_METHOD = 0x10
private const val LZ4_METHOD = 0x20
private const val LZ4_XXHASH_SEED = 0x9747B28C.toInt()

// lz4-java's LZ4Block stream obtains the hash through asChecksum(), whose
// wire-compatible value is masked to 28 bits.
private const val LZ4_CHECKSUM_MASK = 0x0FFF_FFFF
private const val XXHASH_PRIME_1 = 0x9E3779B1.toInt()
private const val XXHASH_PRIME_2 = 0x85EBCA77.toInt()
private const val XXHASH_PRIME_3 = 0xC2B2AE3D.toInt()
private const val XXHASH_PRIME_4 = 0x27D4EB2F
private const val XXHASH_PRIME_5 = 0x165667B1
