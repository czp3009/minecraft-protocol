package com.hiczp.minecraft.test

/** Lazily opened kRPC client initialized from the Gradle test environment. */
internal class MinecraftTestSupportServiceClient private constructor(
    private val connection: MinecraftTestSupportServiceConnection,
    val ownerId: String,
) : MinecraftTestSupportService by connection.service, AutoCloseable {
    override fun close() {
        connection.close()
    }

    companion object {
        fun fromEnvironment(): MinecraftTestSupportServiceClient {
            val rpcUrl = requiredEnvironment(FIXTURE_RPC_URL_ENV)
            val ownerId = requiredEnvironment(FIXTURE_OWNER_ID_ENV)
            return MinecraftTestSupportServiceClient(
                connection = openMinecraftTestSupportServiceConnection(
                    rpcUrl,
                ),
                ownerId = ownerId,
            )
        }

        private fun requiredEnvironment(name: String): String =
            platformEnvironmentVariable(name)
                ?.takeIf(String::isNotBlank)
                ?: error(
                    "Minecraft test fixture environment '$name' is absent; run this test through its standard Gradle test task",
                )
    }
}

internal expect fun platformEnvironmentVariable(name: String): String?

internal interface MinecraftTestSupportServiceConnection {
    val service: MinecraftTestSupportService

    fun close()
}

internal expect fun openMinecraftTestSupportServiceConnection(
    rpcUrl: String,
): MinecraftTestSupportServiceConnection
