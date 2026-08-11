package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftEncryption
import com.hiczp.minecraft.protocol.auth.MinecraftServerEncryptionContext
import com.hiczp.minecraft.protocol.auth.MinecraftSessionService

sealed interface MinecraftServerAuthentication {
    data object Offline : MinecraftServerAuthentication

    class Online internal constructor(
        val sessionService: MinecraftSessionService,
        internal val encryptionContext: MinecraftServerEncryptionContext,
    ) : MinecraftServerAuthentication

    companion object {
        suspend fun online(
            sessionService: MinecraftSessionService,
        ): Online = Online(
            sessionService = sessionService,
            encryptionContext = MinecraftEncryption.createServerContext(),
        )
    }
}
