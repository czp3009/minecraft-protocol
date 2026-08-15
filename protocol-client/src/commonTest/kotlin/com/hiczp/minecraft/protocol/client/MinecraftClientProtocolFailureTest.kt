package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.session.MinecraftSession
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.uuid.Uuid

class MinecraftClientProtocolFailureTest {
    @Test
    fun validatesOptionsIdentityAndDefaultHandlerContracts() = runTest {
        assertFailsWith<IllegalArgumentException> {
            MinecraftClientOptions(maximumPacketsPerPhase = 0)
        }

        val offline = MinecraftOfflineIdentity("ClientProbe")
        assertEquals(
            MinecraftOfflineIdentity.minecraftOfflineUuid("ClientProbe"),
            offline.id,
        )

        val online = onlineIdentity(
            name = "ClientProbe",
            id = Uuid.fromLongs(1, 2),
            accessToken = "secret-token",
        )
        assertTrue(online.toString().contains("secret-token"))

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
                serverSession.send(
                    StatusResponsePacket(
                        Json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {},
                        ),
                    ),
                )
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
                        LoginDisconnectPacket(
                            JsonTextComponent(
                                Json.encodeToString(
                                    JsonObject.serializer(),
                                    buildJsonObject {
                                        put("text", "no")
                                    },
                                ),
                            ),
                        ),
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

