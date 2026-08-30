package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.*
import com.hiczp.minecraft.protocol.session.MinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketConnection
import com.hiczp.minecraft.protocol.session.RoutedCustomPayload
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration

class NeoForgeNegotiationProfileTest {
    @Test
    fun scriptedProfilesNegotiateTasksRegistriesAndDynamicPlayPackets() = runTest {
        val customCodecs = testPlayCodecs()
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            NeoForgeProtocol.packetCodecs + customCodecs,
        )
        val serverToClient = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val clientToServer = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val neoForgeTestClientConnection = NeoForgeTestClientConnection(
            serverToClient,
            clientToServer,
            packetRegistry,
        )
        val neoForgeTestServerConnection = NeoForgeTestServerConnection(
            clientToServer,
            serverToClient,
            packetRegistry,
        )
        val staticRegistrySchema = testStaticSchema()
        val neoForgeFrozenRegistrySync = NeoForgeFrozenRegistrySync(
            listOf(
                NeoForgeFrozenRegistryPacket(
                    Identifier("block"),
                    NeoForgeRegistrySnapshot(
                        linkedMapOf(
                            0 to Identifier("mod:new_block"),
                            1 to Identifier("stone"),
                        ),
                        mapOf(
                            Identifier("mod:block") to
                                    Identifier("mod:new_block"),
                        ),
                    ),
                ),
            ),
        )
        val sharedProtocolRegistryContext = staticRegistrySchema.resolve(
            neoForgeFrozenRegistrySync.remoteRegistrySnapshot,
        )
        val knownDataMaps = mapOf(
            Identifier("item") to listOf(
                NeoForgeKnownDataMap(Identifier("mod:properties"), true),
            ),
        )
        val enums = listOf(
            NeoForgeEnumEntry(
                "mod.Example",
                NeoForgeNetworkCheck.BIDIRECTIONAL,
                NeoForgeEnumExtensionData(1, 2, listOf("MOD_VALUE")),
            ),
        )
        val flags = setOf(Identifier("mod:feature"))
        val neoForgeNetworkConfiguration = testNetworkConfiguration()
        val neoForgeClientProfile = NeoForgeClientProfile(
            NeoForgeClientProfileDefinition(
                staticRegistrySchema = staticRegistrySchema,
                neoForgeNetworkConfiguration = neoForgeNetworkConfiguration,
                knownDataMaps = knownDataMaps,
                extensibleEnums = enums,
                featureFlags = flags,
            ),
        )
        val neoForgeServerProfile = NeoForgeServerProfile(
            NeoForgeServerProfileDefinition(
                neoForgeNetworkConfiguration = neoForgeNetworkConfiguration,
                neoForgeFrozenRegistrySync = neoForgeFrozenRegistrySync,
                protocolRegistryContext = sharedProtocolRegistryContext,
                configFiles = listOf(
                    NeoForgeConfigFilePacket(
                        "server.toml",
                        ByteString(byteArrayOf(1, 2, 3)),
                    ),
                ),
                knownDataMaps = knownDataMaps,
                extensibleEnums = enums,
                featureFlags = flags,
            ),
        )
        neoForgeClientProfile.begin(neoForgeTestClientConnection)
        neoForgeServerProfile.begin(neoForgeTestServerConnection)

        val initial = async {
            neoForgeServerProfile.negotiateConfigurationStart(neoForgeTestServerConnection)
        }
        repeat(6) {
            val clientboundPacket = neoForgeTestClientConnection.incoming.receive()
            if (clientboundPacket is ConfigurationPingPacket) {
                neoForgeTestClientConnection.outgoing.send(ConfigurationPongPacket(clientboundPacket.id))
            } else {
                assertTrue(
                    neoForgeClientProfile.handleConfigurationPacket(
                        neoForgeTestClientConnection,
                        clientboundPacket,
                    ),
                )
            }
        }
        initial.await()

        val early = async {
            neoForgeServerProfile.negotiateEarlyConfiguration(neoForgeTestServerConnection)
        }
        repeat(3) {
            assertTrue(
                neoForgeClientProfile.handleConfigurationPacket(
                    neoForgeTestClientConnection,
                    neoForgeTestClientConnection.incoming.receive(),
                ),
            )
        }
        early.await()

