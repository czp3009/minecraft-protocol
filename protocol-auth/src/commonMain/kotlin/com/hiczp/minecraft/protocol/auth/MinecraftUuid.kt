package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.Uuid

fun offlineUuid(playerName: String): Uuid {
    require(playerName.isNotEmpty()) { "Player name cannot be empty" }
    val bytes = Md5.digest(("OfflinePlayer:" + playerName).encodeToByteArray())
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x30).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    return uuidFromBytes(bytes)
}

fun offlineProfile(playerName: String): GameProfile =
    GameProfile(
        id = offlineUuid(playerName),
        name = playerName,
        properties = emptyList(),
    )

fun parseMinecraftUuid(value: String): Uuid {
    val normalized = when (value.length) {
        32 -> value
        36 -> {
            require(
                value[8] == '-' &&
                        value[13] == '-' &&
                        value[18] == '-' &&
                        value[23] == '-',
            ) {
                "Dashed Minecraft UUID must use the 8-4-4-4-12 form"
            }
            buildString(32) {
                value.forEachIndexed { index, character ->
                    if (index !in DASH_POSITIONS) append(character)
                }
            }
        }

        else -> throw IllegalArgumentException(
            "Minecraft UUID must be 32 hexadecimal digits, with optional canonical dashes",
        )
    }
    require(normalized.all(Char::isHexDigit)) {
        "Minecraft UUID must contain only hexadecimal digits"
    }
    return uuidFromBytes(
        ByteArray(16) { index ->
            normalized.substring(index * 2, index * 2 + 2)
                .toInt(16)
                .toByte()
        },
    )
}

fun Uuid.toUndashedString(): String =
    toBytes().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

fun Uuid.toDashedString(): String {
    val value = toUndashedString()
    return buildString(36) {
        append(value, 0, 8)
        append('-')
        append(value, 8, 12)
        append('-')
        append(value, 12, 16)
        append('-')
        append(value, 16, 20)
        append('-')
        append(value, 20, 32)
    }
}

private fun uuidFromBytes(bytes: ByteArray): Uuid {
    require(bytes.size == 16)
    var mostSignificant = 0L
    var leastSignificant = 0L
    for (index in 0 until 8) {
        mostSignificant =
            (mostSignificant shl 8) or (bytes[index].toLong() and 0xFF)
        leastSignificant =
            (leastSignificant shl 8) or (bytes[index + 8].toLong() and 0xFF)
    }
    return Uuid(mostSignificant, leastSignificant)
}

private fun Uuid.toBytes(): ByteArray {
    val bytes = ByteArray(16)
    for (index in 0 until 8) {
        bytes[index] =
            (mostSignificantBits ushr ((7 - index) * 8)).toByte()
        bytes[index + 8] =
            (leastSignificantBits ushr ((7 - index) * 8)).toByte()
    }
    return bytes
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private val DASH_POSITIONS = setOf(8, 13, 18, 23)
