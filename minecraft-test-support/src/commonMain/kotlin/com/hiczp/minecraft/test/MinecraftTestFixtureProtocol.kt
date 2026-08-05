package com.hiczp.minecraft.test

import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Rpc
interface MinecraftTestFixtureRpc {
    suspend fun createServer(
        owner: String,
        request: CreateOfficialServerRequest,
    ): FixtureResourceDescriptor

    suspend fun createClient(
        owner: String,
        request: CreateOfficialClientRequest,
    ): FixtureResourceDescriptor

    suspend fun status(resourceId: String): FixtureResourceStatus

    suspend fun log(resourceId: String): String

    suspend fun waitForLog(
        resourceId: String,
        marker: String,
        timeoutMillis: Long,
    )

    suspend fun sendCommand(
        resourceId: String,
        command: String,
    )

    suspend fun stopServer(resourceId: String): Int?

    suspend fun restartServer(
        resourceId: String,
    ): FixtureResourceDescriptor

    suspend fun awaitClientExit(resourceId: String): Int

    suspend fun closeResource(resourceId: String)

    suspend fun verifyCodec(fixtures: JsonElement)

    suspend fun readWorldFiles(
        resourceId: String,
    ): Map<String, ByteArray>

    suspend fun writeWorldFiles(
        resourceId: String,
        files: Map<String, ByteArray>,
    )
}

@Serializable
data class CreateOfficialServerRequest(
    val properties: Map<String, String>,
    val startupTimeoutMillis: Long,
    val stopTimeoutMillis: Long,
    val maximumBindAttempts: Int,
)

@Serializable
data class CreateOfficialClientRequest(
    val playerName: String,
    val endpoint: MinecraftTestEndpoint,
)

@Serializable
data class FixtureResourceDescriptor(
    val id: String,
    val endpoint: MinecraftTestEndpoint,
)

@Serializable
data class FixtureResourceStatus(
    val alive: Boolean,
    val exitCode: Int? = null,
)

const val FIXTURE_RPC_URL_ENV = "MINECRAFT_TEST_FIXTURE_RPC_URL"
const val FIXTURE_OWNER_ENV = "MINECRAFT_TEST_FIXTURE_OWNER"