        val late = async {
            neoForgeServerProfile.negotiateConfiguration(neoForgeTestServerConnection)
        }
        repeat(6) {
            assertTrue(
                neoForgeClientProfile.handleConfigurationPacket(
                    neoForgeTestClientConnection,
                    neoForgeTestClientConnection.incoming.receive(),
                ),
            )
        }
        late.await()

        val clientProtocolRegistryContext = neoForgeClientProfile.resolveProtocolRegistryContext(
            staticRegistrySchema.resolve().withRegistrySize(
                ProtocolRegistryContext.BIOME_REGISTRY,
                4,
            ).withChunkSectionCount(24),
        )
        assertEquals(
            Identifier("mod:new_block"),
            clientProtocolRegistryContext.blockStates.first().block,
        )
        assertEquals(4, clientProtocolRegistryContext.biomeRegistrySize)
        assertEquals(24, clientProtocolRegistryContext.chunkSectionCount)

        val serverProtocolRegistryContext = neoForgeServerProfile.resolveProtocolRegistryContext(
            ProtocolRegistryContext.Empty.withChunkSectionCount(24),
        )
        assertSame(sharedProtocolRegistryContext.registries, serverProtocolRegistryContext.registries)
        assertSame(sharedProtocolRegistryContext.blockStates, serverProtocolRegistryContext.blockStates)

        neoForgeClientProfile.preparePlay(neoForgeTestClientConnection)
        neoForgeTestClientConnection.currentState = ConnectionState.PLAY
        neoForgeTestServerConnection.currentState = ConnectionState.PLAY
        neoForgeServerProfile.preparePlay(neoForgeTestServerConnection)

        val customRoutes = customCodecs.map(PacketCodecRegistration<out Packet>::packetRouteKey)
        assertTrue(customRoutes.all(neoForgeTestClientConnection.activeExtensionRoutes::contains))
        assertTrue(customRoutes.all(neoForgeTestServerConnection.activeExtensionRoutes::contains))

        val clientResult = assertIs<NeoForgeNegotiationResult>(
            neoForgeClientProfile.complete(neoForgeTestClientConnection),
        )
        val serverResult = assertIs<NeoForgeNegotiationResult>(
            neoForgeServerProfile.complete(neoForgeTestServerConnection),
        )
        assertTrue(clientResult.neoForgePeer)
        assertTrue(serverResult.neoForgePeer)
        assertTrue(clientResult.registriesSynchronized)
        assertTrue(serverResult.registriesSynchronized)
        assertEquals(1, clientResult.commonVersion)
        assertEquals(1, serverResult.commonVersion)
        assertEquals("server.toml", clientResult.configFiles.single().fileName)
    }

    @Test
    fun mandatoryNetworkMismatchThrowsAndDoesNotSendFailurePacket() = runTest {
        val requiredChannel = Identifier("mod:required")
        val requiredPacketCodecs = listOf(
            PacketCodecRegistration.clientboundCustomPayload(
                ConnectionState.CONFIGURATION,
                requiredChannel,
                RequiredNeoForgePacket::class,
                KotlinxPacketBodyCodec(RequiredNeoForgePacket.serializer()),
            ),
            PacketCodecRegistration.serverboundCustomPayload(
                ConnectionState.CONFIGURATION,
                requiredChannel,
                RequiredNeoForgePacket::class,
                KotlinxPacketBodyCodec(RequiredNeoForgePacket.serializer()),
            ),
        )
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            NeoForgeProtocol.packetCodecs + requiredPacketCodecs,
        )
        val incoming = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val neoForgeTestServerConnection = NeoForgeTestServerConnection(
            incoming,
            outgoing,
            packetRegistry,
        )
        val neoForgeServerProfile = NeoForgeServerProfile(
            NeoForgeServerProfileDefinition(
                neoForgeNetworkConfiguration = NeoForgeNetworkConfiguration(
                    configuration = listOf(
                        NeoForgeNetworkComponent(
                            requiredChannel,
                            "1",
                            optional = false,
                        ),
                    ),
                ),
            ),
        )
        neoForgeServerProfile.begin(neoForgeTestServerConnection)
        val failure = supervisorScope {
            val negotiation = async {
                neoForgeServerProfile.negotiateConfigurationStart(neoForgeTestServerConnection)
            }
            repeat(4) { outgoing.receive() }
            incoming.send(NeoForgeNetworkConfiguration().neoForgeModdedNetworkQueryPacket)
            assertFailsWith<NeoForgeNetworkNegotiationException> {
                negotiation.await()
            }
        }
        assertTrue(requiredChannel in failure.failurePacket.failureReasons)
        assertFalse(
            outgoing.tryReceive().getOrNull() is
                    NeoForgeModdedNetworkSetupFailedPacket,
        )
    }

    @Test
    fun outOfOrderRegistryPacketThrowsWithoutReply() = runTest {
        val incoming = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            NeoForgeProtocol.packetCodecs,
        )
        val neoForgeTestClientConnection = NeoForgeTestClientConnection(
            incoming,
            outgoing,
            packetRegistry,
        )
        val neoForgeClientProfile = NeoForgeClientProfile(
            NeoForgeClientProfileDefinition(testStaticSchema()),
        )
        neoForgeClientProfile.begin(neoForgeTestClientConnection)

        assertFailsWith<NeoForgeNegotiationException> {
            neoForgeClientProfile.handleConfigurationPacket(
                neoForgeTestClientConnection,
                NeoForgeFrozenRegistryPacket(
                    Identifier("block"),
                    NeoForgeRegistrySnapshot(
                        mapOf(0 to Identifier("stone")),
                    ),
                ),
            )
        }
        assertTrue(outgoing.tryReceive().isFailure)
    }
}

