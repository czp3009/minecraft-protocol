package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.Source
import kotlinx.io.readCodePointValue

/**
 * The selected-release grammar is implemented locally because no maintained
 * Kotlin Multiplatform SNBT parser exposes that grammar over kotlinx-io.
 * kotlinx-io still owns incremental UTF-8 decoding at the stream boundary.
 */
internal class SnbtParser(
    private val input: SnbtInput,
    private val configuration: SnbtFormatConfiguration,
) {
    fun parseFully(): NbtTag {
        val result = parseTag()
        skipWhitespace()
        if (input.peek() != null) fail("Unexpected trailing SNBT data")
        return result
    }

    private fun parseTag(): NbtTag {
        skipWhitespace()
        return when (val next = input.peek()) {
            null -> fail("Expected an SNBT value")
            '{' -> parseCompound()
            '[' -> parseListOrArray()
            '\'', '"' -> NbtString(parseQuotedString())
            else -> if (canStartNumber(next)) parseNumber() else parseUnquotedOrBuiltin()
        }
    }

    private fun parseCompound(): NbtCompound {
        expect('{')
        val entries = linkedMapOf<String, NbtTag>()
        skipWhitespace()
        if (consumeIf('}')) return NbtCompound(entries)

        while (true) {
            val key = parseKey()
            if (key.isEmpty()) fail("SNBT compound keys cannot be empty")
            expect(':')
            entries[key] = parseTag()
            skipWhitespace()
            if (consumeIf('}')) return NbtCompound(entries)
            expect(',')
            skipWhitespace()
            if (consumeIf('}')) return NbtCompound(entries)
        }
    }

    private fun parseKey(): String {
        skipWhitespace()
        return when (input.peek()) {
            '\'', '"' -> parseQuotedString()
            else -> readUnquotedString()
        }
    }

    private fun parseListOrArray(): NbtTag {
        expect('[')
        skipWhitespace()
        if (consumeIf(']')) return NbtList(emptyList())

        val possiblePrefix = input.peek()
        if (possiblePrefix == 'B' || possiblePrefix == 'I' || possiblePrefix == 'L') {
            input.read()
            val skippedWhitespace = skipWhitespace()
            if (consumeIf(';')) return parseArray(possiblePrefix)

            val token = buildString {
                append(possiblePrefix)
                if (!skippedWhitespace) appendUnquotedRemainder(this)
            }
            return parseList(parseUnquotedOrBuiltin(token))
        }

        return parseList(parseTag())
    }

    private fun parseList(first: NbtTag): NbtList {
        val values = mutableListOf(first)
        while (true) {
            skipWhitespace()
            if (consumeIf(']')) return NbtList(values)
            expect(',')
            skipWhitespace()
            if (consumeIf(']')) return NbtList(values)
            values += parseTag()
        }
    }

    private fun parseArray(prefix: Char): NbtTag {
        val values = mutableListOf<SnbtIntegerLiteral>()
        skipWhitespace()
        if (!consumeIf(']')) {
            while (true) {
                values += parseIntegerLiteral()
                skipWhitespace()
                if (consumeIf(']')) break
                expect(',')
                skipWhitespace()
                if (consumeIf(']')) break
            }
        }

        return when (prefix) {
            'B' -> NbtByteArray(
                ByteArray(values.size) { index ->
                    values[index].arrayNumber(SnbtIntegerType.BYTE, this).toByte()
                },
            )

            'I' -> NbtIntArray(
                IntArray(values.size) { index ->
                    values[index].arrayNumber(
                        SnbtIntegerType.INT,
                        this,
                    ).toInt()
                },
            )

            'L' -> NbtLongArray(
                LongArray(values.size) { index ->
                    values[index].arrayNumber(
                        SnbtIntegerType.LONG,
                        this,
                    )
                },
            )

            else -> error("Unreachable SNBT array prefix")
        }
    }

    private fun parseNumber(): NbtTag {
        val text = readNumberText()
        SnbtNumberParser.parseFloat(text)?.let { return it }
        val integer = SnbtNumberParser.parseInteger(text)
            ?: fail("Invalid SNBT number '$text'")
        return integer.toTag(this)
    }

    private fun parseIntegerLiteral(): SnbtIntegerLiteral {
        val text = readNumberText()
        return SnbtNumberParser.parseInteger(text)
            ?: fail("Expected an integer array element, got '$text'")
    }

    private fun readNumberText(): String = buildString {
        while (true) {
            val next = input.peek() ?: break
            if (isNumberTerminator(next)) break
            append(input.read() ?: fail("Unexpected end of SNBT number"))
        }
        if (isEmpty()) fail("Expected an SNBT number")
    }

    private fun parseUnquotedOrBuiltin(): NbtTag =
        parseUnquotedOrBuiltin(readUnquotedString())

    private fun parseUnquotedOrBuiltin(token: String): NbtTag {
        if (token.isEmpty()) fail("Expected an unquoted SNBT string")
        if (canStartNumber(token[0])) fail("Unquoted SNBT strings cannot start with '${token[0]}'")

        skipWhitespace()
        if (consumeIf('(')) {
            val arguments = mutableListOf<NbtTag>()
            skipWhitespace()
            if (!consumeIf(')')) {
                while (true) {
                    arguments += parseTag()
                    skipWhitespace()
                    if (consumeIf(')')) break
                    expect(',')
                    skipWhitespace()
                    if (consumeIf(')')) break
                }
            }
            return applyBuiltin(token, arguments)
        }

        return when {
            token.equals("true", ignoreCase = true) -> NbtByte(1)
            token.equals("false", ignoreCase = true) -> NbtByte(0)
            else -> NbtString(token)
        }
    }

    private fun applyBuiltin(name: String, arguments: List<NbtTag>): NbtTag =
        when {
            name == "bool" && arguments.size == 1 -> parseBoolean(arguments[0])
            name == "uuid" && arguments.size == 1 -> parseUuid(arguments[0])
            else -> fail("No such SNBT operation: $name/${arguments.size}")
        }

    private fun parseBoolean(argument: NbtTag): NbtByte {
        val value = when (argument) {
            is NbtByte -> argument.value.toDouble()
            is NbtShort -> argument.value.toDouble()
            is NbtInt -> argument.value.toDouble()
            is NbtLong -> argument.value.toDouble()
            is NbtFloat -> argument.value.toDouble()
            is NbtDouble -> argument.value
            else -> fail("SNBT bool() requires a number or boolean")
        }
        return NbtByte(if (value != 0.0) 1 else 0)
    }

    private fun parseUuid(argument: NbtTag): NbtIntArray {
        val value = (argument as? NbtString)?.value
            ?: fail("SNBT uuid() requires a string")
        val groups = value.split('-')
        val widths = intArrayOf(8, 4, 4, 4, 12)
        if (groups.size != widths.size) fail("Invalid UUID '$value'")
        val parsed = MutableList(groups.size) { 0uL }
        for (index in groups.indices) {
            val group = groups[index]
            if (group.isEmpty() || group.length > widths[index] || group.any { !it.isHexDigit() }) {
                fail("Invalid UUID '$value'")
            }
            parsed[index] = group.toULong(16)
        }
        val most = (parsed[0] shl 32) or (parsed[1] shl 16) or parsed[2]
        val least = (parsed[3] shl 48) or parsed[4]
        return NbtIntArray(
            intArrayOf(
                (most shr 32).toInt(),
                most.toInt(),
                (least shr 32).toInt(),
                least.toInt(),
            ),
        )
    }

    private fun parseQuotedString(): String {
        skipWhitespace()
        val quote = input.read() ?: fail("Expected a quoted SNBT string")
        val result = StringBuilder()
        while (true) {
            val next = input.read() ?: fail("Unterminated SNBT string")
            when (next) {
                quote -> return result.toString()
                '\\' -> appendEscape(result)
                else -> result.append(next)
            }
        }
    }

    private fun appendEscape(result: StringBuilder) {
        skipWhitespace()
        when (val escape = input.read() ?: fail("Unterminated SNBT escape")) {
            'b' -> result.append('\b')
            's' -> result.append(' ')
            't' -> result.append('\t')
            'n' -> result.append('\n')
            'f' -> result.append('\u000C')
            'r' -> result.append('\r')
            '\\' -> result.append('\\')
            '\'' -> result.append('\'')
            '"' -> result.append('"')
            'x' -> appendCodePoint(result, readHexCodePoint(2))
            'u' -> appendCodePoint(result, readHexCodePoint(4))
            'U' -> appendCodePoint(result, readHexCodePoint(8))
            'N' -> appendUnicodeName(result)
            else -> fail("Unknown SNBT escape '\\$escape'")
        }
    }

    private fun appendUnicodeName(result: StringBuilder) {
        expect('{')
        val name = buildString {
            while (input.peek()?.isUnicodeNameCharacter() == true) {
                append(input.read() ?: fail("Unexpected end of Unicode character name"))
            }
        }
        if (name.isEmpty()) fail("SNBT Unicode character name cannot be empty")
        expect('}')
        val resolver = configuration.unicodeNameResolver
            ?: fail("SNBT \\N{name} escapes require a unicodeNameResolver")
        val codePoint = resolver.resolve(name)
            ?: fail("Unknown Unicode character name '$name'")
        appendCodePoint(result, codePoint)
    }

    private fun readHexCodePoint(digitCount: Int): Int {
        val text = buildString {
            repeat(digitCount) {
                val next = input.read() ?: fail("Expected $digitCount hexadecimal escape digits")
                if (!next.isHexDigit()) fail("Expected $digitCount hexadecimal escape digits")
                append(next)
            }
        }
        val value = text.toUInt(16)
        if (value > MAX_UNICODE_CODE_POINT.toUInt()) {
            fail("Invalid Unicode code point U+${value.toString(16).uppercase()}")
        }
        return value.toInt()
    }

    private fun appendCodePoint(result: StringBuilder, codePoint: Int) {
        if (codePoint !in 0..MAX_UNICODE_CODE_POINT) {
            fail("Invalid Unicode code point U+${codePoint.toString(16).uppercase()}")
        }
        if (codePoint <= Char.MAX_VALUE.code) {
            result.append(codePoint.toChar())
        } else {
            val supplementary = codePoint - MIN_SUPPLEMENTARY_CODE_POINT
            result.append(((supplementary shr 10) + HIGH_SURROGATE_START).toChar())
            result.append(((supplementary and SURROGATE_MASK) + LOW_SURROGATE_START).toChar())
        }
    }

    private fun readUnquotedString(): String = buildString {
        appendUnquotedRemainder(this)
    }

    private fun appendUnquotedRemainder(result: StringBuilder) {
        while (input.peek()?.isAllowedInUnquotedString() == true) {
            result.append(input.read() ?: fail("Unexpected end of unquoted SNBT string"))
        }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        val actual = input.read()
        if (actual != expected) {
            val description = actual?.let { "'$it'" } ?: "end of input"
            fail("Expected '$expected', got $description")
        }
    }

    private fun consumeIf(expected: Char): Boolean {
        if (input.peek() != expected) return false
        input.read()
        return true
    }

    private fun skipWhitespace(): Boolean {
        var skipped = false
        while (input.peek()?.isJavaWhitespace() == true) {
            input.read()
            skipped = true
        }
        return skipped
    }

    internal fun fail(message: String): Nothing =
        throw NbtDecodingException("$message at character ${input.position}")
}

