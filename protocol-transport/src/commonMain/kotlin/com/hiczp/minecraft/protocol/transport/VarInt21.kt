package com.hiczp.minecraft.protocol.transport

import kotlinx.io.EOFException
import kotlinx.io.Sink
import kotlinx.io.Source

internal fun Sink.writeVarInt(value: Int): Int {
    var remaining = value
    var size = 0
    do {
        var current = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining != 0) current = current or 0x80
        writeByte(current.toByte())
        size++
    } while (remaining != 0)
    return size
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

internal data class DecodedVarInt(
    val value: Int,
    val byteCount: Int,
)

internal fun Source.readVarInt(
    maximumBytes: Int = 5,
    rejectNonMinimal: Boolean,
): DecodedVarInt {
    var result = 0
    var shift = 0
    var count = 0
    while (count < maximumBytes) {
        val current = try {
            readByte().toInt() and 0xFF
        } catch (failure: EOFException) {
            throw MinecraftTransportException("Truncated VarInt", failure)
        }
        result = result or ((current and 0x7F) shl shift)
        count++
        if (current and 0x80 == 0) {
            if (rejectNonMinimal && count != varIntSize(result)) {
                throw MinecraftTransportException(
                    "Non-minimal VarInt encoding",
                )
            }
            return DecodedVarInt(result, count)
        }
        shift += 7
    }
    throw MinecraftTransportException(
        "VarInt is wider than $maximumBytes byte(s)",
    )
}