@Serializable
private data class TestNeoForgeClientboundPlayPacket(
    val value: Int,
) : ClientboundPacket.Extension

@Serializable
private data class TestNeoForgeServerboundPlayPacket(
    val value: Int,
) : ServerboundPacket.Extension

@Serializable
private data class RequiredNeoForgePacket(
    val value: Int,
) : NeoForgeBidirectionalPacket

private fun testPlayCodecs(): List<PacketCodecRegistration<out Packet>> = listOf(
    PacketCodecRegistration.clientboundCustomPayload(
        ConnectionState.PLAY,
        Identifier("mod:to_client"),
        TestNeoForgeClientboundPlayPacket::class,
        KotlinxPacketBodyCodec(TestNeoForgeClientboundPlayPacket.serializer()),
    ),
    PacketCodecRegistration.serverboundCustomPayload(
        ConnectionState.PLAY,
        Identifier("mod:to_server"),
        TestNeoForgeServerboundPlayPacket::class,
        KotlinxPacketBodyCodec(TestNeoForgeServerboundPlayPacket.serializer()),
    ),
)

private fun testNetworkConfiguration(): NeoForgeNetworkConfiguration =
    NeoForgeNetworkConfiguration(
        play = listOf(
            NeoForgeNetworkComponent(
                Identifier("mod:to_client"),
                "1",
                NeoForgePacketFlow.CLIENTBOUND,
            ),
            NeoForgeNetworkComponent(
                Identifier("mod:to_server"),
                "1",
                NeoForgePacketFlow.SERVERBOUND,
            ),
        ),
    )

private fun testStaticSchema(): StaticRegistrySchema = StaticRegistrySchema(
    registries = mapOf(
        Identifier("block") to listOf(
            Identifier("stone"),
            Identifier("mod:block"),
        ),
        ProtocolRegistryContext.BIOME_REGISTRY to listOf(
            MinecraftBiomeIds.PLAINS,
        ),
    ),
    blocks = listOf(
        StaticBlockSchema(
            Identifier("stone"),
            listOf(StaticBlockState(emptyMap(), isDefault = true)),
        ),
        StaticBlockSchema(
            Identifier("mod:block"),
            listOf(
                StaticBlockState(
                    mapOf("powered" to "false"),
                    isDefault = true,
                ),
                StaticBlockState(
                    mapOf("powered" to "true"),
                    isDefault = false,
                ),
            ),
        ),
    ),
)

