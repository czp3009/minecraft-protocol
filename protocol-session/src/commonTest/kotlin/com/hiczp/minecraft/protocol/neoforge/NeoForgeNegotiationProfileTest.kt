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

class NeoForgeNegotiationProfileTest {
    @Test
    fun scriptedProfilesNegotiateTasksRegistriesAndDynamicPlayPackets() = runTest {
        val customCodecs = testPlayCodecs()
        val packetRegistry = MinecraftPacketRegistry.compose(
            NeoForgeProtocol.packetCodecs + customCodecs,
        )
        val serverToClient = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val clientToServer = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val clientConnection = NeoForgeTestClientConnection(
            serverToClient,
            clientToServer,
            packetRegistry,
        )
        val serverConnection = NeoForgeTestServerConnection(
            clientToServer,
            serverToClient,
            packetRegistry,
        )
        val staticRegistries = testStaticSchema()
        val frozen = NeoForgeFrozenRegistrySync(
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
        val sharedServerContext = staticRegistries.resolve(
            frozen.remoteSnapshot,
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
        val network = testNetworkConfiguration()
        val clientProfile = NeoForgeClientProfile(
            NeoForgeClientProfileDefinition(
                staticRegistries = staticRegistries,
                network = network,
                knownDataMaps = knownDataMaps,
                extensibleEnums = enums,
                featureFlags = flags,
            ),
        )
        val serverProfile = NeoForgeServerProfile(
            NeoForgeServerProfileDefinition(
                network = network,
                frozenRegistries = frozen,
                resolvedRegistryContext = sharedServerContext,
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
        clientProfile.begin(clientConnection)
        serverProfile.begin(serverConnection)

        val initial = async {
            serverProfile.negotiateConfigurationStart(serverConnection)
        }
        repeat(6) {
            val packet = clientConnection.incoming.receive()
            if (packet is ConfigurationPingPacket) {
                clientConnection.outgoing.send(ConfigurationPongPacket(packet.id))
            } else {
                assertTrue(
                    clientProfile.handleConfigurationPacket(
                        clientConnection,
                        packet,
                    ),
                )
            }
        }
        initial.await()

        val early = async {
            serverProfile.negotiateEarlyConfiguration(serverConnection)
        }
        repeat(3) {
            assertTrue(
                clientProfile.handleConfigurationPacket(
                    clientConnection,
                    clientConnection.incoming.receive(),
                ),
            )
        }
        early.await()

        val late = async {
            serverProfile.negotiateConfiguration(serverConnection)
        }
        repeat(6) {
            assertTrue(
                clientProfile.handleConfigurationPacket(
                    clientConnection,
                    clientConnection.incoming.receive(),
                ),
            )
        }
        late.await()

        val clientContext = clientProfile.resolveRegistryContext(
            staticRegistries.resolve().withRegistrySize(
                ProtocolRegistryContext.BIOME_REGISTRY,
                4,
            ).withChunkSectionCount(24),
        )
        assertEquals(
            Identifier("mod:new_block"),
            clientContext.blockStates.first().block,
        )
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

        val clientResult = assertIs<NeoForgeNegotiationResult>(
            clientProfile.complete(clientConnection),
        )
        val serverResult = assertIs<NeoForgeNegotiationResult>(
            serverProfile.complete(serverConnection),
        )
        assertTrue(clientResult.neoForgePeer)
        assertTrue(serverResult.neoForgePeer)
        assertTrue(clientResult.registrySynchronized)
        assertTrue(serverResult.registrySynchronized)
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
        val registry = MinecraftPacketRegistry.compose(
            NeoForgeProtocol.packetCodecs + requiredPacketCodecs,
        )
        val incoming = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val connection = NeoForgeTestServerConnection(
            incoming,
            outgoing,
            registry,
        )
        val profile = NeoForgeServerProfile(
            NeoForgeServerProfileDefinition(
                network = NeoForgeNetworkConfiguration(
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
        profile.begin(connection)
        val failure = supervisorScope {
            val negotiation = async {
                profile.negotiateConfigurationStart(connection)
            }
            repeat(4) { outgoing.receive() }
            incoming.send(NeoForgeNetworkConfiguration().queryPacket)
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
        val registry = MinecraftPacketRegistry.compose(
            NeoForgeProtocol.packetCodecs,
        )
        val connection = NeoForgeTestClientConnection(
            incoming,
            outgoing,
            registry,
        )
        val profile = NeoForgeClientProfile(
            NeoForgeClientProfileDefinition(testStaticSchema()),
        )
        profile.begin(connection)

        assertFailsWith<NeoForgeNegotiationException> {
            profile.handleConfigurationPacket(
                connection,
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

private abstract class NeoForgeTestConnection<Incoming : Packet, Outgoing : Packet>(
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

    override fun installRegistryContext(context: ProtocolRegistryContext) {
        registryContext = context
    }

    override fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
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
        return RoutedCustomPayload(
            route,
            ByteString(buffer.readByteArray()),
        )
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
}
