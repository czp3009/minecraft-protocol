package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.*
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.RoutedCustomPayload
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.test.*

class ForgeNegotiationProfileTest {
    @Test
    fun scriptedProfilesNegotiateTasksRegistriesAndDynamicPlayPackets() = runTest {
        val customCodecs = forgeTestPlayCodecs()
        val packetRegistry = MinecraftPacketRegistry.compose(
            ForgeProtocol.packetCodecs + customCodecs,
        )
        val serverToClient = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val clientToServer = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val clientConnection = ForgeTestConnection(
            serverToClient,
            clientToServer,
            packetRegistry,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
        val serverConnection = ForgeTestConnection(
            clientToServer,
            serverToClient,
            packetRegistry,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
        val network = forgeTestNetworkConfiguration()
        val staticRegistries = forgeTestStaticSchema()
        val dataPackRegistry = Identifier("mod:data_pack_registry")
        val registrySync = ForgeRegistrySync(
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
        val sharedServerContext = registrySync.resolve(staticRegistries)
        val mods = mapOf(
            "example" to ForgeModInfo("Example", "1.0"),
        )
        val clientProfile = ForgeClientProfile(
            ForgeClientProfileDefinition(
                staticRegistries,
                network,
                mods,
                setOf(dataPackRegistry),
            ),
        )
        val serverProfile = ForgeServerProfile(
            ForgeServerProfileDefinition(
                network,
                mods,
                registrySync,
                sharedServerContext,
                listOf(
                    ForgeConfigDataMessage(
                        "server.toml",
                        ByteString(byteArrayOf(1, 2, 3)),
                    ),
                ),
            ),
        )
        clientProfile.begin(clientConnection)
        serverProfile.begin(serverConnection)
        val handshake = clientProfile.prepareHandshake(
            HandshakePacket(
                1,
                "localhost",
                25_565,
                HandshakeNextState.LOGIN,
            ),
        )
        serverProfile.acceptHandshake(handshake)

        val negotiation = async {
            serverProfile.negotiateConfigurationStart(serverConnection)
        }
        repeat(6) {
            assertTrue(
                clientProfile.handleConfigurationPacket(
                    clientConnection,
                    clientConnection.incoming.receive(),
                ),
            )
        }
        negotiation.await()

        val clientContext = clientProfile.resolveRegistryContext(
            staticRegistries.resolve()
                .withRegistrySize(ProtocolRegistryContext.BIOME_REGISTRY, 4)
                .withChunkSectionCount(24),
        )
        assertEquals(
            Identifier("mod:new_block"),
            clientContext.blockStates.first().block,
        )
        assertEquals(5, clientContext.registrySize(Identifier("block")))
        assertEquals(4, clientContext.biomeRegistrySize)
        assertEquals(24, clientContext.chunkSectionCount)

        val serverContext = serverProfile.resolveRegistryContext(
            ProtocolRegistryContext.Empty.withChunkSectionCount(24),
        )
        assertSame(sharedServerContext.registries, serverContext.registries)
        assertSame(sharedServerContext.blockStates, serverContext.blockStates)

        clientProfile.preparePlay(clientConnection)
        clientConnection.currentState = ConnectionState.PLAY
        serverConnection.currentState = ConnectionState.PLAY
        serverProfile.preparePlay(serverConnection)

        val customRoutes = customCodecs.map(PacketCodecRegistration<out Packet>::route)
        assertTrue(customRoutes.all(clientConnection.activeExtensionRoutes::contains))
        assertTrue(customRoutes.all(serverConnection.activeExtensionRoutes::contains))

        val clientResult = assertIs<ForgeNegotiationResult>(
            clientProfile.complete(clientConnection),
        )
        val serverResult = assertIs<ForgeNegotiationResult>(
            serverProfile.complete(serverConnection),
        )
        assertTrue(clientResult.forgePeer)
        assertTrue(serverResult.forgePeer)
        assertTrue(clientResult.registrySynchronized)
        assertTrue(serverResult.registrySynchronized)
        assertEquals(mods, clientResult.remoteMods)
        assertEquals(mods, serverResult.remoteMods)
        assertEquals("server.toml", clientResult.configFiles.single().fileName)
    }

    @Test
    fun channelMismatchThrowsWithoutAutomaticallySendingMismatchData() = runTest {
        val required = Identifier("mod:required")
        val network = ForgeNetworkConfiguration(
            listOf(ForgeChannelDefinition(required, 1)),
        )
        val registry = MinecraftPacketRegistry.compose(
            ForgeProtocol.packetCodecs,
        )
        val incoming = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val connection = ForgeTestConnection(
            incoming,
            outgoing,
            registry,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
        val profile = ForgeServerProfile(
            ForgeServerProfileDefinition(network = network),
        )
        profile.begin(connection)
        profile.acceptHandshake(
            HandshakePacket(
                1,
                "localhost\u0000FORGE",
                25_565,
                HandshakeNextState.LOGIN,
            ),
        )

        val failure = supervisorScope {
            val negotiation = async {
                profile.negotiateConfigurationStart(connection)
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
                ?.let { packet ->
                    packet is ForgeClientboundHandshakePacket &&
                            packet.message is ForgeMismatchDataMessage
                } == true,
        )
    }

    @Test
    fun outOfOrderRegistryDataThrowsWithoutReply() = runTest {
        val registry = MinecraftPacketRegistry.compose(
            ForgeProtocol.packetCodecs,
        )
        val incoming = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val connection = ForgeTestConnection(
            incoming,
            outgoing,
            registry,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
        val profile = ForgeClientProfile(
            ForgeClientProfileDefinition(forgeTestStaticSchema()),
        )
        profile.begin(connection)

        assertFailsWith<ForgeNegotiationException> {
            profile.handleConfigurationPacket(
                connection,
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

private class ForgeTestConnection<Incoming : Packet, Outgoing : Packet>(
    override val incoming: Channel<Incoming>,
    override val outgoing: Channel<Outgoing>,
    private val packetRegistry: PacketRegistry,
    private val incomingDirection: PacketDirection,
    private val outgoingDirection: PacketDirection,
) : MinecraftPacketConnection<Incoming, Outgoing> {
    var currentState: ConnectionState = ConnectionState.CONFIGURATION
    private var registryContext = ProtocolRegistryContext.Empty
    private var activeRoutes = emptySet<PacketRouteKey>()
    private val format = MinecraftProtocolFormat.Default

    override val state: ConnectionState
        get() = currentState

    override val registries: ProtocolRegistryContext
        get() = registryContext

    override val declaredExtensionRoutes: Set<PacketRouteKey>
        get() = packetRegistry.declaredExtensionRoutes

    override val activeExtensionRoutes: Set<PacketRouteKey>
        get() = activeRoutes

    override val isOpen: Boolean = true

    override suspend fun awaitClosed() = Unit

    override suspend fun installRegistryContext(context: ProtocolRegistryContext) {
        registryContext = context
    }

    override suspend fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        require(routes.all(declaredExtensionRoutes::contains))
        activeRoutes = routes.toSet()
    }

    override fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload {
        val route = packetRegistry.extensionRoute(
            packet,
            currentState,
            outgoingDirection,
            outerPacketId = 0,
        ) as PacketRoute.CustomPayload
        require(route.key in activeRoutes)
        val buffer = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            currentState,
            outgoingDirection,
            buffer,
            format,
        )
        return RoutedCustomPayload(route, ByteString(buffer.readByteArray()))
    }

    override fun decodeCustomPayload(payload: RoutedCustomPayload): Incoming {
        require(payload.route.direction == incomingDirection)
        require(payload.route.key in activeRoutes)
        val bytes = payload.data.toByteArray()
        val buffer = Buffer().apply { write(bytes) }
        @Suppress("UNCHECKED_CAST")
        return packetRegistry.decodeExtensionPayloadFromSource(
            payload.route,
            buffer,
            bytes.size,
            format,
        ) as Incoming
    }

    override suspend fun awaitState(state: ConnectionState) = Unit

    override suspend fun prepareOutboundEncryption(sharedSecret: ByteArray) = Unit

    override suspend fun enableEncryption(sharedSecret: ByteArray) = Unit

    override fun close() = Unit
}
