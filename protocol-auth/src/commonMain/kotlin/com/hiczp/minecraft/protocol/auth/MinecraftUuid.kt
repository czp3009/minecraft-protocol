package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import okio.ByteString.Companion.toByteString
import kotlin.uuid.Uuid

fun offlineUuid(playerName: String): Uuid {
    require(playerName.isNotEmpty()) { "Player name cannot be empty" }
    val bytes = "OfflinePlayer:$playerName"
        .encodeToByteArray()
        .toByteString()
        .md5()
        .toByteArray()
    // Okio owns the MD5 operation. These two masks are the RFC 4122 version-3
    // and variant bits applied by Java's nameUUIDFromBytes, which vanilla uses.
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x30).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    return Uuid.fromByteArray(bytes)
}

fun offlineProfile(playerName: String): GameProfile =
    GameProfile(
        id = offlineUuid(playerName),
        name = playerName,
        properties = emptyList(),
    )

fun parseMinecraftUuid(value: String): Uuid = Uuid.parse(value)

fun Uuid.toUndashedString(): String = toHexString()

fun Uuid.toDashedString(): String = toHexDashString()