internal interface SnbtInput {
    val position: Long

    fun peek(): Char?

    fun read(): Char?
}

internal class StringSnbtInput(
    private val value: String,
) : SnbtInput {
    private var index = 0

    override val position: Long
        get() = index.toLong()

    override fun peek(): Char? = value.getOrNull(index)

    override fun read(): Char? = value.getOrNull(index)?.also { index++ }
}

internal class SourceSnbtInput(
    private val source: Source,
) : SnbtInput {
    private var cached = UNSET_CHARACTER
    private var pendingLowSurrogate = UNSET_CHARACTER
    private var consumedCharacters = 0L

    override val position: Long
        get() = consumedCharacters

    override fun peek(): Char? {
        if (cached == UNSET_CHARACTER) cached = readCharacterCode()
        return cached.takeUnless { it == END_OF_INPUT }?.toChar()
    }

    override fun read(): Char? {
        val result = peek() ?: return null
        cached = UNSET_CHARACTER
        consumedCharacters++
        return result
    }

    private fun readCharacterCode(): Int {
        if (pendingLowSurrogate != UNSET_CHARACTER) {
            return pendingLowSurrogate.also { pendingLowSurrogate = UNSET_CHARACTER }
        }
        if (source.exhausted()) return END_OF_INPUT
        val codePoint = source.readCodePointValue()
        if (codePoint <= Char.MAX_VALUE.code) return codePoint
        val supplementary = codePoint - MIN_SUPPLEMENTARY_CODE_POINT
        pendingLowSurrogate = (LOW_SURROGATE_START + (supplementary and SURROGATE_MASK))
        return HIGH_SURROGATE_START + (supplementary shr 10)
    }
}

