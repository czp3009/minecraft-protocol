package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.MinecraftSession
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MinecraftClientProtocolFailureTest {
    @Test
    fun validatesOptionsIdentityAndDefaultHandlerContracts() = runTest {
        assertFailsWith<IllegalArgumentException> {
            MinecraftClientOptions(maximumPacketsPerPhase = 0)
        }

        val offline = MinecraftOfflineIdentity("ClientProbe")
        assertEquals(offlineUuid("ClientProbe"), offline.id)

        val service = MinecraftSessionService(
            HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }),
        )
        val online = MinecraftOnlineIdentity(
            name = "ClientProbe",
            id = Uuid(1, 2),
            accessToken = "secret-token",
            sessionService = service,
            cryptography = IdentityCryptography,
        )
        assertFalse(online.toString().contains("secret-token"))
        assertTrue(online.toString().contains("<redacted>"))

        val cookie = LoginCookieRequestPacket(Identifier("test:cookie"))
        val plugin = LoginPluginRequestPacket(
            1,
            Identifier("test:plugin"),
            ByteString(byteArrayOf()),
        )
        assertNull(DefaultMinecraftClientHandler.loginCookie(cookie))
        assertNull(DefaultMinecraftClientHandler.loginPlugin(plugin))
        assertNull(
            DefaultMinecraftClientHandler.configurationCookie(
                ConfigurationCookieRequestPacket(cookie.key),
            ),
        )
        assertTrue(
            DefaultMinecraftClientHandler.acceptCodeOfConduct(
                CodeOfConductPacket("rules"),
            ),
        )
        service.httpClient.close()
    }

    @Test
    fun queryStatusRejectsWrongResponseAndMismatchedPong() = runTest {
        run {
            val (clientSession, serverSession) = sessionPair()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(StatusPongResponsePacket(1))
            }
            val failure = assertFailsWith<MinecraftClientException> {
                MinecraftClientProtocol(
                    clientSession,
                    "localhost",
                    25_565,
                ).queryStatus(1)
            }
            assertTrue(failure.message.orEmpty().contains("Expected Status Response"))
            server.await()
        }

        run {
            val (clientSession, serverSession) = sessionPair()
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(StatusResponsePacket("{}"))
                serverSession.receive()
                serverSession.send(StatusPongResponsePacket(2))
            }
            val failure = assertFailsWith<MinecraftClientException> {
                MinecraftClientProtocol(
                    clientSession,
                    "localhost",
                    25_565,
                ).queryStatus(1)
            }
            assertTrue(failure.message.orEmpty().contains("did not preserve"))
            server.await()
        }
    }

    @Test
    fun loginReportsServerDisconnectsAndRejectsOnlineChallengeForOfflineIdentity() =
        runTest {
            run {
                val (clientSession, serverSession) = sessionPair()
                val server = async {
                    serverSession.receive()
                    serverSession.receive()
                    serverSession.send(
                        LoginDisconnectPacket(JsonTextComponent("""{"text":"no"}""")),
                    )
                }
                val failure = assertFailsWith<MinecraftClientException> {
                    MinecraftClientProtocol(
                        clientSession,
                        "localhost",
                        25_565,
                    ).login(MinecraftOfflineIdentity("ClientProbe"))
                }
                assertTrue(failure.message.orEmpty().contains("rejected Login"))
                server.await()
            }

            run {
                val (clientSession, serverSession) = sessionPair()
                val server = async {
                    serverSession.receive()
                    serverSession.receive()
                    serverSession.send(encryptionRequest())
                }
                val failure = assertFailsWith<MinecraftClientException> {
                    MinecraftClientProtocol(
                        clientSession,
                        "localhost",
                        25_565,
                    ).login(MinecraftOfflineIdentity("ClientProbe"))
                }
                assertTrue(failure.message.orEmpty().contains("offline identity"))
                server.await()
            }
        }

    @Test
    fun completesOnlineEncryptionJoinAndEncryptedPlayEntry() = runTest {
        var joinRequests = 0
        val service = MinecraftSessionService(
            HttpClient(
                MockEngine { request ->
                    assertEquals("/session/minecraft/join", request.url.encodedPath)
                    joinRequests++
                    respond("", HttpStatusCode.NoContent)
                },
            ),
        )
        val identity = MinecraftOnlineIdentity(
            name = "OnlineProbe",
            id = Uuid(1, 2),
            accessToken = "token",
            sessionService = service,
            cryptography = IdentityCryptography,
        )
        val (clientSession, serverSession) = sessionPair()
        val success = LoginSuccessPacket(
            GameProfile(identity.id, identity.name, emptyList()),
            Uuid(3, 4),
        )
        val play = playLogin(onlineMode = true)
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(
                LoginStartPacket(identity.name, identity.id),
                serverSession.receive(),
            )
            val request = encryptionRequest()
            serverSession.send(request)
            val response = assertIs<EncryptionResponsePacket>(
                serverSession.receive(),
            )
            assertContentEquals(
                request.verifyToken.toByteArray(),
                response.verifyToken.toByteArray(),
            )
            val secret = response.sharedSecret.toByteArray()
            assertEquals(16, secret.size)
            serverSession.enableEncryption(secret)
            serverSession.send(success)
            assertEquals(LoginAcknowledgedPacket, serverSession.receive())
            assertIs<ConfigurationClientInformationPacket>(
                serverSession.receive(),
            )
            serverSession.send(FinishConfigurationPacket)
            assertEquals(
                AcknowledgeFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(play)
        }

        val result = MinecraftClientProtocol(
            clientSession,
            "localhost",
            25_565,
        ).login(identity)

        assertEquals(success, result.login)
        assertEquals(play, result.playLogin)
        assertEquals(1, joinRequests)
        server.await()
        service.httpClient.close()
    }

    @Test
    fun customHandlerAnswersEveryRequestAndObservesUnhandledPackets() =
        runTest {
            val (clientSession, serverSession) = sessionPair()
            val observed = mutableListOf<Packet>()
            val responseData = ByteString(byteArrayOf(1, 2, 3))
            val selectedPack = KnownPack("test", "selected", "1")
            val handler = object : MinecraftClientHandler {
                override suspend fun loginCookie(
                    request: LoginCookieRequestPacket,
                ): ByteString = responseData

                override suspend fun loginPlugin(
                    request: LoginPluginRequestPacket,
                ): ByteString = responseData

                override suspend fun configurationCookie(
                    request: ConfigurationCookieRequestPacket,
                ): ByteString = responseData

                override suspend fun selectKnownPacks(
                    offered: List<KnownPack>,
                ): List<KnownPack> = listOf(selectedPack)

                override suspend fun onPacket(packet: Packet) {
                    observed += packet
                }
            }
            val identity = MinecraftOfflineIdentity("HandlerProbe")
            val success = LoginSuccessPacket(
                GameProfile(identity.id, identity.name, emptyList()),
                Uuid(3, 4),
            )
            val server = async {
                serverSession.receive()
                serverSession.receive()
                val key = Identifier("test:cookie")
                serverSession.send(LoginCookieRequestPacket(key))
                assertEquals(
                    LoginCookieResponsePacket(key, responseData),
                    serverSession.receive(),
                )
                serverSession.send(
                    LoginPluginRequestPacket(
                        7,
                        Identifier("test:plugin"),
                        ByteString(byteArrayOf()),
                    ),
                )
                assertEquals(
                    LoginPluginResponsePacket(7, responseData),
                    serverSession.receive(),
                )
                serverSession.send(success)
                serverSession.receive()
                serverSession.receive()
                serverSession.send(ConfigurationCookieRequestPacket(key))
                assertEquals(
                    ConfigurationCookieResponsePacket(key, responseData),
                    serverSession.receive(),
                )
                serverSession.send(
                    ConfigurationClientboundKnownPacksPacket(
                        listOf(selectedPack),
                    ),
                )
                assertEquals(
                    ConfigurationServerboundKnownPacksPacket(
                        listOf(selectedPack),
                    ),
                    serverSession.receive(),
                )
                val plugin = ConfigurationClientboundPluginMessagePacket(
                    CustomPayload.Unknown(
                        Identifier("test:payload"),
                        ByteString(byteArrayOf(9)),
                    ),
                )
                serverSession.send(plugin)
                serverSession.send(FinishConfigurationPacket)
                serverSession.receive()
                serverSession.send(playLogin())
            }

            MinecraftClientProtocol(
                clientSession,
                "localhost",
                25_565,
            ).login(identity, handler = handler)

            assertEquals(
                listOf(ConfigurationClientboundPluginMessagePacket::class),
                observed.map { it::class },
            )
            server.await()
        }

    @Test
    fun rejectsConfigurationDisconnectAndDeclinedCodeOfConduct() = runTest {
        suspend fun assertRejected(
            packet: ConfigurationStatePacket,
            handler: MinecraftClientHandler = DefaultMinecraftClientHandler,
        ): MinecraftClientException {
            val (clientSession, serverSession) = sessionPair()
            val identity = MinecraftOfflineIdentity("RejectedProbe")
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(
                    LoginSuccessPacket(
                        GameProfile(identity.id, identity.name, emptyList()),
                        Uuid(3, 4),
                    ),
                )
                serverSession.receive()
                serverSession.receive()
                serverSession.send(packet as Packet)
            }
            val failure = assertFailsWith<MinecraftClientException> {
                MinecraftClientProtocol(
                    clientSession,
                    "localhost",
                    25_565,
                ).login(identity, handler = handler)
            }
            server.await()
            return failure
        }

        assertTrue(
            assertRejected(
                ConfigurationDisconnectPacket(TextComponent.literal("no")),
            ).message.orEmpty().contains("rejected Configuration"),
        )
        assertTrue(
            assertRejected(
                CodeOfConductPacket("rules"),
                object : MinecraftClientHandler {
                    override suspend fun acceptCodeOfConduct(
                        packet: CodeOfConductPacket,
                    ): Boolean = false
                },
            ).message.orEmpty().contains("not accepted"),
        )
    }

    @Test
    fun enforcesIndependentPacketLimitsForLoginConfigurationAndPlay() = runTest {
        assertPhaseLimit("Login packet limit") { client, server, identity ->
            server.receive()
            server.receive()
            val key = Identifier("test:cookie")
            server.send(LoginCookieRequestPacket(key))
            server.receive()
        }
        assertPhaseLimit("Configuration packet limit") { client, server, identity ->
            server.receive()
            server.receive()
            server.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid(3, 4),
                ),
            )
            server.receive()
            server.receive()
            server.send(FeatureFlagsPacket(emptySet()))
        }
        assertPhaseLimit("Play Login packet limit") { client, server, identity ->
            server.receive()
            server.receive()
            server.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid(3, 4),
                ),
            )
            server.receive()
            server.receive()
            server.send(FinishConfigurationPacket)
            server.receive()
            server.send(PlayClientboundKeepAlivePacket(1))
        }
    }

    private suspend fun assertPhaseLimit(
        expectedMessage: String,
        script: suspend (
            client: MinecraftSession,
            server: MinecraftSession,
            identity: MinecraftOfflineIdentity,
        ) -> Unit,
    ) {
        val (clientSession, serverSession) = sessionPair()
        val identity = MinecraftOfflineIdentity("LimitProbe")
        kotlinx.coroutines.coroutineScope {
            val server = async { script(clientSession, serverSession, identity) }
            val failure = assertFailsWith<MinecraftClientException> {
                MinecraftClientProtocol(
                    clientSession,
                    "localhost",
                    25_565,
                ).login(
                    identity,
                    options = MinecraftClientOptions(maximumPacketsPerPhase = 1),
                )
            }
            assertTrue(failure.message.orEmpty().contains(expectedMessage))
            server.await()
        }
    }

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

    private fun encryptionRequest(): EncryptionRequestPacket =
        EncryptionRequestPacket(
            serverId = "",
            publicKey = ByteString(byteArrayOf(1, 2, 3)),
            verifyToken = ByteString(byteArrayOf(4, 5, 6, 7)),
            shouldAuthenticate = true,
        )

    private fun playLogin(
        onlineMode: Boolean = false,
    ): PlayLoginPacket {
        val overworld = Identifier("overworld")
        return PlayLoginPacket(
            playerId = 1,
            hardcore = false,
            levels = setOf(overworld),
            maxPlayers = 20,
            chunkRadius = 8,
            simulationDistance = 8,
            reducedDebugInfo = false,
            showDeathScreen = true,
            limitedCrafting = false,
            spawnInfo = CommonPlayerSpawnInfo(
                dimensionTypeId = 0,
                dimension = overworld,
                seed = 0,
                gameMode =
                    com.hiczp.minecraft.protocol.model.type.GameMode.CREATIVE,
                previousGameMode = null,
                isDebug = false,
                isFlat = true,
                lastDeathLocation = null,
                portalCooldown = 0,
                seaLevel = 63,
            ),
            onlineMode = onlineMode,
            enforcesSecureChat = false,
        )
    }

    private object TestPrivateKey : MinecraftRsaPrivateKey

    private object IdentityCryptography : MinecraftCryptography {
        override fun secureRandomBytes(size: Int): ByteArray =
            ByteArray(size) { it.toByte() }

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
