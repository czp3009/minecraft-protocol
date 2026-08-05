package com.hiczp.minecraft.test

import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/** kRPC contract whose operation names mirror [MinecraftTestSupport]. */
@Rpc
interface MinecraftTestSupportService {
    suspend fun newOfficialServer(
        ownerId: String,
        configuration: OfficialMinecraftServerConfiguration,
    ): OfficialMinecraftServer

    suspend fun newOfficialClient(
        ownerId: String,
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient

    suspend fun status(
        resource: MinecraftTestResource,
    ): MinecraftTestResourceStatus

    suspend fun logText(resource: MinecraftTestResource): String

    suspend fun waitForLog(
        resource: MinecraftTestResource,
        marker: String,
        timeout: Duration,
    )

    suspend fun sendCommand(
        server: OfficialMinecraftServer,
        command: String,
    )

    suspend fun stopServer(server: OfficialMinecraftServer): Int?

    suspend fun restartServer(
        server: OfficialMinecraftServer,
    ): OfficialMinecraftServer

    suspend fun awaitClientExit(client: HeadlessMinecraftClient): Int

    suspend fun close(resource: MinecraftTestResource)

    suspend fun verifyOfficialCodec(fixtures: JsonElement)

    suspend fun readWorldFiles(
        server: OfficialMinecraftServer,
    ): Map<String, ByteArray>

    suspend fun writeWorldFiles(
        server: OfficialMinecraftServer,
        files: Map<String, ByteArray>,
    )
}

internal const val FIXTURE_RPC_URL_ENV = "MINECRAFT_TEST_FIXTURE_RPC_URL"
internal const val FIXTURE_OWNER_ID_ENV = "MINECRAFT_TEST_FIXTURE_OWNER_ID"