private object SnbtNumberParser {
    fun parseFloat(text: String): NbtTag? {
        parseFloatAlternative(text, FloatAlternative.WHOLE_AND_DOT)?.let { return it }
        parseFloatAlternative(text, FloatAlternative.DOT_AND_FRACTION)?.let { return it }
        parseFloatAlternative(text, FloatAlternative.WHOLE_AND_EXPONENT)?.let { return it }
        return parseFloatAlternative(text, FloatAlternative.WHOLE_AND_SUFFIX)
    }

    fun parseInteger(text: String): SnbtIntegerLiteral? {
        val cursor = NumberCursor(text)
        val negative = cursor.readSign()
        cursor.skipWhitespace()
        val first = cursor.peek() ?: return null
        val base: SnbtIntegerBase
        val digits: String

        if (first == '0') {
            cursor.read()
            val afterZero = cursor.position
            cursor.skipWhitespace()
            when (cursor.peek()) {
                'x', 'X' -> {
                    cursor.read()
                    val parsedDigits = cursor.readNumeral { it.isHexDigit() || it == '_' } ?: return null
                    base = SnbtIntegerBase.HEX
                    digits = parsedDigits
                }

                'b', 'B' -> {
                    cursor.read()
                    val binaryDigits = cursor.readNumeral { it == '0' || it == '1' || it == '_' }
                    if (binaryDigits != null) {
                        base = SnbtIntegerBase.BINARY
                        digits = binaryDigits
                    } else {
                        cursor.position = afterZero
                        base = SnbtIntegerBase.DECIMAL
                        digits = "0"
                    }
                }

                in '0'..'9' -> return null
                else -> {
                    cursor.position = afterZero
                    base = SnbtIntegerBase.DECIMAL
                    digits = "0"
                }
            }
        } else {
            base = SnbtIntegerBase.DECIMAL
            digits = cursor.readNumeral { it in '0'..'9' || it == '_' } ?: return null
        }

        val suffix = cursor.readIntegerSuffix()
        cursor.skipWhitespace()
        if (!cursor.exhausted()) return null
        return SnbtIntegerLiteral(negative, base, digits, suffix)
    }

