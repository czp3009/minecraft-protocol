package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftCryptography
import com.hiczp.minecraft.protocol.auth.MinecraftRsaKeyPair
import com.hiczp.minecraft.protocol.auth.MinecraftRsaPrivateKey
import com.hiczp.minecraft.protocol.auth.MinecraftSessionService
import com.hiczp.minecraft.protocol.auth.toUndashedString
import com.hiczp.minecraft.protocol.client.MinecraftClientProtocol
import com.hiczp.minecraft.protocol.client.MinecraftOnlineIdentity
import com.hiczp.minecraft.protocol.data.ProtocolDataSet
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.ConfigurationClientInformationPacket
import com.hiczp.minecraft.protocol.model.packet.ConfigurationServerboundPluginMessagePacket
import com.hiczp.minecraft.protocol.model.packet.HandshakeNextState
import com.hiczp.minecraft.protocol.model.packet.HandshakePacket
import com.hiczp.minecraft.protocol.model.packet.LoginAcknowledgedPacket
import com.hiczp.minecraft.protocol.model.packet.LoginStartPacket
import com.hiczp.minecraft.protocol.model.packet.LoginSuccessPacket
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.StatusPingRequestPacket
import com.hiczp.minecraft.protocol.model.packet.StatusPongResponsePacket
import com.hiczp.minecraft.protocol.model.packet.StatusRequestPacket
import com.hiczp.minecraft.protocol.model.packet.StatusResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.ChatMode
import com.hiczp.minecraft.protocol.model.type.ClientInformation
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MainHand
import com.hiczp.minecraft.protocol.model.type.ParticleStatus
import com.hiczp.minecraft.protocol.model.type.Uuid
import com.hiczp.minecraft.protocol.session.MinecraftSession
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MinecraftServerProtocolTest {
    @Test
    fun validatesConfigurationAndEscapesStatusJson() {
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(compressionThreshold = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(maximumPlayers = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(viewDistance = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(simulationDistance = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(maximumPacketsPerPhase = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(
                protocolData = object : ProtocolDataSet by VanillaProtocolData {
                    override val protocolVersion: Int =
                        MinecraftProtocol.PROTOCOL_VERSION + 1
                },
            )
        }

        val configuration = MinecraftServerConfiguration(
            compressionThreshold = null,
            statusDescription = "\"line\\\n\t\u0001",
            maximumPlayers = 7,
        )
        val json = configuration.statusJson(onlinePlayers = 3)

        assertTrue(json.contains("\"max\": 7"))
        assertTrue(json.contains("\"online\": 3"))
        assertTrue(json.contains("\\\"line\\\\\\n\\t\\u0001"))
        assertFalse(json.contains('\u0001'))
        val login = configuration.playLogin(
            GameProfile(Uuid(1, 2), "Probe", emptyList()),
        )
        assertEquals(configuration.viewDistance, login.chunkRadius)
        assertEquals(configuration.simulationDistance, login.simulationDistance)
        assertFalse(login.onlineMode)
    }

    @Test
    fun servesStatusThroughTheConfiguredHandler() = runTest {
        val (client, server) = sessionPair()
        val handler = object : MinecraftServerHandler {
            override suspend fun statusJson(
                configuration: MinecraftServerConfiguration,
            ): String = """{"custom":true}"""
        }
        val negotiation = async {
            MinecraftServerProtocol(
                server,
                MinecraftServerConfiguration(compressionThreshold = null),
                handler,
            ).negotiate()
        }

        client.send(handshake(HandshakeNextState.STATUS))
        client.send(StatusRequestPacket)
        assertEquals(
            StatusResponsePacket("""{"custom":true}"""),
            client.receive(),
        )
        client.send(StatusPingRequestPacket(42))
        assertEquals(StatusPongResponsePacket(42), client.receive())
        assertEquals(
            MinecraftServerNegotiationResult.StatusCompleted,
            negotiation.await(),
        )
    }

    @Test
    fun rejectsUnexpectedStatusOrderingAndRejectedProfiles() = runTest {
        run {
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(compressionThreshold = null),
                    ).negotiate()
                }
            }
            client.send(handshake(HandshakeNextState.STATUS))
            client.send(StatusPingRequestPacket(1))
            assertTrue(
                negotiation.await().message.orEmpty()
                    .contains("Expected StatusRequestPacket"),
            )
        }

        run {
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(compressionThreshold = null),
                        object : MinecraftServerHandler {
                            override suspend fun acceptProfile(
                                profile: GameProfile,
                            ): Boolean = false
                        },
                    ).negotiate()
                }
            }
            client.send(handshake(HandshakeNextState.LOGIN))
            client.send(LoginStartPacket("Rejected", Uuid(0, 1)))
            assertTrue(
                negotiation.await().message.orEmpty().contains("was rejected"),
            )
        }
    }

    @Test
    fun onlineAuthenticationNegotiatesEncryptedPlayThroughSessionServices() =
        runTest {
            val identityId = Uuid(0x1020, 0x3040)
            val clientService = MinecraftSessionService(
                HttpClient(
                    MockEngine {
                        respond("", HttpStatusCode.NoContent)
                    },
                ),
            )
            var hasJoinedRequests = 0
            val serverService = MinecraftSessionService(
                HttpClient(
                    MockEngine {
                        hasJoinedRequests++
                        respond(
                            """
                            {
                              "id": "${identityId.toUndashedString()}",
                              "properties": []
                            }
                            """.trimIndent(),
                            HttpStatusCode.OK,
                            headersOf(
                                HttpHeaders.ContentType,
                                "application/json",
                            ),
                        )
                    },
                ),
            )
            val authentication = MinecraftServerAuthentication.Online(
                sessionService = serverService,
                cryptography = IdentityCryptography,
                keyPair = IdentityCryptography.generateRsaKeyPair(),
            )
            val configuration = MinecraftServerConfiguration(
                authentication = authentication,
                compressionThreshold = null,
            )
            val identity = MinecraftOnlineIdentity(
                name = "OnlineProbe",
                id = identityId,
                accessToken = "token",
                sessionService = clientService,
                cryptography = IdentityCryptography,
            )
            val (client, server) = sessionPair()
            val serverResult = async {
                MinecraftServerProtocol(server, configuration).negotiate()
            }

            val clientResult = MinecraftClientProtocol(
                client,
                "localhost",
                25_565,
            ).login(identity)
            val negotiation = assertIs<
                    MinecraftServerNegotiationResult.PlayReady
                    >(serverResult.await())

            assertEquals(identityId, negotiation.profile.id)
            assertEquals(identityId, clientResult.login.profile.id)
            assertTrue(negotiation.login.onlineMode)
            assertEquals(1, hasJoinedRequests)
            clientService.httpClient.close()
            serverService.httpClient.close()
        }

    @Test
    fun limitsClientInformationAndKnownPackSearchIndependently() = runTest {
        suspend fun reachConfiguration(
            client: MinecraftSession,
        ) {
            client.send(handshake(HandshakeNextState.LOGIN))
            client.send(LoginStartPacket("LimitProbe", Uuid(0, 1)))
            assertIs<LoginSuccessPacket>(client.receive())
            client.send(LoginAcknowledgedPacket)
        }

        run {
            val observed = mutableListOf<Packet>()
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(
                            compressionThreshold = null,
                            maximumPacketsPerPhase = 1,
                        ),
                        observingHandler(observed),
                    ).negotiate()
                }
            }
            reachConfiguration(client)
            val ignored = pluginMessage()
            client.send(ignored)
            assertTrue(
                negotiation.await().message.orEmpty()
                    .contains("Client Information packet limit"),
            )
            assertEquals(1, observed.size)
            assertTrue(observed.single() == ignored)
        }

        run {
            val observed = mutableListOf<Packet>()
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(
                            compressionThreshold = null,
                            maximumPacketsPerPhase = 1,
                        ),
                        observingHandler(observed),
                    ).negotiate()
                }
            }
            reachConfiguration(client)
            client.send(ConfigurationClientInformationPacket(clientInformation()))
            client.receive()
            client.receive()
            val ignored = pluginMessage()
            client.send(ignored)
            assertTrue(
                negotiation.await().message.orEmpty()
                    .contains("Known Packs packet limit"),
            )
            assertEquals(1, observed.size)
            assertTrue(observed.single() == ignored)
        }
    }

    private fun observingHandler(
        packets: MutableList<Packet>,
    ): MinecraftServerHandler =
        object : MinecraftServerHandler {
            override suspend fun onPacket(packet: Packet) {
                packets += packet
            }
        }

    private fun pluginMessage(): ConfigurationServerboundPluginMessagePacket =
        ConfigurationServerboundPluginMessagePacket(
            CustomPayload.Unknown(
                Identifier("test:ignored"),
                ByteString(byteArrayOf(1)),
            ),
        )

    private fun clientInformation(): ClientInformation =
        ClientInformation(
            locale = "en_us",
            viewDistance = 8,
            chatMode = ChatMode.ENABLED,
            chatColors = true,
            displayedSkinParts = 0x7F,
            mainHand = MainHand.RIGHT,
            enableTextFiltering = false,
            allowServerListings = true,
            particleStatus = ParticleStatus.ALL,
        )

    private fun handshake(nextState: HandshakeNextState): HandshakePacket =
        HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = nextState,
        )

    private fun sessionPair(): Pair<MinecraftSession, MinecraftSession> {
        val clientToServer = ByteChannel()
        val serverToClient = ByteChannel()
        return MinecraftSession(
            MinecraftFrameStream(serverToClient, clientToServer),
            MinecraftSessionSide.CLIENT,
        ) to MinecraftSession(
            MinecraftFrameStream(clientToServer, serverToClient),
            MinecraftSessionSide.SERVER,
        )
    }

    private object TestPrivateKey : MinecraftRsaPrivateKey

    private object IdentityCryptography : MinecraftCryptography {
        override fun secureRandomBytes(size: Int): ByteArray =
            ByteArray(size) { (it + 1).toByte() }

        override fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair =
            MinecraftRsaKeyPair(byteArrayOf(1, 2, 3), TestPrivateKey)

        override fun rsaEncrypt(
            encodedPublicKey: ByteArray,
            plaintext: ByteArray,
        ): ByteArray = plaintext.copyOf()

        override fun rsaDecrypt(
            privateKey: MinecraftRsaPrivateKey,
            ciphertext: ByteArray,
        ): ByteArray = ciphertext.copyOf()
    }
}
