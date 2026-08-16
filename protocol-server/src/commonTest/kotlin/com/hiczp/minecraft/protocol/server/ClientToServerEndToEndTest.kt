package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.client.MinecraftClientConnection
import com.hiczp.minecraft.protocol.client.negotiate
import com.hiczp.minecraft.protocol.client.queryStatus
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Difficulty
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.PlayerAbilities
import com.hiczp.minecraft.protocol.model.type.Vector3d
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import kotlin.uuid.Uuid
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

class ClientToServerEndToEndTest {
    @Test
    fun productionClientReceivesInitialBlocksAndEntity() = runTest {
        SelectorManager(Dispatchers.Default).use { selector ->
            val options = MinecraftServerNegotiationOptions(
                compressionThreshold = 64,
                gameMode = PlayerGameMode.CREATIVE,
                difficulty = Difficulty.HARD,
                difficultyLocked = true,
            )
            MinecraftServer.bind(
                selectorManager = selector,
                host = "127.0.0.1",
                port = 0,
            ).use { server ->
                val statusServer = async {
                    server.accept().use { connection ->
                        connection.negotiate(options = options)
                    }
                }
                MinecraftClientConnection.connect(
                    selectorManager = selector,
                    host = "127.0.0.1",
                    port = server.port,
                ).use { client ->
                    val status = client.queryStatus(42)
                    assertEquals(42, status.pong.timestamp)
                    val statusDocument = Json
                        .parseToJsonElement(status.response.jsonResponse)
                        .jsonObject
                    assertEquals(
                        MinecraftProtocol.PROTOCOL_VERSION,
                        statusDocument.getValue("version")
                            .jsonObject
                            .getValue("protocol")
                            .jsonPrimitive
                            .int,
                    )
                }
                assertEquals(
                    MinecraftServerNegotiationResult.StatusCompleted,
                    statusServer.await(),
                )

                val playServer = async {
                    server.accept().use { connection ->
                        val negotiationResult = connection.negotiate(
                            options = options,
                        )
                        val negotiation =
                            assertIs<MinecraftServerNegotiationResult.PlayReady>(
                                negotiationResult,
                            )
                        val world = MinecraftInitialWorld.flatVanilla(
                            options = options,
                            chunkRadius = 0,
                            entities = listOf(testPig()),
                        )
                        val synchronization =
                            connection.synchronizeInitialWorld(world)
                        connection.outgoing.send(
                            PlayClientboundKeepAlivePacket(KEEP_ALIVE_ID),
                        )

                        var teleportConfirmed = false
                        var chunkBatchConfirmed = false
                        var keepAliveConfirmed = false
                        var remainingPackets =
                            options.maximumPacketsPerPhase
                        while (
                            remainingPackets-- > 0 &&
                            !(
                                    teleportConfirmed &&
                                            chunkBatchConfirmed &&
                                            keepAliveConfirmed
                                    )
                        ) {
                            when (val packet = connection.incoming.receive()) {
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
                    val clientResult = client.negotiate(identity)
                    var chunkReceived = false
                    var entityReceived = false
                    var keepAliveReceived = false
                    var difficultyReceived = false
                    var abilities: PlayerAbilities? = null
                    while (!keepAliveReceived) {
                        when (val packet = client.incoming.receive()) {
                            is SynchronizePlayerPositionPacket ->
                                client.outgoing.send(
                                    ConfirmTeleportationPacket(
                                        packet.teleportId,
                                    ),
                                )

                            is ChunkDataAndUpdateLightPacket ->
                                chunkReceived = true

                            is ChunkBatchFinishedPacket ->
                                client.outgoing.send(
                                    ChunkBatchReceivedPacket(
                                        desiredChunksPerTick = 10.0f,
                                    ),
                                )

                            is SpawnEntityPacket ->
                                entityReceived =
                                    packet.typeId == testPig().typeId(
                                        VanillaProtocolData.registryContext,
                                    )

                            is ClientboundChangeDifficultyPacket ->
                                difficultyReceived =
                                    packet.difficulty == Difficulty.HARD &&
                                            packet.locked

                            is ClientboundPlayerAbilitiesPacket ->
                                abilities = packet.abilities

                            is PlayClientboundKeepAlivePacket -> {
                                assertEquals(KEEP_ALIVE_ID, packet.id)
                                client.outgoing.send(
                                    PlayServerboundKeepAlivePacket(
                                        packet.id,
                                    ),
                                )
                                keepAliveReceived = true
                            }

                            else -> Unit
                        }
                    }

                    val serverResult = playServer.await()
                    assertTrue(chunkReceived)
                    assertTrue(entityReceived)
                    assertTrue(difficultyReceived)
                    assertPlayerAbilitiesEqual(
                        expected = vanillaPlayerAbilities(
                            PlayerGameMode.CREATIVE,
                        ),
                        actual = assertNotNull(abilities),
                    )
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
                        options.protocolData
                            .registryPackets(
                                serverResult.negotiation.acceptedKnownPacks,
                            )
                            .size,
                        clientResult.configuration.registries.size,
                    )
                    assertEquals(
                        options.protocolData.knownPacks,
                        serverResult.negotiation.acceptedKnownPacks,
                    )
                }
            }
        }
    }

    private fun testPig(): MinecraftEntitySnapshot =
        MinecraftEntitySnapshot(
            entityId = 2,
            uuid = Uuid.fromLongs(0, 2),
            type = Identifier("pig"),
            position = Vector3d(3.5, 65.0, 3.5),
        )

    private fun assertPlayerAbilitiesEqual(
        expected: PlayerAbilities,
        actual: PlayerAbilities,
    ) {
        assertEquals(expected.invulnerable, actual.invulnerable)
        assertEquals(expected.flying, actual.flying)
        assertEquals(expected.canFly, actual.canFly)
        assertEquals(expected.instantBuild, actual.instantBuild)
        assertEquals(
            expected.flyingSpeed.toRawBits(),
            actual.flyingSpeed.toRawBits(),
        )
        assertEquals(
            expected.walkingSpeed.toRawBits(),
            actual.walkingSpeed.toRawBits(),
        )
    }

    private companion object {
        const val KEEP_ALIVE_ID: Long = 0x1020_3040_5060_7080L
    }
}

private data class ServerWorldOutcome(
    val negotiation: MinecraftServerNegotiationResult.PlayReady,
    val synchronization: MinecraftInitialWorldSynchronization,
)
