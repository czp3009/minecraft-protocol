package com.hiczp.minecraft.protocol.fabric

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.MinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketConnection
import com.hiczp.minecraft.protocol.session.RoutedCustomPayload
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration

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
            .map { packetCodecRegistration -> packetCodecRegistration.packetRouteKey }
            .toSet() + customRoutes
        val testClientConnection = TestClientConnection(
            serverToClient,
            clientToServer,
            declared,
        )
        val testServerConnection = TestServerConnection(
            clientToServer,
            serverToClient,
            declared,
        )
        val staticRegistrySchema = testStaticSchema()
        val fabricRegistrySyncPacket = FabricRegistrySyncPacket(
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
        val sharedPacketCodecContext =
            staticRegistrySchema.resolve(fabricRegistrySyncPacket.remoteRegistrySnapshot)
        val fabricClientProfile = FabricClientProfile(staticRegistrySchema)
        val fabricServerProfile = FabricServerProfile(
            fabricRegistrySyncPacket = fabricRegistrySyncPacket,
            packetCodecContext = sharedPacketCodecContext,
        )
        fabricClientProfile.begin(testClientConnection)
        fabricServerProfile.begin(testServerConnection)

        val client = async {
            while (true) {
                when (val clientboundPacket = testClientConnection.incoming.receive()) {
                    is ClientboundPingPacket ->
                        testClientConnection.outgoing.send(
                            ServerboundPongPacket(clientboundPacket.id),
                        )

                    else -> {
                        assertTrue(
                            fabricClientProfile.handleConfigurationPacket(
                                testClientConnection,
                                clientboundPacket,
                            ),
                        )
                        if (clientboundPacket is FabricRegistrySyncPacket) return@async
                    }
                }
            }
        }
        fabricServerProfile.negotiateConfiguration(testServerConnection)
        client.await()

        val clientPacketCodecContext = fabricClientProfile.resolvePacketCodecContext(
            staticRegistrySchema.resolve().withRegistrySize(
                PacketCodecContext.BIOME_REGISTRY,
                4,
            ),
        )
        assertEquals(Identifier("mod:block"), clientPacketCodecContext.blockStates.first().block)
        assertEquals(4, clientPacketCodecContext.biomeRegistrySize)
        val serverPacketCodecContext = fabricServerProfile.resolvePacketCodecContext(
            sharedPacketCodecContext,
        )
        assertSame(sharedPacketCodecContext.registries, serverPacketCodecContext.registries)
        assertSame(sharedPacketCodecContext.blockStates, serverPacketCodecContext.blockStates)

        testClientConnection.currentState = ConnectionState.PLAY
        testServerConnection.currentState = ConnectionState.PLAY
        fabricClientProfile.preparePlay(testClientConnection)
        fabricServerProfile.preparePlay(testServerConnection)
        val clientResult = fabricClientProfile.complete(testClientConnection)
                as FabricNegotiationResult
        val serverResult = fabricServerProfile.complete(testServerConnection)
                as FabricNegotiationResult
        assertEquals(1, clientResult.commonVersion)
        assertEquals(1, serverResult.commonVersion)
        assertTrue(clientResult.registriesSynchronized)
        assertTrue(serverResult.registriesSynchronized)
        assertTrue(customRoutes.all(testClientConnection.activeExtensionRoutes::contains))
        assertTrue(customRoutes.all(testServerConnection.activeExtensionRoutes::contains))
    }

    @Test
    fun incompatibleRegistryThrowsWithoutSendingAReply() = runTest {
        val incoming = Channel<ClientboundPacket>(Channel.UNLIMITED)
        val outgoing = Channel<ServerboundPacket>(Channel.UNLIMITED)
        val testClientConnection = TestClientConnection(
            incoming,
            outgoing,
            FabricProtocol.packetCodecs
                .map { packetCodecRegistration -> packetCodecRegistration.packetRouteKey }
                .toSet(),
        )
        val fabricClientProfile = FabricClientProfile(testStaticSchema())
        fabricClientProfile.begin(testClientConnection)
        val fabricRegistrySyncPacket = FabricRegistrySyncPacket(
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
            fabricClientProfile.handleConfigurationPacket(testClientConnection, fabricRegistrySyncPacket)
        }
        assertTrue(outgoing.tryReceive().isFailure)
    }
}

private abstract class TestConnection<Incoming : Packet, Outgoing : Packet>(
    override val incoming: Channel<Incoming>,
    override val outgoing: Channel<Outgoing>,
    override val declaredExtensionRoutes: Set<PacketRouteKey>,
) : MinecraftPacketConnection<Incoming, Outgoing> {
    var currentState: ConnectionState = ConnectionState.CONFIGURATION
    private var mutablePacketCodecContext = PacketCodecContext.Empty
    private var activeRoutes = emptySet<PacketRouteKey>()

    override val connectionState: ConnectionState
        get() = currentState

    override val packetCodecContext: PacketCodecContext
        get() = mutablePacketCodecContext

    override val activeExtensionRoutes: Set<PacketRouteKey>
        get() = activeRoutes

    override val isOpen: Boolean = true

    override suspend fun awaitClosed() = Unit

    override fun installPacketCodecContext(packetCodecContext: PacketCodecContext) {
        mutablePacketCodecContext = packetCodecContext
    }

    override fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        require(routes.all(declaredExtensionRoutes::contains))
        activeRoutes = routes
    }

    override fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload {
        val packetDirection =
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
                packetDirection,
                packetId = 0,
                channel = channel,
            ),
            ByteString(byteArrayOf()),
        )
    }

    override fun decodeCustomPayload(routedCustomPayload: RoutedCustomPayload): Incoming =
        error("This scripted peer does not merge packets")

    override suspend fun awaitState(connectionState: ConnectionState) = Unit

    override suspend fun flush() = Unit

    override fun requestFlush() = Unit

    override fun close() = Unit
}

private class TestClientConnection(
    incoming: Channel<ClientboundPacket>,
    outgoing: Channel<ServerboundPacket>,
    declaredExtensionRoutes: Set<PacketRouteKey>,
) : TestConnection<ClientboundPacket, ServerboundPacket>(incoming, outgoing, declaredExtensionRoutes),
    MinecraftClientPacketConnection {
    override fun prepareOutboundEncryption(sharedSecret: ByteArray) = Unit
}

private class TestServerConnection(
    incoming: Channel<ServerboundPacket>,
    outgoing: Channel<ClientboundPacket>,
    declaredExtensionRoutes: Set<PacketRouteKey>,
) : TestConnection<ServerboundPacket, ClientboundPacket>(incoming, outgoing, declaredExtensionRoutes),
    MinecraftServerPacketConnection {
    override fun enableEncryption(sharedSecret: ByteArray) = Unit

    override fun enableKeepAlive(
        extractChallenge: (ServerboundPacket) -> Long?,
        createRequest: (Long) -> ClientboundPacket,
        interval: Duration,
    ) = Unit

    override fun disableKeepAlive() = Unit
}

private fun testStaticSchema(): StaticRegistrySchema = StaticRegistrySchema(
    registries = mapOf(
        Identifier("block") to listOf(
            Identifier("stone"),
            Identifier("mod:block"),
        ),
        PacketCodecContext.BIOME_REGISTRY to listOf(
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
                StaticBlockState(mapOf("powered" to "false"), isDefault = true),
                StaticBlockState(mapOf("powered" to "true"), isDefault = false),
            ),
        ),
    ),
)
