package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftCryptography
import com.hiczp.minecraft.protocol.auth.MinecraftSessionService
import com.hiczp.minecraft.protocol.auth.offlineUuid
import kotlin.uuid.Uuid

sealed interface MinecraftClientIdentity {
    val name: String
    val id: Uuid
}

data class MinecraftOfflineIdentity(
    override val name: String,
    override val id: Uuid = offlineUuid(name),
) : MinecraftClientIdentity

class MinecraftOnlineIdentity(
    override val name: String,
    override val id: Uuid,
    internal val accessToken: String,
    val sessionService: MinecraftSessionService,
    val cryptography: MinecraftCryptography,
) : MinecraftClientIdentity {
    override fun toString(): String =
        "MinecraftOnlineIdentity(name=$name, id=$id, accessToken=<redacted>)"
}
