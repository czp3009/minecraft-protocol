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
        val offline = MinecraftOfflineIdentity("ClientProbe")
        assertEquals(
            MinecraftOfflineIdentity.minecraftOfflineUuid("ClientProbe"),
            offline.id,
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
                    StatusResponsePacket(buildJsonObject {}.toString()),
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
            val keyPair = MinecraftServerKeyPair.generate()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(keyPair.createChallenge().toEncryptionRequestPacket())
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
            val identity = onlineIdentity()
            val keyPair = MinecraftServerKeyPair.generate()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(keyPair.createChallenge().toEncryptionRequestPacket())
            }
            val failure = assertFailsWith<MinecraftClientException> {
                client.negotiate(identity)
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
            MockEngine { request ->
                assertEquals("/session/minecraft/join", request.url.encodedPath)
                joinRequests++
                respond("", HttpStatusCode.NoContent)
            },
        ) {
            followRedirects = false
        }
        val identity = onlineIdentity()
        val keyPair = MinecraftServerKeyPair.generate()
        val (client, serverSession) = connectionPair()
        val loginSuccessPacket = LoginSuccessPacket(
            GameProfile(identity.id, identity.name, emptyList()),
            Uuid.fromLongs(3, 4),
        )
        val playLoginPacket = createPlayLoginPacket(onlineMode = true)
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(
                LoginStartPacket(identity.name, identity.id),
                serverSession.receive(),
            )
            val challenge = keyPair.createChallenge()
            serverSession.send(challenge.toEncryptionRequestPacket())
            val response = assertIs<EncryptionResponsePacket>(serverSession.receive())
            val exchange = challenge.accept(response)
            val secret = exchange.sharedSecret
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

        val result = client.negotiate(identity, httpClient)

        assertEquals(1, joinRequests)
        assertEquals(loginSuccessPacket, result.loginSuccessPacket)
        assertEquals(playLoginPacket, result.playLoginPacket)
        server.await()
        client.close()
        httpClient.close()
    }

    @Test
    fun answersUnknownLoginQueriesWithoutClaimingUnderstanding() = runTest {
        val identity = MinecraftOfflineIdentity("QueryProbe")
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
                    direction = PacketDirection.SERVERBOUND,
                    transactionId = 7,
                    channel = queryChannel,
                    hasPayload = false,
                ),
                response.route,
            )
            completeOfflineNegotiation(serverSession, identity)
        }

        val result = client.negotiate(identity)

        assertEquals(identity.id, result.loginSuccessPacket.profile.id)
        server.await()
        client.close()
    }

    @Test
    fun policyFailuresDoNotEmitImplicitPackets() = runTest {
        val identity = MinecraftOfflineIdentity("PolicyProbe")
        val (client, serverSession) = connectionPair()
        val server = async {
            serverSession.receive()
            serverSession.receive()
            serverSession.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            serverSession.receive()
            serverSession.receive()
            serverSession.send(CodeOfConductPacket("rules"))
        }

        val failure = assertFailsWith<MinecraftClientException> {
            client.negotiate(
                identity,
                options = MinecraftClientNegotiationOptions(
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
        val identity = MinecraftOfflineIdentity("RegistryProbe")
        val registry = RegistryDataPacket(
            Identifier("worldgen/biome"),
            listOf(RegistryEntry(Identifier("test:biome"), null)),
        )
        val (client, serverSession) = connectionPair()
        val server = async {
            serverSession.receive()
            serverSession.receive()
            serverSession.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            serverSession.receive()
            serverSession.receive()
            serverSession.send(registry)
            serverSession.send(registry)
        }

        val failure = assertFailsWith<MinecraftClientException> {
            client.negotiate(identity)
        }

        assertContains(failure.message.orEmpty(), "duplicate registry")
        server.await()
        client.close()
    }

    private suspend fun completeOfflineNegotiation(
        server: MinecraftServerPacketSession,
        identity: MinecraftOfflineIdentity,
    ) {
        server.send(
            LoginSuccessPacket(
                GameProfile(identity.id, identity.name, emptyList()),
                Uuid.fromLongs(1, 2),
            ),
        )
        assertEquals(LoginAcknowledgedPacket, server.receive())
        assertIs<ConfigurationClientInformationPacket>(server.receive())
        VanillaProtocolData.synchronizedRegistryPackets(
            VanillaProtocolData.offeredKnownPacks,
        ).forEach { registryDataPacket -> server.send(registryDataPacket) }
        server.send(FinishConfigurationPacket)
        assertEquals(AcknowledgeFinishConfigurationPacket, server.receive())
        server.send(createPlayLoginPacket())
    }

    private fun connectionPair(): Pair<MinecraftClientConnection, MinecraftServerPacketSession> {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val client = MinecraftClientConnection(
            connection = createMinecraftClientPacketConnection(
                frameStream = clientFrames,
                closeTransport = { clientFrames.cancel() },
                definition = MinecraftConnectionDefinition(),
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
