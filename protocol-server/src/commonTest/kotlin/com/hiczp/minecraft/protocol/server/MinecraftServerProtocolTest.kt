package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.client.MinecraftClientProtocol
import com.hiczp.minecraft.protocol.client.MinecraftOnlineIdentity
import com.hiczp.minecraft.protocol.data.ProtocolDataSet
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import kotlin.uuid.Uuid

class MinecraftServerProtocolTest {
    @Test
    fun validatesConfigurationAndBuildsStructuredStatusJson() {
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(compressionThreshold = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(maximumPlayers = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(viewDistance = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfiguration(viewDistance = 33)
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
            hardcore = true,
            gameMode = GameMode.SPECTATOR,
            difficulty = Difficulty.HARD,
            difficultyLocked = true,
            enforcesSecureChat = true,
        )
        val json = configuration.statusJson(onlinePlayers = 3)
        val status = Json.parseToJsonElement(json).jsonObject
        val players = status.getValue("players").jsonObject
        assertEquals(7, players.getValue("max").jsonPrimitive.int)
        assertEquals(3, players.getValue("online").jsonPrimitive.int)
        assertEquals(
            "\"line\\\n\t\u0001",
            status.getValue("description").jsonObject
                .getValue("text").jsonPrimitive.content,
        )
        assertFalse(
            status.getValue("enforcesSecureChat").jsonPrimitive
                .content.toBooleanStrict(),
        )
        assertFailsWith<IllegalArgumentException> {
            configuration.statusJson(onlinePlayers = -1)
        }
        val login = configuration.playLogin(
            GameProfile(Uuid.fromLongs(1, 2), "Probe", emptyList()),
        )
        assertEquals(configuration.viewDistance, login.chunkRadius)
        assertEquals(configuration.simulationDistance, login.simulationDistance)
        assertFalse(login.onlineMode)
        assertTrue(login.hardcore)
        assertEquals(configuration.gameMode, login.spawnInfo.gameMode)
        assertFalse(login.enforcesSecureChat)
        assertEquals(10, MinecraftServerConfiguration().viewDistance)
        assertEquals(10, MinecraftServerConfiguration().simulationDistance)
        assertEquals("A Minecraft Server", MinecraftServerConfiguration().statusDescription)
        assertEquals(
            "127.0.0.1",
            byteArrayOf(127, 0, 0, 1).toNumericIpAddress(),
        )
        assertEquals(
            "2001:db8:0:0:0:0:0:1",
            byteArrayOf(
                0x20,
                0x01,
                0x0d,
                0xb8.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                1,
            ).toNumericIpAddress(),
        )
        assertFailsWith<IllegalArgumentException> {
            byteArrayOf(1, 2, 3).toNumericIpAddress()
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerConfigurationTask(" ", emptyList()) { true }
        }
        assertEquals(
            2,
            MinecraftServerConfiguration(
                compressionThreshold = null,
                viewDistance = 2,
            ).viewDistance,
        )
        assertEquals(
            32,
            MinecraftServerConfiguration(
                compressionThreshold = null,
                viewDistance = 32,
            ).viewDistance,
        )
    }

    @Test
    fun rejectsInvalidHandlerProducedPlayLoginContext() {
        val protocol = MinecraftServerProtocol(
            sessionPair().second,
            MinecraftServerConfiguration(compressionThreshold = null),
        )
        val valid = protocol.configuration.playLogin(
            GameProfile(Uuid.fromLongs(1, 2), "Probe", emptyList()),
        )

        protocol.validatePlayLogin(valid.copy(chunkRadius = 2))
        protocol.validatePlayLogin(valid.copy(chunkRadius = 32))

        listOf(
            valid.copy(maxPlayers = -1),
            valid.copy(chunkRadius = 1),
            valid.copy(chunkRadius = 33),
            valid.copy(simulationDistance = -1),
            valid.copy(levels = emptySet()),
            valid.copy(
                spawnInfo = valid.spawnInfo.copy(
                    dimensionTypeId = Int.MAX_VALUE,
                ),
            ),
        ).forEach { invalid ->
            assertFailsWith<MinecraftServerException> {
                protocol.validatePlayLogin(invalid)
            }
        }

        val malformedDimensionData =
            object : ProtocolDataSet by VanillaProtocolData {
                override fun registryPackets(
                    clientKnownPacks: List<KnownPack>,
                ): List<RegistryDataPacket> =
                    VanillaProtocolData.registryPackets(clientKnownPacks)
                        .map { registry ->
                            if (
                                registry.registryId !=
                                Identifier("dimension_type")
                            ) {
                                registry
                            } else {
                                registry.copy(
                                    entries = registry.entries.mapIndexed { index, entry ->
                                        if (
                                            index !=
                                            valid.spawnInfo.dimensionTypeId
                                        ) {
                                            entry
                                        } else {
                                            entry.copy(
                                                data = NbtCompound(
                                                    checkNotNull(
                                                        entry.data as?
                                                                NbtCompound,
                                                    ).value + (
                                                            "height" to
                                                                    NbtInt(1)
                                                            ),
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }
            }
        val malformedProtocol = MinecraftServerProtocol(
            sessionPair().second,
            MinecraftServerConfiguration(
                compressionThreshold = null,
                protocolData = malformedDimensionData,
            ),
        )
        assertFailsWith<MinecraftServerException> {
            malformedProtocol.validatePlayLogin(valid)
        }
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

        client.send(
            handshake(
                HandshakeNextState.STATUS,
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION + 1,
            ),
        )
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
    fun disabledStatusAndTransfersFollowOfficialHandshakePolicy() = runTest {
        run {
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(
                            compressionThreshold = null,
                            statusEnabled = false,
                        ),
                    ).negotiate()
                }
            }

            client.send(handshake(HandshakeNextState.STATUS))

            assertTrue(
                negotiation.await().message.orEmpty()
                    .contains("Status requests are disabled"),
            )
        }

        run {
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(
                            compressionThreshold = null,
                            acceptsTransfers = false,
                        ),
                    ).negotiate()
                }
            }

            client.send(handshake(HandshakeNextState.TRANSFER))

            val disconnect = assertIs<LoginDisconnectPacket>(client.receive())
            assertTrue(disconnect.reason.json.contains("transfers_disabled"))
            assertTrue(
                negotiation.await().message.orEmpty()
                    .contains("Transfer connections are disabled"),
            )
        }
    }

    @Test
    fun enabledTransfersRemainVisibleToEveryAdmissionStage() = runTest {
        val transferFlags = mutableListOf<Boolean>()
        val handler = object : MinecraftServerHandler {
            override suspend fun acceptProfile(
                profile: GameProfile,
                transferred: Boolean,
            ): Boolean {
                transferFlags += transferred
                return true
            }

            override suspend fun playLogin(
                profile: GameProfile,
                clientInformation: ClientInformation,
                transferred: Boolean,
                configuration: MinecraftServerConfiguration,
            ): PlayLoginPacket {
                transferFlags += transferred
                return configuration.playLogin(profile)
            }

            override suspend fun configurationPackets(
                profile: GameProfile,
                clientInformation: ClientInformation,
                acceptedKnownPacks: List<KnownPack>,
                transferred: Boolean,
                configuration: MinecraftServerConfiguration,
            ): List<Packet> {
                transferFlags += transferred
                return emptyList()
            }
        }
        val (client, server) = sessionPair()
        val negotiation = async {
            MinecraftServerProtocol(
                server,
                MinecraftServerConfiguration(
                    compressionThreshold = null,
                    acceptsTransfers = true,
                ),
                handler,
            ).negotiate()
        }

        val login = completeOfflineLogin(client, HandshakeNextState.TRANSFER)
        val result = assertIs<MinecraftServerNegotiationResult.PlayReady>(
            negotiation.await(),
        )

        assertTrue(result.transferred)
        assertEquals(login, result.login)
        assertEquals(listOf(true, true, true), transferFlags)
    }

    @Test
    fun rejectsUnsupportedLoginAndTransferProtocolVersions() = runTest {
        for (nextState in listOf(
            HandshakeNextState.LOGIN,
            HandshakeNextState.TRANSFER,
        )) {
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(
                            compressionThreshold = null,
                            acceptsTransfers = true,
                        ),
                    ).negotiate()
                }
            }

            client.send(
                handshake(
                    nextState,
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION - 1,
                ),
            )

            assertTrue(
                assertIs<LoginDisconnectPacket>(client.receive())
                    .reason.json.contains("Unsupported protocol version"),
            )
            val failure = negotiation.await()
            assertTrue(failure.message.orEmpty().contains("Unsupported protocol"))
            assertTrue(
                failure.message.orEmpty().contains(
                    MinecraftProtocol.PROTOCOL_VERSION.toString(),
                ),
            )
        }
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
            client.send(LoginStartPacket("Rejected", Uuid.fromLongs(0, 1)))
            assertTrue(
                assertIs<LoginDisconnectPacket>(client.receive())
                    .reason.json.contains("server policy"),
            )
            assertTrue(
                negotiation.await().message.orEmpty().contains("was rejected"),
            )
        }

