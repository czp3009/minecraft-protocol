package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
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
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftClientNegotiationFailureTest {
    @Test
    fun buildsOfflineIdentityInput() {
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("ClientProbe")
        assertEquals(
            MinecraftOfflineIdentity.minecraftOfflineUuid("ClientProbe"),
            minecraftOfflineIdentity.id,
        )
    }

    @Test
    fun queryStatusRejectsWrongResponsesWithoutValidatingThePongPayload() = runTest {
        run {
            val (client, serverSession) = connectionPair()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(ClientboundPongResponsePacket(1))
            }
            val failure = assertFailsWith<MinecraftClientException> {
                client.queryStatus(1)
            }
            assertContains(failure.message.orEmpty(), "Expected Status Response")
            server.await()
            client.close()
        }

        run {
            val (client, serverSession) = connectionPair()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(
                    ClientboundStatusResponsePacket(ServerStatus()),
                )
                serverSession.receive()
                serverSession.send(ClientboundPongResponsePacket(2))
            }
            val minecraftStatusExchange = client.queryStatus(1)
            assertEquals(2, minecraftStatusExchange.clientboundPongResponsePacket.time)
            server.await()
            client.close()
        }
    }

    @Test
    fun loginSurfacesDisconnectsAndAuthenticationRequirements() = runTest {
        run {
            val (client, serverSession) = connectionPair()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(
                    ClientboundLoginDisconnectPacket(
                        JsonTextComponent(
                            buildJsonObject { put("text", "no") }.toString(),
                        ),
                    ),
                )
            }
            val failure = assertFailsWith<MinecraftClientException> {
                client.negotiate(MinecraftOfflineIdentity("ClientProbe"))
            }
            assertContains(failure.message.orEmpty(), "rejected Login")
            server.await()
            client.close()
        }

        run {
            val (client, serverSession) = connectionPair()
            val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(minecraftServerKeyPair.createChallenge().toClientboundHelloPacket())
            }
            val failure = assertFailsWith<MinecraftClientException> {
                client.negotiate(MinecraftOfflineIdentity("ClientProbe"))
            }
            assertContains(failure.message.orEmpty(), "offline identity")
            server.await()
            client.close()
        }

        run {
            val (client, serverSession) = connectionPair()
            val minecraftOnlineIdentity = onlineIdentity()
            val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(minecraftServerKeyPair.createChallenge().toClientboundHelloPacket())
            }
            val failure = assertFailsWith<MinecraftClientException> {
                client.negotiate(minecraftOnlineIdentity)
            }
            assertContains(
                failure.message.orEmpty(),
                "no Session Server HttpClient",
            )
            server.await()
            client.close()
        }
    }

    @Test
    fun completesOnlineJoinEncryptionCompressionAndPlayEntry() = runTest {
        var joinRequests = 0
        val httpClient = HttpClient(
            MockEngine { httpRequestData ->
                assertEquals("/session/minecraft/join", httpRequestData.url.encodedPath)
                joinRequests++
                respond("", HttpStatusCode.NoContent)
            },
        ) {
            followRedirects = false
        }
        val minecraftOnlineIdentity = onlineIdentity()
        val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
        val (client, serverSession) = connectionPair()
        val clientboundLoginFinishedPacket = ClientboundLoginFinishedPacket(
            GameProfile(minecraftOnlineIdentity.id, minecraftOnlineIdentity.name, emptyList()),
            Uuid.fromLongs(3, 4),
        )
        val clientboundLoginPacket = createClientboundLoginPacket(onlineMode = true)
        val server = async {
            assertIs<ClientIntentionPacket>(serverSession.receive())
            assertEquals(
                ServerboundHelloPacket(minecraftOnlineIdentity.name, minecraftOnlineIdentity.id),
                serverSession.receive(),
            )
            val minecraftServerChallenge = minecraftServerKeyPair.createChallenge()
            serverSession.send(minecraftServerChallenge.toClientboundHelloPacket())
            val serverboundKeyPacket = assertIs<ServerboundKeyPacket>(serverSession.receive())
            val minecraftServerKeyExchangeResult = minecraftServerChallenge.accept(serverboundKeyPacket)
            val secret = minecraftServerKeyExchangeResult.sharedSecret
            try {
                serverSession.enableEncryption(secret)
                serverSession.send(ClientboundLoginCompressionPacket(32))
                serverSession.send(clientboundLoginFinishedPacket)
                assertEquals(ServerboundLoginAcknowledgedPacket, serverSession.receive())
                assertIs<ServerboundClientInformationPacket>(serverSession.receive())
                VanillaConfigurationData.synchronizedRegistryPackets(
                    VanillaConfigurationData.offeredKnownPacks,
                ).forEach { clientboundRegistryDataPacket -> serverSession.send(clientboundRegistryDataPacket) }
                serverSession.send(ClientboundFinishConfigurationPacket)
                assertEquals(
                    ServerboundFinishConfigurationPacket,
                    serverSession.receive(),
                )
                serverSession.send(clientboundLoginPacket)
            } finally {
                secret.fill(0)
            }
        }

        val minecraftClientNegotiationResult = client.negotiate(minecraftOnlineIdentity, httpClient)

        assertEquals(1, joinRequests)
        assertEquals(clientboundLoginFinishedPacket, minecraftClientNegotiationResult.clientboundLoginFinishedPacket)
        assertEquals(clientboundLoginPacket, minecraftClientNegotiationResult.clientboundLoginPacket)
        server.await()
        client.close()
        httpClient.close()
    }

    @Test
    fun answersUnknownLoginQueriesWithoutClaimingUnderstanding() = runTest {
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("QueryProbe")
        val queryChannel = Identifier("mod:query")
        val (client, serverSession) = connectionPair()
        val server = async {
            serverSession.receive()
            serverSession.receive()
            serverSession.send(
                ClientboundCustomQueryPacket(
                    transactionId = 7,
                    channel = queryChannel,
                    data = ByteString(byteArrayOf(1, 2, 3)),
                ),
            )
            val response = assertIs<UnknownPacket.Serverbound>(
                serverSession.receive(),
            )
            assertEquals(
                PacketRoute.LoginQuery(
                    packetDirection = PacketDirection.SERVERBOUND,
                    transactionId = 7,
                    channel = queryChannel,
                    hasPayload = false,
                ),
                response.packetRoute,
            )
            completeOfflineNegotiation(serverSession, minecraftOfflineIdentity)
        }

        val minecraftClientNegotiationResult = client.negotiate(minecraftOfflineIdentity)

        assertEquals(
            minecraftOfflineIdentity.id,
            minecraftClientNegotiationResult.clientboundLoginFinishedPacket.gameProfile.id
        )
        server.await()
        client.close()
    }

    @Test
    fun policyFailuresDoNotEmitImplicitPackets() = runTest {
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("PolicyProbe")
        val (client, serverSession) = connectionPair()
        val server = async {
            serverSession.receive()
            serverSession.receive()
            serverSession.send(
                ClientboundLoginFinishedPacket(
                    GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            serverSession.receive()
            serverSession.receive()
            serverSession.send(ClientboundCodeOfConductPacket("rules"))
        }

        val failure = assertFailsWith<MinecraftClientException> {
            client.negotiate(
                minecraftOfflineIdentity,
                minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                    acceptCodeOfConduct = false,
                ),
            )
        }

        assertContains(failure.message.orEmpty(), "not accepted")
        server.await()
        val callerPacket = ServerboundPongPacket(91)
        client.outgoing.send(callerPacket)
        assertEquals(callerPacket, serverSession.receive())
        client.close()
    }

    @Test
    fun rejectsDuplicateDynamicRegistries() = runTest {
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("RegistryProbe")
        val clientboundRegistryDataPacket = ClientboundRegistryDataPacket(
            Identifier("worldgen/biome"),
            listOf(RegistryEntry(Identifier("test:biome"), null)),
        )
        val (client, serverSession) = connectionPair()
        val server = async {
            serverSession.receive()
            serverSession.receive()
            serverSession.send(
                ClientboundLoginFinishedPacket(
                    GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            serverSession.receive()
            serverSession.receive()
            serverSession.send(clientboundRegistryDataPacket)
            serverSession.send(clientboundRegistryDataPacket)
            serverSession.send(ClientboundFinishConfigurationPacket)
        }

        val failure = assertFailsWith<MinecraftClientException> {
            client.negotiate(minecraftOfflineIdentity)
        }

        assertContains(failure.message.orEmpty(), "duplicate registry identifiers")
        server.await()
        client.close()
    }

    private suspend fun completeOfflineNegotiation(
        minecraftServerPacketSession: MinecraftServerPacketSession,
        minecraftOfflineIdentity: MinecraftOfflineIdentity,
    ) {
        minecraftServerPacketSession.send(
            ClientboundLoginFinishedPacket(
                GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                Uuid.fromLongs(1, 2),
            ),
        )
        assertEquals(ServerboundLoginAcknowledgedPacket, minecraftServerPacketSession.receive())
        assertIs<ServerboundClientInformationPacket>(minecraftServerPacketSession.receive())
        VanillaConfigurationData.synchronizedRegistryPackets(
            VanillaConfigurationData.offeredKnownPacks,
        ).forEach { clientboundRegistryDataPacket -> minecraftServerPacketSession.send(clientboundRegistryDataPacket) }
        minecraftServerPacketSession.send(ClientboundFinishConfigurationPacket)
        assertEquals(ServerboundFinishConfigurationPacket, minecraftServerPacketSession.receive())
        minecraftServerPacketSession.send(createClientboundLoginPacket())
    }

    private fun connectionPair(): Pair<MinecraftClientConnection, MinecraftServerPacketSession> {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val client = MinecraftClientConnection(
            minecraftClientPacketConnection = MinecraftClientPacketConnection.create(
                minecraftFrameStream = clientFrames,
                closeTransport = { clientFrames.cancel() },
                minecraftConnectionDefinition = MinecraftConnectionDefinition(),
            ),
            serverAddress = "localhost",
            serverPort = 25_565,
        )
        val server = MinecraftServerPacketSession(
            MinecraftFrameStream(clientToServer, serverToClient),
        )
        return client to server
    }

    private fun createClientboundLoginPacket(
        onlineMode: Boolean = false,
    ): ClientboundLoginPacket = ClientboundLoginPacket(
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
            dimensionTypeId = 0,
            dimension = Identifier("overworld"),
            seed = 0,
            gameMode = GameMode.SURVIVAL,
            previousGameMode = null,
            isDebug = false,
            isFlat = false,
            lastDeathLocation = null,
            portalCooldown = 0,
            seaLevel = 63,
        ),
        onlineMode = onlineMode,
        enforcesSecureChat = false,
    )

    private fun onlineIdentity(): MinecraftOnlineIdentity =
        MinecraftOnlineIdentity(
            name = "OnlineProbe",
            id = Uuid.fromLongs(1, 2),
            accessToken = "token",
        )
}
