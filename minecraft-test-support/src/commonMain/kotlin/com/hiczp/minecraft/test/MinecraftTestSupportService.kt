package com.hiczp.minecraft.test

import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/** kRPC contract whose operation names mirror [MinecraftTestSupport]. */
@Rpc
interface MinecraftTestSupportService {
    suspend fun newOfficialServer(
        ownerId: String,
        officialMinecraftServerConfiguration: OfficialMinecraftServerConfiguration,
    ): OfficialMinecraftServer

    suspend fun newHeadlessClient(
        ownerId: String,
        headlessMinecraftClientConfiguration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient

    suspend fun connectHeadlessClient(
        headlessMinecraftClient: HeadlessMinecraftClient,
        minecraftTestEndpoint: MinecraftTestEndpoint,
    ): HeadlessMinecraftClientState

    suspend fun headlessClientState(
        headlessMinecraftClient: HeadlessMinecraftClient,
    ): HeadlessMinecraftClientState

    suspend fun disconnectHeadlessClient(headlessMinecraftClient: HeadlessMinecraftClient)

    suspend fun sendHeadlessClientCommand(
        headlessMinecraftClient: HeadlessMinecraftClient,
        command: String,
        expectedNewOutput: String?,
        timeout: Duration,
    )

    suspend fun status(
        minecraftTestResource: MinecraftTestResource,
    ): MinecraftTestResourceStatus

    suspend fun logText(minecraftTestResource: MinecraftTestResource): String

    suspend fun waitForLog(
        minecraftTestResource: MinecraftTestResource,
        marker: String,
        timeout: Duration,
    )

    suspend fun sendCommand(
        officialMinecraftServer: OfficialMinecraftServer,
        command: String,
        expectedNewOutput: String?,
        timeout: Duration,
    )

    suspend fun restartServer(
        officialMinecraftServer: OfficialMinecraftServer,
    ): OfficialMinecraftServer

    suspend fun closeProcess(minecraftTestResource: MinecraftTestResource): Int

    suspend fun awaitExit(minecraftTestResource: MinecraftTestResource): Int

    suspend fun hostWorkingDirectory(minecraftTestResource: MinecraftTestResource): String

    suspend fun deleteWorkingDirectory(minecraftTestResource: MinecraftTestResource)

    suspend fun close(minecraftTestResource: MinecraftTestResource)

    suspend fun verifyOfficialCodec(fixtures: JsonElement)

    suspend fun verifyOfficialNbt(fixtures: JsonElement)

    suspend fun verifyOfficialSnbt(fixtures: JsonElement)
}

internal const val FIXTURE_RPC_URL_ENV = "MINECRAFT_TEST_FIXTURE_RPC_URL"
internal const val FIXTURE_OWNER_ID_ENV = "MINECRAFT_TEST_FIXTURE_OWNER_ID"
