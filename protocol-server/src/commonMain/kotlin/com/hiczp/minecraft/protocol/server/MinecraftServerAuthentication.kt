package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftServerKeyPair
import io.ktor.client.*

sealed interface MinecraftServerAuthentication {
    data object Offline : MinecraftServerAuthentication

    data class Online(
        val sessionHttpClient: HttpClient,
        val minecraftServerKeyPair: MinecraftServerKeyPair,
    ) : MinecraftServerAuthentication

    companion object {
        suspend fun online(
            sessionHttpClient: HttpClient,
        ): Online = Online(
            sessionHttpClient = sessionHttpClient,
            minecraftServerKeyPair = MinecraftServerKeyPair.generate(),
        )
    }
}