private abstract class NeoForgeTestConnection<Incoming : Packet, Outgoing : Packet>(
    override val incoming: Channel<Incoming>,
    override val outgoing: Channel<Outgoing>,
    private val packetRegistry: PacketRegistry,
    private val incomingDirection: PacketDirection,
    private val outgoingDirection: PacketDirection,
) : MinecraftPacketConnection<Incoming, Outgoing> {
    var currentState: ConnectionState = ConnectionState.CONFIGURATION
    private var mutableProtocolRegistryContext = ProtocolRegistryContext.Empty
    private var activeRoutes = emptySet<PacketRouteKey>()
    private val format = MinecraftProtocolFormat.Default

    override val connectionState: ConnectionState
        get() = currentState

    override val protocolRegistryContext: ProtocolRegistryContext
        get() = mutableProtocolRegistryContext

    override val declaredExtensionRoutes: Set<PacketRouteKey>
        get() = packetRegistry.declaredExtensionRoutes

    override val activeExtensionRoutes: Set<PacketRouteKey>
        get() = activeRoutes

    override val isOpen: Boolean = true

    override suspend fun awaitClosed() = Unit

    override fun installProtocolRegistryContext(protocolRegistryContext: ProtocolRegistryContext) {
        mutableProtocolRegistryContext = protocolRegistryContext
    }

    override fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        require(routes.all(declaredExtensionRoutes::contains))
        activeRoutes = routes.toSet()
    }

    override fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload {
        val customPayload = packetRegistry.extensionRoute(
            packet,
            currentState,
            outgoingDirection,
            outerPacketId = 0,
        ) as PacketRoute.CustomPayload
        require(customPayload.packetRouteKey in activeRoutes)
        val buffer = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            currentState,
            outgoingDirection,
            buffer,
            format,
        )
        return RoutedCustomPayload(
            customPayload,
            ByteString(buffer.readByteArray()),
        )
    }

    override fun decodeCustomPayload(routedCustomPayload: RoutedCustomPayload): Incoming {
        require(routedCustomPayload.route.packetDirection == incomingDirection)
        require(routedCustomPayload.route.packetRouteKey in activeRoutes)
        val byteArray = routedCustomPayload.data.toByteArray()
        val buffer = Buffer().apply { write(byteArray) }
        @Suppress("UNCHECKED_CAST")
        return packetRegistry.decodeExtensionPayloadFromSource(
            routedCustomPayload.route,
            buffer,
            byteArray.size,
            format,
        ) as Incoming
    }

    override suspend fun awaitState(connectionState: ConnectionState) = Unit

    override suspend fun flush() = Unit

    override fun requestFlush() = Unit

    override fun close() = Unit
}

private class NeoForgeTestClientConnection(
    incoming: Channel<ClientboundPacket>,
    outgoing: Channel<ServerboundPacket>,
    packetRegistry: PacketRegistry,
) : NeoForgeTestConnection<ClientboundPacket, ServerboundPacket>(
    incoming,
    outgoing,
    packetRegistry,
    PacketDirection.CLIENTBOUND,
    PacketDirection.SERVERBOUND,
), MinecraftClientPacketConnection {
    override fun prepareOutboundEncryption(sharedSecret: ByteArray) = Unit
}

private class NeoForgeTestServerConnection(
    incoming: Channel<ServerboundPacket>,
    outgoing: Channel<ClientboundPacket>,
    packetRegistry: PacketRegistry,
) : NeoForgeTestConnection<ServerboundPacket, ClientboundPacket>(
    incoming,
    outgoing,
    packetRegistry,
    PacketDirection.SERVERBOUND,
    PacketDirection.CLIENTBOUND,
), MinecraftServerPacketConnection {
    override fun enableEncryption(sharedSecret: ByteArray) = Unit

    override fun enableKeepAlive(
        extractChallenge: (ServerboundPacket) -> Long?,
        createRequest: (Long) -> ClientboundPacket,
        interval: Duration,
    ) = Unit

    override fun disableKeepAlive() = Unit
}