        run {
            val customReason =
                JsonTextComponent("""{"translate":"test.custom_rejection"}""")
            val (client, server) = sessionPair()
            val negotiation = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        server,
                        MinecraftServerConfiguration(compressionThreshold = null),
                        object : MinecraftServerHandler {
                            override suspend fun profileRejection(
                                profile: GameProfile,
                                transferred: Boolean,
                                configuration: MinecraftServerConfiguration,
                            ): JsonTextComponent = customReason
                        },
                    ).negotiate()
                }
            }
            client.send(handshake(HandshakeNextState.LOGIN))
            client.send(LoginStartPacket("CustomReject", Uuid.fromLongs(0, 2)))

            assertEquals(
                LoginDisconnectPacket(customReason),
                client.receive(),
            )
            assertTrue(
                negotiation.await().message.orEmpty()
                    .contains("test.custom_rejection"),
            )
        }
    }

    @Test
    fun onlineAuthenticationNegotiatesEncryptedPlayThroughSessionServices() =
        runTest {
            val identityId = Uuid.fromLongs(0x1020, 0x3040)
            val clientService = MinecraftSessionService(
                HttpClient(
                    MockEngine {
                        respond("", HttpStatusCode.NoContent)
                    },
                ),
            )
            var hasJoinedRequests = 0
            var hasJoinedIpAddress: String? = null
            val serverService = MinecraftSessionService(
                HttpClient(
                    MockEngine { request ->
                        hasJoinedRequests++
                        hasJoinedIpAddress = request.url.parameters["ip"]
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
                preventProxyConnections = true,
                enforcesSecureChat = true,
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
                MinecraftServerProtocol(
                    server,
                    configuration,
                    clientIpAddress = "203.0.113.42",
                ).negotiate()
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
            assertTrue(negotiation.login.enforcesSecureChat)
            assertEquals(1, hasJoinedRequests)
            assertEquals("203.0.113.42", hasJoinedIpAddress)

            val (missingIpClient, missingIpServer) = sessionPair()
            val missingIpResult = async {
                assertFailsWith<MinecraftServerException> {
                    MinecraftServerProtocol(
                        missingIpServer,
                        configuration,
                    ).negotiate()
                }
            }
            missingIpClient.send(handshake(HandshakeNextState.LOGIN))
            missingIpClient.send(
                LoginStartPacket("MissingIp", Uuid.fromLongs(0x50, 0x60)),
            )
            val request = assertIs<EncryptionRequestPacket>(
                missingIpClient.receive(),
            )
            val encryption = MinecraftEncryption.answerServerChallenge(
                request,
                IdentityCryptography,
            )
            missingIpClient.send(encryption.response)
            missingIpClient.enableEncryption(encryption.sharedSecret)

            assertTrue(
                missingIpResult.await().message.orEmpty()
                    .contains("requires the client IP address"),
            )
            assertEquals(1, hasJoinedRequests)
            clientService.httpClient.close()
            serverService.httpClient.close()
        }

    @Test
    fun configurationExtensionsAllowOptionalPacketsAndClientResponses() =
        runTest {
            val observed = mutableListOf<Packet>()
            val acceptedResponseObserved = CompletableDeferred<Unit>()
            val resourcePackId = Uuid.fromLongs(0x10, 0x20)
            val handler = object : MinecraftServerHandler {
                override suspend fun configurationPackets(
                    profile: GameProfile,
                    clientInformation: ClientInformation,
                    acceptedKnownPacks: List<KnownPack>,
                    transferred: Boolean,
                    configuration: MinecraftServerConfiguration,
                ): List<Packet> =
                    listOf(ConfigurationServerLinksPacket(emptyList()))

                override suspend fun configurationTasks(
                    profile: GameProfile,
                    clientInformation: ClientInformation,
                    acceptedKnownPacks: List<KnownPack>,
                    transferred: Boolean,
                    configuration: MinecraftServerConfiguration,
                ): List<MinecraftServerConfigurationTask> =
                    listOf(
                        MinecraftServerConfigurationTask(
                            name = "code-of-conduct",
                            packets = listOf(
                                CodeOfConductPacket("Be kind"),
                            ),
                        ) { packet ->
                            packet === AcceptCodeOfConductPacket
                        },
                        MinecraftServerConfigurationTask(
                            name = "resource-pack",
                            packets = listOf(
                                ConfigurationAddResourcePackPacket(
                                    uuid = resourcePackId,
                                    url =
                                        "https://example.invalid/resources.zip",
                                    hash = "",
                                    forced = false,
                                    promptMessage =
                                        TextComponent.literal("Optional"),
                                ),
                            ),
                        ) { packet ->
                            packet is ConfigurationResourcePackResponsePacket &&
                                    packet.uuid == resourcePackId &&
                                    packet.result ==
                                    ResourcePackResult.SUCCESSFULLY_DOWNLOADED
                        },
                    )

                override suspend fun onPacket(packet: Packet) {
                    observed += packet
                    if (
                        packet is ConfigurationResourcePackResponsePacket &&
                        packet.uuid == resourcePackId &&
                        packet.result == ResourcePackResult.ACCEPTED
                    ) {
                        acceptedResponseObserved.complete(Unit)
                    }
                }
            }
            val (client, server) = sessionPair()
            val negotiation = async {
                MinecraftServerProtocol(
                    server,
                    MinecraftServerConfiguration(compressionThreshold = null),
                    handler,
                ).negotiate()
            }

            reachConfigurationExtensions(client)
            assertIs<ConfigurationServerLinksPacket>(client.receive())
            assertEquals(CodeOfConductPacket("Be kind"), client.receive())
            client.send(AcceptCodeOfConductPacket)
            val pack = assertIs<ConfigurationAddResourcePackPacket>(
                client.receive(),
            )
            assertEquals(resourcePackId, pack.uuid)
            client.send(
                ConfigurationResourcePackResponsePacket(
                    resourcePackId,
                    ResourcePackResult.ACCEPTED,
                ),
            )
            acceptedResponseObserved.await()
            assertFalse(negotiation.isCompleted)
            client.send(
                ConfigurationResourcePackResponsePacket(
                    resourcePackId,
                    ResourcePackResult.SUCCESSFULLY_DOWNLOADED,
                ),
            )
            assertEquals(FinishConfigurationPacket, client.receive())
            client.send(AcknowledgeFinishConfigurationPacket)
            assertIs<PlayLoginPacket>(client.receive())

            negotiation.await()
            assertEquals(
                listOf<Packet>(
                    AcceptCodeOfConductPacket,
                    ConfigurationResourcePackResponsePacket(
                        resourcePackId,
                        ResourcePackResult.ACCEPTED,
                    ),
                    ConfigurationResourcePackResponsePacket(
                        resourcePackId,
                        ResourcePackResult.SUCCESSFULLY_DOWNLOADED,
                    ),
                ),
                observed,
            )
        }

    @Test
    fun configurationExtensionsRejectWrongStateDirectionAndManagedPackets() =
        runTest {
            val invalidPackets = listOf(
                StatusResponsePacket("{}"),
                ConfigurationServerboundPluginMessagePacket(
                    CustomPayload.Unknown(
                        Identifier("test:serverbound"),
                        ByteString(byteArrayOf(1)),
                    ),
                ),
                FinishConfigurationPacket,
            )
            invalidPackets.forEach { invalid ->
                val (client, server) = sessionPair()
                val negotiation = async {
                    assertFailsWith<MinecraftServerException> {
                        MinecraftServerProtocol(
                            server,
                            MinecraftServerConfiguration(
                                compressionThreshold = null,
                            ),
                            object : MinecraftServerHandler {
                                override suspend fun configurationPackets(
                                    profile: GameProfile,
                                    clientInformation: ClientInformation,
                                    acceptedKnownPacks: List<KnownPack>,
                                    transferred: Boolean,
                                    configuration: MinecraftServerConfiguration,
                                ): List<Packet> = listOf(invalid)
                            },
                        ).negotiate()
                    }
                }

                reachConfigurationExtensions(client)

                val failure = negotiation.await()
                assertTrue(
                    failure.message.orEmpty().contains(
                        if (invalid === FinishConfigurationPacket) {
                            "managed by MinecraftServerProtocol"
                        } else {
                            "clientbound Configuration packet"
                        },
                    ),
                )
            }
        }

    @Test
    fun limitsClientInformationAndKnownPackSearchIndependently() = runTest {
        suspend fun reachConfiguration(
            client: MinecraftSession,
        ) {
            client.send(handshake(HandshakeNextState.LOGIN))
            client.send(LoginStartPacket("LimitProbe", Uuid.fromLongs(0, 1)))
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
            assertEquals(ignored, observed.single())
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
            assertEquals(ignored, observed.single())
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

    private suspend fun reachConfigurationExtensions(
        client: MinecraftSession,
        nextState: HandshakeNextState = HandshakeNextState.LOGIN,
    ) {
        client.send(handshake(nextState))
        client.send(LoginStartPacket("ConfigProbe", Uuid.fromLongs(0, 1)))
        assertIs<LoginSuccessPacket>(client.receive())
        client.send(LoginAcknowledgedPacket)
        client.send(ConfigurationClientInformationPacket(clientInformation()))
        assertIs<FeatureFlagsPacket>(client.receive())
        val packs = assertIs<ConfigurationClientboundKnownPacksPacket>(
            client.receive(),
        )
        client.send(
            ConfigurationServerboundKnownPacksPacket(packs.knownPacks),
        )
        repeat(VanillaProtocolData.registryPackets(packs.knownPacks).size) {
            assertIs<RegistryDataPacket>(client.receive())
        }
        assertIs<ConfigurationUpdateTagsPacket>(client.receive())
    }

    private suspend fun completeOfflineLogin(
        client: MinecraftSession,
        nextState: HandshakeNextState,
    ): PlayLoginPacket {
        reachConfigurationExtensions(client, nextState)
        assertEquals(FinishConfigurationPacket, client.receive())
        client.send(AcknowledgeFinishConfigurationPacket)
        return assertIs(client.receive())
    }

    private fun handshake(
        nextState: HandshakeNextState,
        protocolVersion: Int = MinecraftProtocol.PROTOCOL_VERSION,
    ): HandshakePacket =
        HandshakePacket(
            protocolVersion = protocolVersion,
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
