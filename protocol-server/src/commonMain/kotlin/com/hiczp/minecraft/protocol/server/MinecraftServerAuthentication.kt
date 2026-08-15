package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftServerKeyPair
import io.ktor.client.*

sealed interface MinecraftServerAuthentication {
    data object Offline : MinecraftServerAuthentication

    data class Online(
        val sessionHttpClient: HttpClient,
        val keyPair: MinecraftServerKeyPair,
    ) : MinecraftServerAuthentication

    companion object {
        suspend fun online(
            sessionHttpClient: HttpClient,
        ): Online = Online(
            sessionHttpClient = sessionHttpClient,
            keyPair = MinecraftServerKeyPair.generate(),
        )
    }
}
