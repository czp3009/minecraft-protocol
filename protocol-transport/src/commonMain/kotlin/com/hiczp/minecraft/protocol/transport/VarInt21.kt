package com.hiczp.minecraft.protocol.transport

internal fun encodeVarInt(value: Int): ByteArray {
    var remaining = value
    val output = ByteArray(5)
    var size = 0
    do {
        var current = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining != 0) current = current or 0x80
        output[size++] = current.toByte()
    } while (remaining != 0)
    return output.copyOf(size)
}

internal fun varIntSize(value: Int): Int {
    var remaining = value
    var size = 1
    while (remaining and 0x7F.inv() != 0) {
        remaining = remaining ushr 7
        size++
    }
    return size
}

internal class ByteCursor(
    private val bytes: ByteArray,
) {
    var position: Int = 0
        private set

    val remaining: Int
        get() = bytes.size - position

    fun readVarInt(
        maximumBytes: Int = 5,
        rejectNonMinimal: Boolean,
    ): Int {
        var result = 0
        var shift = 0
        var count = 0
        while (count < maximumBytes) {
            if (position >= bytes.size) {
                throw MinecraftTransportException("Truncated VarInt")
            }
            val current = bytes[position++].toInt() and 0xFF
            result = result or ((current and 0x7F) shl shift)
            count++
            if (current and 0x80 == 0) {
                if (rejectNonMinimal && count != varIntSize(result)) {
                    throw MinecraftTransportException("Non-minimal VarInt encoding")
                }
                return result
            }
            shift += 7
        }
        throw MinecraftTransportException(
            "VarInt is wider than $maximumBytes byte(s)",
        )
    }

    fun remainingBytes(): ByteArray = bytes.copyOfRange(position, bytes.size)
}
