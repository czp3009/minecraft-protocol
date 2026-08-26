package com.hiczp.minecraft.protocol.forge

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

class ForgeNegotiationProfileTest {
    @Test
    fun scriptedProfilesNegotiateTasksRegistriesAndDynamicPlayPackets() = runTest {
        val customCodecs = forgeTestPlayCodecs()
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            ForgeProtocol.packetCodecs + customCodecs,
        )
        val serverToClient = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val clientToServer = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val forgeTestClientConnection = ForgeTestClientConnection(
            serverToClient,
            clientToServer,
            packetRegistry,
        )
        val forgeTestServerConnection = ForgeTestServerConnection(
            clientToServer,
            serverToClient,
            packetRegistry,
        )
        val forgeNetworkConfiguration = forgeTestNetworkConfiguration()
        val staticRegistrySchema = forgeTestStaticSchema()
        val dataPackRegistry = Identifier("mod:data_pack_registry")
        val forgeRegistrySync = ForgeRegistrySync(
            mapOf(
                Identifier("block") to ForgeRegistrySnapshot(
                    linkedMapOf(
                        Identifier("mod:new_block") to 0,
                        Identifier("stone") to 1,
                    ),
                    aliases = mapOf(
                        Identifier("mod:block") to Identifier("mod:new_block"),
                    ),
                    overrides = mapOf(
                        Identifier("mod:new_block") to "example",
                    ),
                    blocked = setOf(4),
                ),
            ),
            setOf(dataPackRegistry),
        )
        val sharedProtocolRegistryContext = forgeRegistrySync.resolve(staticRegistrySchema)
        val mods = mapOf(
            "example" to ForgeModInfo("Example", "1.0"),
        )
        val forgeClientProfile = ForgeClientProfile(
            ForgeClientProfileDefinition(
                staticRegistrySchema,
                forgeNetworkConfiguration,
                mods,
                setOf(dataPackRegistry),
            ),
        )
        val forgeServerProfile = ForgeServerProfile(
            ForgeServerProfileDefinition(
                forgeNetworkConfiguration,
                mods,
                forgeRegistrySync,
                sharedProtocolRegistryContext,
                listOf(
                    ForgeConfigDataMessage(
                        "server.toml",
                        ByteString(byteArrayOf(1, 2, 3)),
                    ),
                ),
            ),
        )
        forgeClientProfile.begin(forgeTestClientConnection)
        forgeServerProfile.begin(forgeTestServerConnection)
        val handshakePacket = forgeClientProfile.prepareHandshake(
            HandshakePacket(
                1,
                "localhost",
                25_565,
                HandshakeNextState.LOGIN,
            ),
        )
        forgeServerProfile.acceptHandshake(handshakePacket)

        val negotiation = async {
            forgeServerProfile.negotiateConfigurationStart(forgeTestServerConnection)
        }
        repeat(6) {
            assertTrue(
                forgeClientProfile.handleConfigurationPacket(
                    forgeTestClientConnection,
                    forgeTestClientConnection.incoming.receive(),
                ),
            )
        }
        negotiation.await()

        val clientProtocolRegistryContext = forgeClientProfile.resolveProtocolRegistryContext(
            staticRegistrySchema.resolve()
                .withRegistrySize(ProtocolRegistryContext.BIOME_REGISTRY, 4)
                .withChunkSectionCount(24),
        )
        assertEquals(
            Identifier("mod:new_block"),
            clientProtocolRegistryContext.blockStates.first().block,
        )
        assertEquals(5, clientProtocolRegistryContext.registrySize(Identifier("block")))
        assertEquals(4, clientProtocolRegistryContext.biomeRegistrySize)
        assertEquals(24, clientProtocolRegistryContext.chunkSectionCount)

        val serverProtocolRegistryContext = forgeServerProfile.resolveProtocolRegistryContext(
            ProtocolRegistryContext.Empty.withChunkSectionCount(24),
        )
        assertSame(sharedProtocolRegistryContext.registries, serverProtocolRegistryContext.registries)
        assertSame(sharedProtocolRegistryContext.blockStates, serverProtocolRegistryContext.blockStates)

        forgeClientProfile.preparePlay(forgeTestClientConnection)
        forgeTestClientConnection.currentState = ConnectionState.PLAY
        forgeTestServerConnection.currentState = ConnectionState.PLAY
        forgeServerProfile.preparePlay(forgeTestServerConnection)

        val customRoutes = customCodecs.map(PacketCodecRegistration<out Packet>::packetRouteKey)
        assertTrue(customRoutes.all(forgeTestClientConnection.activeExtensionRoutes::contains))
        assertTrue(customRoutes.all(forgeTestServerConnection.activeExtensionRoutes::contains))

