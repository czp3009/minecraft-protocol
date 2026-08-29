package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketSession
import com.hiczp.minecraft.protocol.session.createMinecraftClientPacketConnection
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
class MinecraftClientProtocolFailureTest {
    @Test
    fun buildsOfflineIdentityInput() {
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("ClientProbe")
        assertEquals(
            MinecraftOfflineIdentity.minecraftOfflineUuid("ClientProbe"),
            minecraftOfflineIdentity.id,
        )
    }

    @Test
    fun queryStatusRejectsWrongResponseAndMismatchedPong() = runTest {
        run {
            val (client, serverSession) = connectionPair()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(StatusPongResponsePacket(1))
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
                    StatusResponsePacket(ServerStatus()),
                )
                serverSession.receive()
                serverSession.send(StatusPongResponsePacket(2))
            }
            val failure = assertFailsWith<MinecraftClientException> {
                client.queryStatus(1)
            }
            assertContains(failure.message.orEmpty(), "did not preserve")
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
                    LoginDisconnectPacket(
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
                serverSession.send(minecraftServerKeyPair.createChallenge().toEncryptionRequestPacket())
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
                serverSession.send(minecraftServerKeyPair.createChallenge().toEncryptionRequestPacket())
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
        val loginSuccessPacket = LoginSuccessPacket(
            GameProfile(minecraftOnlineIdentity.id, minecraftOnlineIdentity.name, emptyList()),
            Uuid.fromLongs(3, 4),
        )
        val playLoginPacket = createPlayLoginPacket(onlineMode = true)
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(
                LoginStartPacket(minecraftOnlineIdentity.name, minecraftOnlineIdentity.id),
                serverSession.receive(),
            )
            val minecraftServerChallenge = minecraftServerKeyPair.createChallenge()
            serverSession.send(minecraftServerChallenge.toEncryptionRequestPacket())
            val encryptionResponsePacket = assertIs<EncryptionResponsePacket>(serverSession.receive())
            val minecraftServerKeyExchangeResult = minecraftServerChallenge.accept(encryptionResponsePacket)
            val secret = minecraftServerKeyExchangeResult.sharedSecret
            try {
                serverSession.enableEncryption(secret)
                serverSession.send(SetCompressionPacket(32))
                serverSession.send(loginSuccessPacket)
                assertEquals(LoginAcknowledgedPacket, serverSession.receive())
                assertIs<ConfigurationClientInformationPacket>(serverSession.receive())
                VanillaProtocolData.synchronizedRegistryPackets(
                    VanillaProtocolData.offeredKnownPacks,
                ).forEach { registryDataPacket -> serverSession.send(registryDataPacket) }
                serverSession.send(FinishConfigurationPacket)
                assertEquals(
                    AcknowledgeFinishConfigurationPacket,
                    serverSession.receive(),
                )
                serverSession.send(playLoginPacket)
            } finally {
                secret.fill(0)
            }
        }

        val minecraftClientNegotiationResult = client.negotiate(minecraftOnlineIdentity, httpClient)

        assertEquals(1, joinRequests)
        assertEquals(loginSuccessPacket, minecraftClientNegotiationResult.loginSuccessPacket)
        assertEquals(playLoginPacket, minecraftClientNegotiationResult.playLoginPacket)
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
                LoginPluginRequestPacket(
                    messageId = 7,
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

        assertEquals(minecraftOfflineIdentity.id, minecraftClientNegotiationResult.loginSuccessPacket.profile.id)
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
                LoginSuccessPacket(
                    GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            serverSession.receive()
            serverSession.receive()
            serverSession.send(CodeOfConductPacket("rules"))
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
        val callerPacket = ConfigurationPongPacket(91)
        client.outgoing.send(callerPacket)
        assertEquals(callerPacket, serverSession.receive())
        client.close()
    }

    @Test
    fun rejectsDuplicateDynamicRegistries() = runTest {
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("RegistryProbe")
        val registryDataPacket = RegistryDataPacket(
            Identifier("worldgen/biome"),
            listOf(RegistryEntry(Identifier("test:biome"), null)),
        )
        val (client, serverSession) = connectionPair()
        val server = async {
            serverSession.receive()
            serverSession.receive()
            serverSession.send(
                LoginSuccessPacket(
                    GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            serverSession.receive()
            serverSession.receive()
            serverSession.send(registryDataPacket)
            serverSession.send(registryDataPacket)
        }

        val failure = assertFailsWith<MinecraftClientException> {
            client.negotiate(minecraftOfflineIdentity)
        }

        assertContains(failure.message.orEmpty(), "duplicate registry")
        server.await()
        client.close()
    }

    private suspend fun completeOfflineNegotiation(
        minecraftServerPacketSession: MinecraftServerPacketSession,
        minecraftOfflineIdentity: MinecraftOfflineIdentity,
    ) {
        minecraftServerPacketSession.send(
            LoginSuccessPacket(
                GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                Uuid.fromLongs(1, 2),
            ),
        )
        assertEquals(LoginAcknowledgedPacket, minecraftServerPacketSession.receive())
        assertIs<ConfigurationClientInformationPacket>(minecraftServerPacketSession.receive())
        VanillaProtocolData.synchronizedRegistryPackets(
            VanillaProtocolData.offeredKnownPacks,
        ).forEach { registryDataPacket -> minecraftServerPacketSession.send(registryDataPacket) }
        minecraftServerPacketSession.send(FinishConfigurationPacket)
        assertEquals(AcknowledgeFinishConfigurationPacket, minecraftServerPacketSession.receive())
        minecraftServerPacketSession.send(createPlayLoginPacket())
    }

    private fun connectionPair(): Pair<MinecraftClientConnection, MinecraftServerPacketSession> {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val client = MinecraftClientConnection(
            minecraftClientPacketConnection = createMinecraftClientPacketConnection(
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

    private fun createPlayLoginPacket(
        onlineMode: Boolean = false,
    ): PlayLoginPacket = PlayLoginPacket(
        playerId = 1,
        hardcore = false,
        levels = setOf(Identifier("overworld")),
        maxPlayers = 20,
        chunkRadius = 8,
        simulationDistance = 8,
        reducedDebugInfo = false,
        showDeathScreen = true,
        limitedCrafting = false,
        spawnInfo = CommonPlayerSpawnInfo(
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
