package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftOnlineAccount
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
    val account: MinecraftOnlineAccount,
    val sessionService: MinecraftSessionService,
) : MinecraftClientIdentity {
    override val name: String
        get() = account.name

    override val id: Uuid
        get() = account.id

    override fun toString(): String =
        "MinecraftOnlineIdentity(account=$account)"
}