    private fun parseFloatAlternative(text: String, alternative: FloatAlternative): NbtTag? {
        val cursor = NumberCursor(text)
        val negative = cursor.readSign()
        var whole: String? = null
        var fraction: String? = null
        var exponent: SignedNumeral? = null
        var suffix: Char? = null

        when (alternative) {
            FloatAlternative.WHOLE_AND_DOT -> {
                whole = cursor.readNumeral { it in '0'..'9' || it == '_' } ?: return null
                if (!cursor.consume('.')) return null
                fraction = cursor.readNumeral { it in '0'..'9' || it == '_' }
                exponent = cursor.readExponent()
                suffix = cursor.readFloatSuffix()
            }

            FloatAlternative.DOT_AND_FRACTION -> {
                if (!cursor.consume('.')) return null
                fraction = cursor.readNumeral { it in '0'..'9' || it == '_' } ?: return null
                exponent = cursor.readExponent()
                suffix = cursor.readFloatSuffix()
            }

            FloatAlternative.WHOLE_AND_EXPONENT -> {
                whole = cursor.readNumeral { it in '0'..'9' || it == '_' } ?: return null
                exponent = cursor.readExponent() ?: return null
                suffix = cursor.readFloatSuffix()
            }

            FloatAlternative.WHOLE_AND_SUFFIX -> {
                whole = cursor.readNumeral { it in '0'..'9' || it == '_' } ?: return null
                exponent = cursor.readExponent()
                suffix = cursor.readFloatSuffix() ?: return null
            }
        }

        cursor.skipWhitespace()
        if (!cursor.exhausted()) return null
        val number = buildString {
            if (negative) append('-')
            if (whole != null) append(whole.withoutUnderscores())
            if (fraction != null || alternative == FloatAlternative.WHOLE_AND_DOT) {
                append('.')
                if (fraction != null) append(fraction.withoutUnderscores())
            }
            if (exponent != null) {
                append('e')
                if (exponent.negative) append('-')
                append(exponent.digits.withoutUnderscores())
            }
        }

        return try {
            if (suffix == 'f' || suffix == 'F') {
                val value = number.toFloat()
                if (value.isFinite()) NbtFloat(value) else null
            } else {
                val value = number.toDouble()
                if (value.isFinite()) NbtDouble(value) else null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private data class SnbtIntegerLiteral(
    val negative: Boolean,
    val base: SnbtIntegerBase,
    val digits: String,
    val suffix: SnbtIntegerSuffix,
) {
    fun toTag(parser: SnbtParser): NbtTag {
        val type = suffix.type ?: SnbtIntegerType.INT
        val number = number(type, parser)
        return when (type) {
            SnbtIntegerType.BYTE -> NbtByte(number.toByte())
            SnbtIntegerType.SHORT -> NbtShort(number.toShort())
            SnbtIntegerType.INT -> NbtInt(number.toInt())
            SnbtIntegerType.LONG -> NbtLong(number)
        }
    }

    fun arrayNumber(
        defaultType: SnbtIntegerType,
        parser: SnbtParser,
    ): Long {
        val type = suffix.type ?: defaultType
        if (type.ordinal > defaultType.ordinal) {
            parser.fail("Invalid ${defaultType.name.lowercase()} array element type")
        }
        return number(type, parser)
    }

    private fun number(type: SnbtIntegerType, parser: SnbtParser): Long {
        val signed = suffix.signed ?: (base == SnbtIntegerBase.DECIMAL)
        if (!signed && negative) parser.fail("Unsigned SNBT integers cannot be negative")
        val radix = when (base) {
            SnbtIntegerBase.BINARY -> 2
            SnbtIntegerBase.DECIMAL -> 10
            SnbtIntegerBase.HEX -> 16
        }
        val cleanDigits = digits.withoutUnderscores()
        return try {
            if (signed) {
                val text = if (negative) "-$cleanDigits" else cleanDigits
                when (type) {
                    SnbtIntegerType.BYTE -> text.toByte(radix).toLong()
                    SnbtIntegerType.SHORT -> text.toShort(radix).toLong()
                    SnbtIntegerType.INT -> text.toInt(radix).toLong()
                    SnbtIntegerType.LONG -> text.toLong(radix)
                }
            } else {
                when (type) {
                    SnbtIntegerType.BYTE -> cleanDigits.toUByte(radix).toByte().toLong()
                    SnbtIntegerType.SHORT -> cleanDigits.toUShort(radix).toShort().toLong()
                    SnbtIntegerType.INT -> cleanDigits.toUInt(radix).toInt().toLong()
                    SnbtIntegerType.LONG -> cleanDigits.toULong(radix).toLong()
                }
            }
        } catch (_: IllegalArgumentException) {
            parser.fail("SNBT integer '$digits' is out of range for ${type.name.lowercase()}")
        }
    }
}

private data class SnbtIntegerSuffix(
    val signed: Boolean?,
    val type: SnbtIntegerType?,
) {
    companion object {
        val NONE = SnbtIntegerSuffix(null, null)
    }
}

private class NumberCursor(
    private val text: String,
) {
    var position: Int = 0

    fun exhausted(): Boolean = position == text.length

    fun peek(): Char? = text.getOrNull(position)

    fun read(): Char? = text.getOrNull(position)?.also { position++ }

    fun skipWhitespace() {
        while (peek()?.isJavaWhitespace() == true) position++
    }

    fun readSign(): Boolean {
        skipWhitespace()
        return when (peek()) {
            '+' -> {
                position++
                false
            }

            '-' -> {
                position++
                true
            }

            else -> false
        }
    }

    fun consume(expected: Char): Boolean {
        skipWhitespace()
        if (peek() != expected) return false
        position++
        return true
    }

    fun readNumeral(accepted: (Char) -> Boolean): String? {
        skipWhitespace()
        val start = position
        while (peek()?.let(accepted) == true) position++
        if (start == position) return null
        val result = text.substring(start, position)
        if (result.first() == '_' || result.last() == '_') {
            position = start
            return null
        }
        return result
    }

    fun readExponent(): SignedNumeral? {
        val start = position
        skipWhitespace()
        if (peek() != 'e' && peek() != 'E') {
            position = start
            return null
        }
        position++
        val negative = readSign()
        val digits = readNumeral { it in '0'..'9' || it == '_' }
        if (digits == null) {
            position = start
            return null
        }
        return SignedNumeral(negative, digits)
    }

    fun readFloatSuffix(): Char? {
        val start = position
        skipWhitespace()
        val result = peek()
        if (result != 'f' && result != 'F' && result != 'd' && result != 'D') {
            position = start
            return null
        }
        position++
        return result
    }

    fun readIntegerSuffix(): SnbtIntegerSuffix {
        val start = position
        skipWhitespace()
        val first = peek()?.lowercaseChar() ?: run {
            position = start
            return SnbtIntegerSuffix.NONE
        }
        if (first == 'u' || first == 's') {
            position++
            val type = readIntegerTypeCharacter()
            if (type != null) return SnbtIntegerSuffix(first == 's', type)
            position = start
            if (first == 'u') return SnbtIntegerSuffix.NONE
        }

        val type = readIntegerTypeCharacter()
        if (type != null) return SnbtIntegerSuffix(null, type)
        position = start
        return SnbtIntegerSuffix.NONE
    }

    private fun readIntegerTypeCharacter(): SnbtIntegerType? {
        skipWhitespace()
        val type = when (peek()?.lowercaseChar()) {
            'b' -> SnbtIntegerType.BYTE
            's' -> SnbtIntegerType.SHORT
            'i' -> SnbtIntegerType.INT
            'l' -> SnbtIntegerType.LONG
            else -> return null
        }
        position++
        return type
    }
}

private data class SignedNumeral(
    val negative: Boolean,
    val digits: String,
)

private enum class FloatAlternative {
    WHOLE_AND_DOT,
    DOT_AND_FRACTION,
    WHOLE_AND_EXPONENT,
    WHOLE_AND_SUFFIX,
}

private enum class SnbtIntegerBase {
    BINARY,
    DECIMAL,
    HEX,
}

private enum class SnbtIntegerType {
    BYTE,
    SHORT,
    INT,
    LONG,
}

private fun String.withoutUnderscores(): String =
    if ('_' in this) filter { it != '_' } else this

private fun Char.isAllowedInUnquotedString(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z' || this == '_' || this == '-' || this == '.' || this == '+'

private fun Char.isUnicodeNameCharacter(): Boolean =
    this == '-' || this == ' ' || this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

private fun Char.isJavaWhitespace(): Boolean =
    this in '\u0009'..'\u000D' ||
            this in '\u001C'..'\u001F' ||
            this == ' ' ||
            this == '\u1680' ||
            this in '\u2000'..'\u2006' ||
            this in '\u2008'..'\u200A' ||
            this == '\u2028' ||
            this == '\u2029' ||
            this == '\u205F' ||
            this == '\u3000'

private fun canStartNumber(value: Char): Boolean =
    value == '+' || value == '-' || value == '.' || value in '0'..'9'

private fun isNumberTerminator(value: Char): Boolean =
    value == ',' || value == ']' || value == '}' || value == ')' || value == ':' || value == ';' ||
            value == '[' || value == '{' || value == '(' || value == '\'' || value == '"'

private const val MAX_UNICODE_CODE_POINT = 0x10FFFF
private const val MIN_SUPPLEMENTARY_CODE_POINT = 0x10000
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
private const val SURROGATE_MASK = 0x3FF
private const val UNSET_CHARACTER = -2
private const val END_OF_INPUT = -1