            run {
                val (clientSession, serverSession) = sessionPair()
                val identity = onlineIdentity(
                    name = "OnlineProbe",
                    id = Uuid.fromLongs(1, 2),
                    accessToken = "token",
                )
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
                    ).login(identity)
                }
                assertTrue(
                    failure.message.orEmpty()
                        .contains("no Session Server HttpClient"),
                )
                server.await()
            }
        }

    @Test
    fun completesOnlineEncryptionJoinAndEncryptedPlayEntry() = runTest {
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
        val identity = onlineIdentity(
            name = "OnlineProbe",
            id = Uuid.fromLongs(1, 2),
            accessToken = "token",
        )
        val keyPair = MinecraftServerKeyPair.generate()
        val (clientSession, serverSession) = sessionPair()
        val success = LoginSuccessPacket(
            GameProfile(identity.id, identity.name, emptyList()),
            Uuid.fromLongs(3, 4),
        )
        val play = playLogin(onlineMode = true)
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(
                LoginStartPacket(identity.name, identity.id),
                serverSession.receive(),
            )
            val challenge = keyPair.createChallenge()
            serverSession.send(challenge.toEncryptionRequestPacket())
            val response = assertIs<EncryptionResponsePacket>(
                serverSession.receive(),
            )
            val secret = challenge.accept(response).sharedSecret
            assertEquals(16, secret.size)
            serverSession.enableEncryption(secret)
            secret.fill(0)
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
        ).login(identity, httpClient)

        assertEquals(success, result.login)
        assertEquals(play, result.playLogin)
        assertEquals(1, joinRequests)
        server.await()
        httpClient.close()
    }

    @Test
    fun encryptedLoginSkipsSessionJoinWhenAuthenticationIsNotRequested() =
        runTest {
            var joinRequests = 0
            val httpClient = HttpClient(
                MockEngine {
                    joinRequests++
                    respond("", HttpStatusCode.NoContent)
                },
            ) {
                followRedirects = false
            }
            val onlineIdentity = onlineIdentity(
                name = "NoAuthProbe",
                id = Uuid.fromLongs(1, 2),
                accessToken = "token",
            )
            val offlineIdentity = MinecraftOfflineIdentity("EncryptedProbe")

            suspend fun complete(
                identity: MinecraftIdentity,
                sessionHttpClient: HttpClient?,
            ) {
                val keyPair = MinecraftServerKeyPair.generate()
                val (clientSession, serverSession) = sessionPair()
                val server = async {
                    serverSession.receive()
                    serverSession.receive()
                    val challenge = keyPair.createChallenge(
                        shouldAuthenticate = false,
                    )
                    serverSession.send(challenge.toEncryptionRequestPacket())
                    val response = assertIs<EncryptionResponsePacket>(
                        serverSession.receive(),
                    )
                    val secret = challenge.accept(response).sharedSecret
                    serverSession.enableEncryption(secret)
                    secret.fill(0)
                    serverSession.send(
                        LoginSuccessPacket(
                            GameProfile(identity.id, identity.name, emptyList()),
                            Uuid.fromLongs(3, 4),
                        ),
                    )
                    serverSession.receive()
                    serverSession.receive()
                    serverSession.send(FinishConfigurationPacket)
                    serverSession.receive()
                    serverSession.send(playLogin())
                }

                MinecraftClientProtocol(
                    clientSession,
                    "localhost",
                    25_565,
                ).login(identity, sessionHttpClient)
                server.await()
            }

            complete(onlineIdentity, httpClient)
            complete(offlineIdentity, null)

            assertEquals(0, joinRequests)
            httpClient.close()
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
                Uuid.fromLongs(3, 4),
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
                        Uuid.fromLongs(3, 4),
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
        assertPhaseLimit("Login packet limit") { _, server, _ ->
            server.receive()
            server.receive()
            val key = Identifier("test:cookie")
            server.send(LoginCookieRequestPacket(key))
            server.receive()
        }
        assertPhaseLimit("Configuration packet limit") { _, server, identity ->
            server.receive()
            server.receive()
            server.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid.fromLongs(3, 4),
                ),
            )
            server.receive()
            server.receive()
            server.send(FeatureFlagsPacket(emptySet()))
        }
        assertPhaseLimit("Play Login packet limit") { _, server, identity ->
            server.receive()
            server.receive()
            server.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid.fromLongs(3, 4),
                ),
            )
            server.receive()
            server.receive()
            server.send(FinishConfigurationPacket)
            server.receive()
            server.send(PlayClientboundKeepAlivePacket(1))
        }
    }

    @Test
    fun rejectsAmbiguousRegistriesAndInvalidPlayDimensionContext() = runTest {
        val missingLevel = assertPlayLoginRejected(
            playLogin(
                levels = setOf(Identifier("the_nether")),
            ),
        )
        assertTrue(
            missingLevel.message.orEmpty().contains("advertised levels"),
        )

        val missingDimensionType = assertPlayLoginRejected(
            playLogin(dimensionTypeId = Int.MAX_VALUE),
        )
        assertTrue(
            missingDimensionType.message.orEmpty()
                .contains("absent dimension-type"),
        )

        val (clientSession, serverSession) = sessionPair()
        val identity = MinecraftOfflineIdentity("DuplicateProbe")
        val duplicate = RegistryDataPacket(
            Identifier("test:duplicate"),
            emptyList(),
        )
        val server = async {
            serverSession.receive()
            serverSession.receive()
            serverSession.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid.fromLongs(3, 4),
                ),
            )
            serverSession.receive()
            serverSession.receive()
            serverSession.send(duplicate)
            serverSession.send(duplicate)
        }
        val failure = assertFailsWith<MinecraftClientException> {
            MinecraftClientProtocol(
                clientSession,
                "localhost",
                25_565,
            ).login(identity)
        }
        assertTrue(failure.message.orEmpty().contains("duplicate registry"))
        server.await()
    }

    private suspend fun assertPlayLoginRejected(
        playLogin: PlayLoginPacket,
    ): MinecraftClientException =
        coroutineScope {
            val (clientSession, serverSession) = sessionPair()
            val identity = MinecraftOfflineIdentity("InvalidPlayProbe")
            val server = async {
                serverSession.receive()
                serverSession.receive()
                serverSession.send(
                    LoginSuccessPacket(
                        GameProfile(identity.id, identity.name, emptyList()),
                        Uuid.fromLongs(3, 4),
                    ),
                )
                serverSession.receive()
                serverSession.receive()
                serverSession.send(FinishConfigurationPacket)
                serverSession.receive()
                serverSession.send(playLogin)
            }
            val failure = assertFailsWith<MinecraftClientException> {
                MinecraftClientProtocol(
                    clientSession,
                    "localhost",
                    25_565,
                ).login(identity)
            }
            server.await()
            failure
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
        coroutineScope {
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

    private fun encryptionRequest(
        shouldAuthenticate: Boolean = true,
    ): EncryptionRequestPacket =
        EncryptionRequestPacket(
            serverId = "",
            publicKey = ByteString(byteArrayOf(1, 2, 3)),
            verifyToken = ByteString(byteArrayOf(4, 5, 6, 7)),
            shouldAuthenticate = shouldAuthenticate,
        )

    private fun playLogin(
        onlineMode: Boolean = false,
        dimension: Identifier = Identifier("overworld"),
        levels: Set<Identifier> = setOf(dimension),
        dimensionTypeId: Int = 0,
    ): PlayLoginPacket {
        return PlayLoginPacket(
            playerId = 1,
            hardcore = false,
            levels = levels,
            maxPlayers = 20,
            chunkRadius = 8,
            simulationDistance = 8,
            reducedDebugInfo = false,
            showDeathScreen = true,
            limitedCrafting = false,
            spawnInfo = CommonPlayerSpawnInfo(
                dimensionTypeId = dimensionTypeId,
                dimension = dimension,
                seed = 0,
                gameMode = GameMode.CREATIVE,
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

    private fun onlineIdentity(
        name: String,
        id: Uuid,
        accessToken: String,
    ): MinecraftOnlineIdentity = MinecraftOnlineIdentity(
        name = name,
        id = id,
        accessToken = accessToken,
    )
}
