package com.hiczp.minecraft.protocol.fabric

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.RoutedCustomPayload
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FabricNegotiationProfileTest {
    @Test
    fun scriptedProfilesNegotiateChannelsAndRegistryMapping() = runTest {
        val serverToClient = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val clientToServer = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val customRoutes = setOf(
            PacketRouteKey.CustomPayload(
                ConnectionState.PLAY,
                PacketDirection.CLIENTBOUND,
                Identifier("mod:to_client"),
            ),
            PacketRouteKey.CustomPayload(
                ConnectionState.PLAY,
                PacketDirection.SERVERBOUND,
                Identifier("mod:to_server"),
            ),
        )
        val declared = FabricProtocol.packetCodecs
            .map { registration -> registration.route }
            .toSet() + customRoutes
        val clientConnection = TestConnection(
            serverToClient,
            clientToServer,
            declared,
        )
        val serverConnection = TestConnection(
            clientToServer,
            serverToClient,
            declared,
        )
        val static = testStaticSchema()
        val sync = FabricRegistrySyncPacket(
            RemoteRegistrySnapshot(
                listOf(
                    RemoteRegistry(
                        Identifier("block"),
                        listOf(
                            RemoteRegistryEntry(Identifier("mod:block"), 0),
                            RemoteRegistryEntry(Identifier("stone"), 1),
                        ),
                    ),
                ),
            ),
        )
        val sharedServerContext = static.resolve(sync.snapshot)
        val clientProfile = FabricClientProfile(static)
        val serverProfile = FabricServerProfile(
            registrySync = sync,
            resolvedRegistryContext = sharedServerContext,
        )
        clientProfile.begin(clientConnection)
        serverProfile.begin(serverConnection)

        val client = async {
            while (true) {
                when (val packet = clientConnection.incoming.receive()) {
                    is ConfigurationPingPacket ->
                        clientConnection.outgoing.send(
                            ConfigurationPongPacket(packet.id),
                        )

                    else -> {
                        assertTrue(
                            clientProfile.handleConfigurationPacket(
                                clientConnection,
                                packet,
                            ),
                        )
                        if (packet is FabricRegistrySyncPacket) return@async
                    }
                }
            }
        }
        serverProfile.negotiateConfiguration(serverConnection)
        client.await()

        val clientContext = clientProfile.resolveRegistryContext(
            static.resolve().withRegistrySize(
                ProtocolRegistryContext.BIOME_REGISTRY,
                4,
            ),
        )
        assertEquals(Identifier("mod:block"), clientContext.blockStates.first().block)
        assertEquals(4, clientContext.biomeRegistrySize)
        val serverContext = serverProfile.resolveRegistryContext(
            sharedServerContext.withChunkSectionCount(24),
        )
        assertSame(sharedServerContext.registries, serverContext.registries)
        assertSame(sharedServerContext.blockStates, serverContext.blockStates)

        clientConnection.currentState = ConnectionState.PLAY
        serverConnection.currentState = ConnectionState.PLAY
        clientProfile.preparePlay(clientConnection)
        serverProfile.preparePlay(serverConnection)
        val clientResult = clientProfile.complete(clientConnection)
                as FabricNegotiationResult
        val serverResult = serverProfile.complete(serverConnection)
                as FabricNegotiationResult
        assertEquals(1, clientResult.commonVersion)
        assertEquals(1, serverResult.commonVersion)
        assertTrue(clientResult.registrySynchronized)
        assertTrue(serverResult.registrySynchronized)
        assertTrue(customRoutes.all(clientConnection.activeExtensionRoutes::contains))
        assertTrue(customRoutes.all(serverConnection.activeExtensionRoutes::contains))
    }

    @Test
    fun incompatibleRegistryThrowsWithoutSendingAReply() = runTest {
        val incoming = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val connection = TestConnection(
            incoming,
            outgoing,
            FabricProtocol.packetCodecs
                .map { registration -> registration.route }
                .toSet(),
        )
        val profile = FabricClientProfile(testStaticSchema())
        profile.begin(connection)
        val packet = FabricRegistrySyncPacket(
            RemoteRegistrySnapshot(
                listOf(
                    RemoteRegistry(
                        Identifier("block"),
                        listOf(
                            RemoteRegistryEntry(Identifier("missing:block"), 0),
                        ),
                    ),
                ),
            ),
        )
        assertFailsWith<FabricNegotiationException> {
            profile.handleConfigurationPacket(connection, packet)
        }
        assertTrue(outgoing.tryReceive().isFailure)
    }
}

private class TestConnection<Incoming : Packet, Outgoing : Packet>(
    override val incoming: Channel<Incoming>,
    override val outgoing: Channel<Outgoing>,
    override val declaredExtensionRoutes: Set<PacketRouteKey>,
) : MinecraftPacketConnection<Incoming, Outgoing> {
    var currentState: ConnectionState = ConnectionState.CONFIGURATION
    private var registryContext = ProtocolRegistryContext.Empty
    private var activeRoutes = emptySet<PacketRouteKey>()

    override val state: ConnectionState
        get() = currentState

    override val registries: ProtocolRegistryContext
        get() = registryContext

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
        val direction =
            if (packet is ClientboundPacket) {
                PacketDirection.CLIENTBOUND
            } else {
                PacketDirection.SERVERBOUND
            }
        val channel = when (packet) {
            is FabricRegistrySyncPacket -> FabricChannels.RegistrySync
            else -> error("This scripted peer cannot encode ${packet::class.simpleName}")
        }
        return RoutedCustomPayload(
            PacketRoute.CustomPayload(
                currentState,
                direction,
                packetId = 0,
                channel = channel,
            ),
            ByteString(byteArrayOf()),
        )
    }

    override fun decodeCustomPayload(payload: RoutedCustomPayload): Incoming =
        error("This scripted peer does not merge packets")

    override suspend fun awaitState(state: ConnectionState) = Unit

    override suspend fun flush() = Unit

    override fun requestFlush() = Unit

    override fun prepareOutboundEncryption(sharedSecret: ByteArray) = Unit

    override fun enableEncryption(sharedSecret: ByteArray) = Unit

    override fun close() = Unit
}

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
                StaticBlockState(mapOf("powered" to "false"), isDefault = true),
                StaticBlockState(mapOf("powered" to "true"), isDefault = false),
            ),
        ),
    ),
)