        val clientResult = assertIs<ForgeNegotiationResult>(
            forgeClientProfile.complete(forgeTestClientConnection),
        )
        val serverResult = assertIs<ForgeNegotiationResult>(
            forgeServerProfile.complete(forgeTestServerConnection),
        )
        assertTrue(clientResult.forgePeer)
        assertTrue(serverResult.forgePeer)
        assertTrue(clientResult.registriesSynchronized)
        assertTrue(serverResult.registriesSynchronized)
        assertEquals(mods, clientResult.remoteMods)
        assertEquals(mods, serverResult.remoteMods)
        assertEquals("server.toml", clientResult.configFiles.single().fileName)
    }

    @Test
    fun channelMismatchThrowsWithoutAutomaticallySendingMismatchData() = runTest {
        val required = Identifier("mod:required")
        val forgeNetworkConfiguration = ForgeNetworkConfiguration(
            listOf(ForgeChannelDefinition(required, 1)),
        )
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            ForgeProtocol.packetCodecs,
        )
        val incoming = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val forgeTestServerConnection = ForgeTestServerConnection(
            incoming,
            outgoing,
            packetRegistry,
        )
        val forgeServerProfile = ForgeServerProfile(
            ForgeServerProfileDefinition(forgeNetworkConfiguration = forgeNetworkConfiguration),
        )
        forgeServerProfile.begin(forgeTestServerConnection)
        forgeServerProfile.acceptHandshake(
            HandshakePacket(
                1,
                "localhost\u0000FORGE",
                25_565,
                HandshakeNextState.LOGIN,
            ),
        )

        val failure = supervisorScope {
            val negotiation = async {
                forgeServerProfile.negotiateConfigurationStart(forgeTestServerConnection)
            }
            assertIs<ForgeRegisterChannelsPacket>(outgoing.receive())
            assertIs<ForgeClientboundHandshakePacket>(outgoing.receive())
            incoming.send(
                ForgeServerboundHandshakePacket(
                    ForgeModVersionsMessage(emptyMap()),
                ),
            )
            assertIs<ForgeClientboundHandshakePacket>(outgoing.receive())
            incoming.send(
                ForgeServerboundHandshakePacket(
                    ForgeChannelVersionsMessage(mapOf(required to 2)),
                ),
            )
            assertFailsWith<ForgeChannelNegotiationException> {
                negotiation.await()
            }
        }
        assertTrue(required in failure.failurePacket.mismatched)
        assertFalse(
            outgoing.tryReceive().getOrNull()
                ?.let { clientboundPacket ->
                    clientboundPacket is ForgeClientboundHandshakePacket &&
                            clientboundPacket.forgeClientboundHandshakeMessage is ForgeMismatchDataMessage
                } == true,
        )
    }

    @Test
    fun outOfOrderRegistryDataThrowsWithoutReply() = runTest {
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            ForgeProtocol.packetCodecs,
        )
        val incoming = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val forgeTestClientConnection = ForgeTestClientConnection(
            incoming,
            outgoing,
            packetRegistry,
        )
        val forgeClientProfile = ForgeClientProfile(
            ForgeClientProfileDefinition(forgeTestStaticSchema()),
        )
        forgeClientProfile.begin(forgeTestClientConnection)

        assertFailsWith<ForgeNegotiationException> {
            forgeClientProfile.handleConfigurationPacket(
                forgeTestClientConnection,
                ForgeClientboundHandshakePacket(
                    ForgeRegistryDataMessage(
                        1,
                        Identifier("block"),
                        ForgeRegistrySnapshot(
                            mapOf(Identifier("stone") to 0),
                        ),
                    ),
                ),
            )
        }
        assertTrue(outgoing.tryReceive().isFailure)
    }
}

@Serializable
private data class ForgeTestClientboundPlayPacket(
    val value: Int,
) : ClientboundPacket.Extension

@Serializable
private data class ForgeTestServerboundPlayPacket(
    val value: Int,
) : ServerboundPacket.Extension

private fun forgeTestPlayCodecs(): List<PacketCodecRegistration<out Packet>> = listOf(
    PacketCodecRegistration.clientboundCustomPayload(
        ConnectionState.PLAY,
        Identifier("mod:to_client"),
        ForgeTestClientboundPlayPacket::class,
        KotlinxPacketBodyCodec(ForgeTestClientboundPlayPacket.serializer()),
    ),
    PacketCodecRegistration.serverboundCustomPayload(
        ConnectionState.PLAY,
        Identifier("mod:to_server"),
        ForgeTestServerboundPlayPacket::class,
        KotlinxPacketBodyCodec(ForgeTestServerboundPlayPacket.serializer()),
    ),
)

private fun forgeTestNetworkConfiguration(): ForgeNetworkConfiguration =
    ForgeNetworkConfiguration(
        listOf(
            ForgeChannelDefinition(Identifier("mod:to_client"), 1),
            ForgeChannelDefinition(Identifier("mod:to_server"), 1),
        ),
    )

private fun forgeTestStaticSchema(): StaticRegistrySchema = StaticRegistrySchema(
    registries = mapOf(
        Identifier("block") to listOf(
            Identifier("stone"),
            Identifier("mod:block"),
        ),
        ProtocolRegistryContext.BIOME_REGISTRY to listOf(
            Identifier("plains"),
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

private abstract class ForgeTestConnection<Incoming : Packet, Outgoing : Packet>(
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
        return RoutedCustomPayload(customPayload, ByteString(buffer.readByteArray()))
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

private class ForgeTestClientConnection(
    incoming: Channel<ClientboundPacket>,
    outgoing: Channel<ServerboundPacket>,
    packetRegistry: PacketRegistry,
) : ForgeTestConnection<ClientboundPacket, ServerboundPacket>(
    incoming,
    outgoing,
    packetRegistry,
    PacketDirection.CLIENTBOUND,
    PacketDirection.SERVERBOUND,
), MinecraftClientPacketConnection {
    override fun prepareOutboundEncryption(sharedSecret: ByteArray) = Unit
}

private class ForgeTestServerConnection(
    incoming: Channel<ServerboundPacket>,
    outgoing: Channel<ClientboundPacket>,
    packetRegistry: PacketRegistry,
) : ForgeTestConnection<ServerboundPacket, ClientboundPacket>(
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
