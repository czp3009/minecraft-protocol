package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi
import com.hiczp.minecraft.protocol.session.MinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketSession
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftClientResourcePackTest {
    @Test
    fun userDownloadsAndAppliesThePackWhileConfigurationTrafficContinues() = runTest {
        val connectionPair = connectionPair()
        val resourcePack = resourcePack(1).copy(
            required = true,
            prompt = TextComponent.literal("Custom textures are required."),
        )
        val downloadStarted = CompletableDeferred<Unit>()
        val allowDownload = CompletableDeferred<Unit>()
        val allowApply = CompletableDeferred<Unit>()
        val bytes = "application-owned pack bytes".encodeToByteArray()
        val httpClient = HttpClient(MockEngine { request ->
            assertEquals(resourcePack.url, request.url.toString())
            downloadStarted.complete(Unit)
            allowDownload.await()
            respond(bytes)
        })
        try {
            val negotiation = async {
                connectionPair.client.negotiate(
                    identity,
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                        onResourcePack = { request, reportProgress ->
                            assertEquals(resourcePack, request)
                            reportProgress(ServerboundResourcePackPacket.Action.ACCEPTED)
                            val downloadedBytes = httpClient.get(request.url).body<ByteArray>()
                            reportProgress(ServerboundResourcePackPacket.Action.DOWNLOADED)
                            allowApply.await()
                            assertContentEquals(bytes, downloadedBytes)
                            ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                        },
                    ),
                )
            }
            configureServer(connectionPair.server)
            connectionPair.server.send(resourcePack)
            assertResponse(connectionPair.server, resourcePack, ServerboundResourcePackPacket.Action.ACCEPTED)
            downloadStarted.await()
            connectionPair.server.send(ClientboundPingPacket(51))
            assertEquals(ServerboundPongPacket(51), connectionPair.server.receive())
            assertFalse(negotiation.isCompleted)
            allowDownload.complete(Unit)
            assertResponse(connectionPair.server, resourcePack, ServerboundResourcePackPacket.Action.DOWNLOADED)
            connectionPair.server.send(ClientboundPingPacket(52))
            assertEquals(ServerboundPongPacket(52), connectionPair.server.receive())
            assertFalse(negotiation.isCompleted)
            allowApply.complete(Unit)
            assertResponse(
                connectionPair.server,
                resourcePack,
                ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
            )
            finishServer(connectionPair.server)
            negotiation.await()
            assertEquals(ConnectionState.PLAY, connectionPair.client.connectionState)
            val initialWorldPacket = ClientboundGameEventPacket(GameEventType.LEVEL_CHUNKS_LOAD_START, 0.0f)
            connectionPair.server.send(initialWorldPacket)
            assertEquals(initialWorldPacket, connectionPair.client.incoming.receive())
        } finally {
            httpClient.close()
            connectionPair.close()
        }
    }

    @Test
    fun receivesAndCompletesMultiplePacksIndependently() = runTest {
        val connectionPair = connectionPair()
        val first = resourcePack(1)
        val second = resourcePack(2)
        val releaseFirst = CompletableDeferred<Unit>()
        try {
            val negotiation = async {
                connectionPair.client.negotiate(
                    identity,
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                        onResourcePack = { request, reportProgress ->
                            reportProgress(ServerboundResourcePackPacket.Action.ACCEPTED)
                            if (request.id == first.id) releaseFirst.await()
                            ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                        },
                    ),
                )
            }
            configureServer(connectionPair.server)
            connectionPair.server.send(first)
            assertResponse(connectionPair.server, first, ServerboundResourcePackPacket.Action.ACCEPTED)
            connectionPair.server.send(second)
            assertResponse(connectionPair.server, second, ServerboundResourcePackPacket.Action.ACCEPTED)
            assertResponse(connectionPair.server, second, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED)
            releaseFirst.complete(Unit)
            assertResponse(connectionPair.server, first, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED)
            finishServer(connectionPair.server)
            negotiation.await()
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun popCancelsPendingWorkAndNotifiesTheApplicationForOneOrAllPacks() = runTest {
        for (popAll in listOf(false, true)) {
            val connectionPair = connectionPair()
            val resourcePack = resourcePack(1)
            val cancelled = CompletableDeferred<Unit>()
            val removed = CompletableDeferred<ClientboundResourcePackPopPacket>()
            try {
                val negotiation = async {
                    connectionPair.client.negotiate(
                        identity,
                        minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                            onResourcePack = { _, reportProgress ->
                                try {
                                    reportProgress(ServerboundResourcePackPacket.Action.ACCEPTED)
                                    awaitCancellation()
                                } finally {
                                    cancelled.complete(Unit)
                                }
                            },
                            onResourcePackPop = { removed.complete(it) },
                        ),
                    )
                }
                configureServer(connectionPair.server)
                connectionPair.server.send(resourcePack)
                assertResponse(connectionPair.server, resourcePack, ServerboundResourcePackPacket.Action.ACCEPTED)
                val pop = ClientboundResourcePackPopPacket(if (popAll) null else resourcePack.id)
                connectionPair.server.send(pop)
                assertResponse(connectionPair.server, resourcePack, ServerboundResourcePackPacket.Action.DISCARDED)
                cancelled.await()
                assertEquals(pop, removed.await())
                finishServer(connectionPair.server)
                negotiation.await()
            } finally {
                connectionPair.close()
            }
        }
    }

    @Test
    fun replacingAnIdCancelsTheOldRequestBeforeStartingTheNewOne() = runTest {
        val connectionPair = connectionPair()
        val first = resourcePack(1)
        val replacement = first.copy(url = "https://example.invalid/replacement.zip")
        val cancelled = CompletableDeferred<Unit>()
        try {
            val negotiation = async {
                connectionPair.client.negotiate(
                    identity,
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                        onResourcePack = { request, reportProgress ->
                            if (request == first) {
                                try {
                                    reportProgress(ServerboundResourcePackPacket.Action.ACCEPTED)
                                    awaitCancellation()
                                } finally {
                                    cancelled.complete(Unit)
                                }
                            }
                            assertEquals(replacement, request)
                            assertTrue(cancelled.isCompleted)
                            ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                        },
                    ),
                )
            }
            configureServer(connectionPair.server)
            connectionPair.server.send(first)
            assertResponse(connectionPair.server, first, ServerboundResourcePackPacket.Action.ACCEPTED)
            connectionPair.server.send(replacement)
            assertResponse(connectionPair.server, first, ServerboundResourcePackPacket.Action.DISCARDED)
            assertResponse(connectionPair.server, replacement, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED)
            finishServer(connectionPair.server)
            negotiation.await()
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun cancellationStopsTheUserCallbackAndLeavesConnectionOwnershipWithTheCaller() = runTest {
        val connectionPair = connectionPair()
        val resourcePack = resourcePack(1)
        val cancelled = CompletableDeferred<Unit>()
        try {
            val negotiation = async {
                connectionPair.client.negotiate(
                    identity,
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                        onResourcePack = { _, reportProgress ->
                            try {
                                reportProgress(ServerboundResourcePackPacket.Action.ACCEPTED)
                                awaitCancellation()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        },
                    ),
                )
            }
            configureServer(connectionPair.server)
            connectionPair.server.send(resourcePack)
            assertResponse(connectionPair.server, resourcePack, ServerboundResourcePackPacket.Action.ACCEPTED)
            negotiation.cancelAndJoin()
            cancelled.await()
            assertTrue(connectionPair.client.isOpen)
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun callbackCancellationAndFailuresPropagateWithoutInventingAResourcePackStatus() = runTest {
        val failures =
            listOf(IllegalArgumentException("Application failed"), CancellationException("Application cancelled"))
        for (failure in failures) {
            val connectionPair = connectionPair()
            try {
                val negotiation = async {
                    assertFailsWith<Exception> {
                        connectionPair.client.negotiate(
                            identity,
                            minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                                onResourcePack = { _, _ -> throw failure },
                            ),
                        )
                    }
                }
                configureServer(connectionPair.server)
                connectionPair.server.send(resourcePack(1))
                val observed = negotiation.await()
                if (failure is CancellationException) {
                    assertIs<CancellationException>(observed)
                } else {
                    assertIs<IllegalArgumentException>(observed)
                }
                assertEquals(failure.message, observed.message)
                assertTrue(connectionPair.client.isOpen)
                connectionPair.client.outgoing.send(ServerboundPongPacket(73))
                connectionPair.client.requestFlush()
                assertEquals(ServerboundPongPacket(73), connectionPair.server.receive())
            } finally {
                connectionPair.close()
            }
        }
    }

    private val identity = MinecraftOfflineIdentity("PackClient")

    private fun resourcePack(id: Long): ClientboundResourcePackPushPacket = ClientboundResourcePackPushPacket(
        Uuid.fromLongs(17, id), "https://example.invalid/pack$id.zip", "", false, null,
    )

    private suspend fun assertResponse(
        server: MinecraftServerPacketSession,
        resourcePack: ClientboundResourcePackPushPacket,
        action: ServerboundResourcePackPacket.Action,
    ) {
        assertEquals(ServerboundResourcePackPacket(resourcePack.id, action), server.receive())
    }

    private suspend fun configureServer(server: MinecraftServerPacketSession) {
        assertIs<ClientIntentionPacket>(server.receive())
        assertIs<ServerboundHelloPacket>(server.receive())
        server.send(
            ClientboundLoginFinishedPacket(
                GameProfile(identity.id, identity.name, emptyList()),
                Uuid.fromLongs(1, 2)
            )
        )
        assertEquals(ServerboundLoginAcknowledgedPacket, server.receive())
        assertIs<ServerboundClientInformationPacket>(server.receive())
        server.send(ClientboundSelectKnownPacks(VanillaConfigurationData.offeredKnownPacks))
        val accepted = assertIs<ServerboundSelectKnownPacks>(server.receive())
        VanillaConfigurationData.synchronizedRegistryPackets(accepted.knownPacks).forEach { server.send(it) }
    }

    private suspend fun finishServer(server: MinecraftServerPacketSession) {
        server.send(ClientboundFinishConfigurationPacket)
        assertEquals(ServerboundFinishConfigurationPacket, server.receive())
        server.send(
            ClientboundLoginPacket(
                playerId = 1,
                hardcore = false,
                levels = setOf(Identifier("overworld")),
                maxPlayers = 20,
                chunkRadius = 8,
                simulationDistance = 8,
                reducedDebugInfo = false,
                showDeathScreen = true,
                doLimitedCrafting = false,
                commonPlayerSpawnInfo = CommonPlayerSpawnInfo(
                    0, Identifier("overworld"), 0, GameMode.SURVIVAL, null, false, false, null, 0, 63,
                ),
                onlineMode = false,
                enforcesSecureChat = false,
            ),
        )
    }

    private fun TestScope.connectionPair(): ResourcePackConnectionPair {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val serverFrames = MinecraftFrameStream(clientToServer, serverToClient)
        val client = MinecraftClientConnection(
            MinecraftClientPacketConnection.create(
                minecraftFrameStream = clientFrames,
                closeTransport = { clientFrames.cancel() },
                minecraftConnectionDefinition = MinecraftConnectionDefinition(),
                connectionDispatcher = StandardTestDispatcher(testScheduler),
            ),
            "localhost",
            25_565,
        )
        return ResourcePackConnectionPair(client, MinecraftServerPacketSession(serverFrames), serverFrames)
    }
}

private data class ResourcePackConnectionPair(
    val client: MinecraftClientConnection,
    val server: MinecraftServerPacketSession,
    val serverFrames: MinecraftFrameStream,
) {
    fun close() {
        client.close()
        serverFrames.cancel()
    }
}
