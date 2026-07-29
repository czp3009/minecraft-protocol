package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftCryptography
import com.hiczp.minecraft.protocol.auth.MinecraftRsaKeyPair
import com.hiczp.minecraft.protocol.auth.MinecraftSessionService

sealed interface MinecraftServerAuthentication {
    data object Offline : MinecraftServerAuthentication

    class Online(
        val sessionService: MinecraftSessionService,
        val cryptography: MinecraftCryptography,
        val keyPair: MinecraftRsaKeyPair = cryptography.generateRsaKeyPair(),
    ) : MinecraftServerAuthentication
}
