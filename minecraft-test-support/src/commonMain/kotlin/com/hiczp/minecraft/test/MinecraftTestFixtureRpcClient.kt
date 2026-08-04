package com.hiczp.minecraft.test

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class MinecraftTestFixtureRpcClient private constructor(
    private val connection: MinecraftTestFixtureRpcConnection,
    private val configuration: FixtureClientConfiguration,
) {
    private val service: MinecraftTestFixtureRpc
        get() = connection.service

    suspend fun createServer(
        configuration: OfficialMinecraftServerConfiguration,
    ): FixtureResourceDescriptor = service.createServer(
        owner = this.configuration.ownerId,
        request = CreateOfficialServerRequest(
            properties = configuration.properties,
            startupTimeoutMillis =
                configuration.startupTimeout.inWholeMilliseconds,
            stopTimeoutMillis = configuration.stopTimeout.inWholeMilliseconds,
            maximumBindAttempts = configuration.maximumBindAttempts,
        ),
    )

    suspend fun createClient(
        configuration: HeadlessMinecraftClientConfiguration,
    ): FixtureResourceDescriptor = service.createClient(
        owner = this.configuration.ownerId,
        request = CreateOfficialClientRequest(
            playerName = configuration.playerName,
            endpoint = configuration.endpoint,
        ),
    )

    suspend fun status(resourceId: String): FixtureResourceStatus =
        service.status(resourceId)

    suspend fun log(resourceId: String): String =
        service.log(resourceId)

    suspend fun waitForLog(
        resourceId: String,
        marker: String,
        timeoutMillis: Long,
    ) = service.waitForLog(
        resourceId,
        marker,
        timeoutMillis,
    )

    suspend fun sendCommand(resourceId: String, command: String) {
        service.sendCommand(resourceId, command)
    }

    suspend fun stopServer(resourceId: String): Int? =
        service.stopServer(resourceId)

    suspend fun restartServer(
        resourceId: String,
    ): FixtureResourceDescriptor =
        service.restartServer(resourceId)

    suspend fun awaitClientExit(resourceId: String): Int =
        service.awaitClientExit(resourceId)

    suspend fun closeResource(resourceId: String) {
        service.closeResource(resourceId)
    }

    suspend fun submitReport(name: String, content: JsonElement) {
        service.submitReport(name, content)
    }

    suspend fun verifyCodec(
        fixtures: JsonElement,
        reportName: String,
    ): JsonObject = service.verifyCodec(fixtures, reportName).jsonObject

    suspend fun readWorldFiles(
        resourceId: String,
    ): Map<String, ByteArray> = service.readWorldFiles(resourceId)

    suspend fun writeWorldFiles(
        resourceId: String,
        files: Map<String, ByteArray>,
    ) = service.writeWorldFiles(resourceId, files)

    fun close() {
        connection.close()
    }

    companion object {
        fun connect(): MinecraftTestFixtureRpcClient {
            val configuration = FixtureClientConfiguration.fromEnvironment()
            return MinecraftTestFixtureRpcClient(
                connection = openMinecraftTestFixtureRpcConnection(
                    configuration.rpcUrl,
                ),
                configuration = configuration,
            )
        }
    }
}

private data class FixtureClientConfiguration(
    val rpcUrl: String,
    val ownerId: String,
) {
    companion object {
        fun fromEnvironment(): FixtureClientConfiguration =
            FixtureClientConfiguration(
                rpcUrl = requiredEnvironment(FIXTURE_RPC_URL_ENV),
                ownerId = requiredEnvironment(FIXTURE_OWNER_ENV),
            )

        private fun requiredEnvironment(name: String): String =
            platformEnvironmentVariable(name)
                ?.takeIf(String::isNotBlank)
                ?: error(
                    "Minecraft test fixture environment '$name' is absent; run this test through its standard Gradle test task",
                )
    }
}

internal expect fun platformEnvironmentVariable(name: String): String?

internal interface MinecraftTestFixtureRpcConnection {
    val service: MinecraftTestFixtureRpc

    fun close()
}

internal expect fun openMinecraftTestFixtureRpcConnection(
    rpcUrl: String,
): MinecraftTestFixtureRpcConnection
