package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.client.MinecraftClientConnection
import com.hiczp.minecraft.protocol.client.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Difficulty
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.Uuid
import com.hiczp.minecraft.protocol.model.type.Vector3d
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

class ClientToServerEndToEndTest {
    @Test
    fun productionClientReceivesInitialBlocksAndEntity() = runBlocking {
        SelectorManager(Dispatchers.IO).use { selector ->
            MinecraftServer.bind(
                selectorManager = selector,
                host = "127.0.0.1",
                port = 0,
                configuration = MinecraftServerConfiguration(
                    compressionThreshold = 64,
                    gameMode = PlayerGameMode.CREATIVE,
                    difficulty = Difficulty.HARD,
                    difficultyLocked = true,
                ),
            ).use { server ->
                val statusServer = async(Dispatchers.IO) {
                    server.accept().use { connection ->
                        connection.negotiate()
                    }
                }
                MinecraftClientConnection.connect(
                    selectorManager = selector,
                    host = "127.0.0.1",
                    port = server.port,
                ).use { client ->
                    val status = client.protocol.queryStatus(42)
                    assertEquals(42, status.pong.timestamp)
                    assertTrue(
                        status.response.jsonResponse.contains(
                            "\"protocol\": ${MinecraftProtocol.PROTOCOL_VERSION}",
                        ),
                    )
                }
                assertEquals(
                    MinecraftServerNegotiationResult.StatusCompleted,
                    statusServer.await(),
                )

                val playServer = async(Dispatchers.IO) {
                    server.accept().use { connection ->
                        val negotiation = assertIs<
                                MinecraftServerNegotiationResult.PlayReady
                                >(connection.negotiate())
                        val world = MinecraftInitialWorld.flatVanilla(
                            configuration = server.configuration,
                            chunkRadius = 0,
                            entities = listOf(testPig()),
                        )
                        val synchronization =
                            connection.synchronizeInitialWorld(world)
                        connection.session.send(
                            PlayClientboundKeepAlivePacket(KEEP_ALIVE_ID),
                        )

                        var teleportConfirmed = false
                        var chunkBatchConfirmed = false
                        var keepAliveConfirmed = false
                        withTimeout(10_000) {
                            repeat(server.configuration.maximumPacketsPerPhase) {
                                when (val packet = connection.session.receive()) {
                                    is ConfirmTeleportationPacket ->
                                        teleportConfirmed =
                                            packet.teleportId ==
                                                    synchronization.teleportId

                                    is ChunkBatchReceivedPacket ->
                                        chunkBatchConfirmed = true

                                    is PlayServerboundKeepAlivePacket ->
                                        keepAliveConfirmed =
                                            packet.id == KEEP_ALIVE_ID

                                    else -> Unit
                                }
                                if (
                                    teleportConfirmed &&
                                    chunkBatchConfirmed &&
                                    keepAliveConfirmed
                                ) {
                                    return@withTimeout
                                }
                            }
                        }
                        assertTrue(teleportConfirmed)
                        assertTrue(chunkBatchConfirmed)
                        assertTrue(keepAliveConfirmed)
                        ServerWorldOutcome(
                            negotiation = negotiation,
                            synchronization = synchronization,
                        )
                    }
                }
                val identity = MinecraftOfflineIdentity("EndToEndProbe")
                MinecraftClientConnection.connect(
                    selectorManager = selector,
                    host = "127.0.0.1",
                    port = server.port,
                ).use { client ->
                    val clientResult = client.protocol.login(identity)
                    var chunkReceived = false
                    var entityReceived = false
                    var keepAliveReceived = false
                    var difficultyReceived = false
                    var abilitiesReceived = false
                    withTimeout(10_000) {
                        while (!keepAliveReceived) {
                            when (val packet = client.session.receive()) {
                                is SynchronizePlayerPositionPacket ->
                                    client.session.send(
                                        ConfirmTeleportationPacket(
                                            packet.teleportId,
                                        ),
                                    )

                                is ChunkDataAndUpdateLightPacket ->
                                    chunkReceived = true

                                is ChunkBatchFinishedPacket ->
                                    client.session.send(
                                        ChunkBatchReceivedPacket(
                                            desiredChunksPerTick = 10.0f,
                                        ),
                                    )

                                is SpawnEntityPacket ->
                                    entityReceived =
                                        packet.typeId == testPig().typeId

                                is ClientboundChangeDifficultyPacket ->
                                    difficultyReceived =
                                        packet.difficulty == Difficulty.HARD &&
                                                packet.locked

                                is ClientboundPlayerAbilitiesPacket ->
                                    abilitiesReceived =
                                        packet.abilities ==
                                                vanillaPlayerAbilities(
                                                    PlayerGameMode.CREATIVE,
                                                )

                                is PlayClientboundKeepAlivePacket -> {
                                    assertEquals(KEEP_ALIVE_ID, packet.id)
                                    client.session.send(
                                        PlayServerboundKeepAlivePacket(
                                            packet.id,
                                        ),
                                    )
                                    keepAliveReceived = true
                                }

                                else -> Unit
                            }
                        }
                    }

                    val serverResult = playServer.await()
                    assertTrue(chunkReceived)
                    assertTrue(entityReceived)
                    assertTrue(difficultyReceived)
                    assertTrue(abilitiesReceived)
                    assertEquals(
                        1,
                        serverResult.synchronization.chunkCount,
                    )
                    assertEquals(
                        1,
                        serverResult.synchronization.entityCount,
                    )
                    assertEquals(identity.id, clientResult.login.profile.id)
                    assertEquals(
                        identity.id,
                        serverResult.negotiation.profile.id,
                    )
                    assertEquals(
                        clientResult.playLogin,
                        serverResult.negotiation.login,
                    )
                    assertEquals(
                        PlayerGameMode.CREATIVE,
                        clientResult.playLogin.spawnInfo.gameMode,
                    )
                    assertEquals(
                        server.configuration.protocolData
                            .registryPackets(
                                serverResult.negotiation.acceptedKnownPacks,
                            )
                            .size,
                        clientResult.configuration.registries.size,
                    )
                    assertEquals(
                        server.configuration.protocolData.knownPacks,
                        serverResult.negotiation.acceptedKnownPacks,
                    )
                }
            }
        }
    }

    private fun testPig(): MinecraftEntitySnapshot =
        MinecraftEntitySnapshot(
            entityId = 2,
            uuid = Uuid(0, 2),
            type = Identifier("pig"),
            position = Vector3d(3.5, 65.0, 3.5),
        )

    private companion object {
        const val KEEP_ALIVE_ID: Long = 0x1020_3040_5060_7080L
    }
}

private data class ServerWorldOutcome(
    val negotiation: MinecraftServerNegotiationResult.PlayReady,
    val synchronization: MinecraftInitialWorldSynchronization,
)
