package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.ProtocolData
import com.hiczp.minecraft.protocol.datapack.requireRegistryPacket
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketSession
import com.hiczp.minecraft.protocol.session.createMinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import com.hiczp.minecraft.world.format.ChunkMetadata
import com.hiczp.minecraft.world.format.DimensionTypeLayout
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftClientProtocolTest {
    @Test
    fun negotiationOptionsDefaultToTheCompleteVanillaClientContract() {
        val minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions()

        assertSame(VanillaProtocolData, minecraftClientNegotiationOptions.protocolData)
        assertEquals(
            VanillaProtocolData.offeredKnownPacks.toSet(),
            minecraftClientNegotiationOptions.acceptedKnownPacks,
        )
        assertSame(
            VanillaProtocolData.staticRegistrySchema,
            minecraftClientNegotiationOptions.staticRegistrySchema,
        )
    }

    @Test
    fun completesStatusAndPingAgainstAScriptedPeer() = runTest {
        val (client, serverSession) = connectionPair()
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(StatusRequestPacket, serverSession.receive())
            serverSession.send(
                StatusResponsePacket(
                    ServerStatus(
                        version = ServerStatus.Version(
                            name = MinecraftProtocol.MINECRAFT_VERSION,
                            protocol = MinecraftProtocol.PROTOCOL_VERSION,
                        ),
                    ),
                ),
            )
            val statusPingRequestPacket = assertIs<StatusPingRequestPacket>(serverSession.receive())
            serverSession.send(StatusPongResponsePacket(statusPingRequestPacket.timestamp))
        }

        val minecraftStatusExchange = client.queryStatus(0x0102_0304_0506_0708)

        assertEquals(
            MinecraftProtocol.PROTOCOL_VERSION,
            minecraftStatusExchange.statusResponsePacket.status.version?.protocol,
        )
        assertEquals(0x0102_0304_0506_0708, minecraftStatusExchange.statusPongResponsePacket.timestamp)
        server.await()
        client.close()
    }

    @Test
    fun completesOfflineLoginConfigurationAndPlayEntry() = runTest {
        val (client, serverSession) = connectionPair()
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("ClientProbe")
        val loginSuccessPacket = LoginSuccessPacket(
            profile = GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
            sessionId = Uuid.fromLongs(10, 20),
        )
        val playLoginPacket = createPlayLoginPacket()
        val coreKnownPack = KnownPack("minecraft", "core", MinecraftProtocol.MINECRAFT_VERSION)
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(
                LoginStartPacket(minecraftOfflineIdentity.name, minecraftOfflineIdentity.id),
                serverSession.receive(),
            )
            val cookieKey = Identifier("test:cookie")
            serverSession.send(LoginCookieRequestPacket(cookieKey))
            assertEquals(
                LoginCookieResponsePacket(cookieKey, null),
                serverSession.receive(),
            )
            serverSession.send(
                LoginPluginRequestPacket(
                    messageId = 7,
                    channel = Identifier("test:query"),
                    data = ByteString(byteArrayOf(1, 2, 3)),
                ),
            )
            val loginQueryResponse = assertIs<UnknownPacket.Serverbound>(
                serverSession.receive(),
            )
            assertEquals(
                PacketRoute.LoginQuery(
                    packetDirection = PacketDirection.SERVERBOUND,
                    transactionId = 7,
                    channel = Identifier("test:query"),
                    hasPayload = false,
                ),
                loginQueryResponse.packetRoute,
            )
            assertEquals(ByteString(byteArrayOf()), loginQueryResponse.data)
            serverSession.send(SetCompressionPacket(32))
            serverSession.send(loginSuccessPacket)
            assertEquals(LoginAcknowledgedPacket, serverSession.receive())
            assertIs<ConfigurationClientInformationPacket>(serverSession.receive())
            serverSession.send(FeatureFlagsPacket(setOf(Identifier("vanilla"))))
            serverSession.send(
                ConfigurationClientboundKnownPacksPacket(listOf(coreKnownPack)),
            )
            assertEquals(
                ConfigurationServerboundKnownPacksPacket(listOf(coreKnownPack)),
                serverSession.receive(),
            )
            serverSession.send(ConfigurationClientboundKeepAlivePacket(42))
            assertEquals(
                ConfigurationServerboundKeepAlivePacket(42),
                serverSession.receive(),
            )
            serverSession.send(ConfigurationPingPacket(19))
            assertEquals(ConfigurationPongPacket(19), serverSession.receive())
            serverSession.send(CodeOfConductPacket("Be kind."))
            assertEquals(AcceptCodeOfConductPacket, serverSession.receive())
            serverSession.send(ConfigurationUpdateTagsPacket(emptyList()))
            VanillaProtocolData.synchronizedRegistryPackets(listOf(coreKnownPack)).forEach { registryDataPacket ->
                serverSession.send(registryDataPacket)
            }
            serverSession.send(FinishConfigurationPacket)
            assertEquals(
                AcknowledgeFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(playLoginPacket)
            serverSession.send(PlayClientboundKeepAlivePacket(43))
            assertEquals(
                PlayServerboundKeepAlivePacket(43),
                serverSession.receive(),
            )
        }

        val minecraftClientNegotiationResult = client.negotiate(minecraftOfflineIdentity)

        assertEquals(loginSuccessPacket, minecraftClientNegotiationResult.loginSuccessPacket)
        assertEquals(playLoginPacket, minecraftClientNegotiationResult.playLoginPacket)
        assertEquals(
            listOf(coreKnownPack),
            minecraftClientNegotiationResult.dataPackConfigurationSnapshot.offeredKnownPacks,
        )
        val expectedMinecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        assertEquals(expectedMinecraftDimensionLayout, minecraftClientNegotiationResult.minecraftDimensionLayout)
        assertEquals(
            expectedMinecraftDimensionLayout.chunkLayout,
            minecraftClientNegotiationResult.chunkLayout,
        )
        assertEquals(
            expectedMinecraftDimensionLayout.sectionCount,
            client.protocolRegistryContext.chunkSectionCount,
        )
        assertEquals(
            VanillaProtocolData.requireRegistryPacket(
                Identifier("worldgen/biome"),
            ).entries.size,
            client.protocolRegistryContext.biomeRegistrySize,
        )
        server.await()
        assertTrue(client.incoming.tryReceive().isFailure)
        client.close()
    }

    @Test
    fun derivesPlayFormatFromTheSynchronizedCustomRegistries() = runTest {
        val (client, serverSession) = connectionPair()
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("RegistryProbe")
        val dimensionId = Identifier("test:world")
        val dimensionTypeRegistryPacket = RegistryDataPacket(
            registryId = Identifier("dimension_type"),
            entries = listOf(
                RegistryEntry(
                    id = Identifier("test:short_dimension"),
                    data = NbtCompound(
                        mapOf(
                            "min_y" to NbtInt(0),
                            "height" to NbtInt(32),
                            "logical_height" to NbtInt(32),
                            "has_skylight" to NbtByte(0),
                            "has_ceiling" to NbtByte(1),
                        ),
                    ),
                ),
            ),
        )
        val biomeRegistryPacket = RegistryDataPacket(
            registryId = Identifier("worldgen/biome"),
            entries = listOf(
                RegistryEntry(Identifier("test:first"), null),
                RegistryEntry(Identifier("test:second"), null),
            ),
        )
        val compactDimensionTypeRegistryPacket = RegistryDataPacket(
            dimensionTypeRegistryPacket.registryId,
            dimensionTypeRegistryPacket.entries.map { registryEntry ->
                RegistryEntry(registryEntry.id, null)
            },
        )
        val protocolData = object : ProtocolData by VanillaProtocolData {
            override fun synchronizedRegistryPackets(
                acceptedKnownPacks: List<KnownPack>,
            ): List<RegistryDataPacket> = listOf(
                dimensionTypeRegistryPacket,
                biomeRegistryPacket,
            )
        }
        val playLoginPacket = createPlayLoginPacket(
            dimensionTypeId = 0,
            dimension = dimensionId,
        )
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertIs<LoginStartPacket>(serverSession.receive())
            serverSession.send(
                LoginSuccessPacket(
                    GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            assertEquals(LoginAcknowledgedPacket, serverSession.receive())
            assertIs<ConfigurationClientInformationPacket>(
                serverSession.receive(),
            )
            serverSession.send(compactDimensionTypeRegistryPacket)
            serverSession.send(biomeRegistryPacket)
            serverSession.send(FinishConfigurationPacket)
            assertEquals(
                AcknowledgeFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(playLoginPacket)
        }

        val minecraftClientNegotiationResult = client.negotiate(
            minecraftOfflineIdentity,
            minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                protocolData = protocolData,
            ),
        )

        assertEquals(playLoginPacket, minecraftClientNegotiationResult.playLoginPacket)
        assertEquals(
            MinecraftDimensionLayout(
                dimensionTypeId = Identifier("test:short_dimension"),
                dimensionTypeRawId = 0,
                dimensionTypeLayout = DimensionTypeLayout(
                    minY = 0,
                    height = 32,
                    logicalHeight = 32,
                    hasSkyLight = false,
                    hasCeiling = true,
                ),
            ),
            minecraftClientNegotiationResult.minecraftDimensionLayout,
        )
        assertEquals(0, minecraftClientNegotiationResult.chunkLayout.minSectionY)
        assertEquals(2, minecraftClientNegotiationResult.chunkLayout.sectionCount)
        assertEquals(
            2,
            client.protocolRegistryContext.chunkSectionCount,
        )
        assertEquals(
            2,
            client.protocolRegistryContext.biomeRegistrySize,
        )
        assertEquals(
            1,
            client.protocolRegistryContext.requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                Identifier("test:second"),
            ).rawId,
        )
        val minecraftChunkContext = minecraftClientNegotiationResult.minecraftDimensionContext
            .createMinecraftChunkContext(defaultBiome = Identifier("test:first"))
        val minecraftChunkPacketDecoder = minecraftChunkContext.packetDecoder(
            ChunkMetadata(dataVersion = 1, status = "minecraft:full"),
        )
        assertSame(
            minecraftChunkContext.protocolRegistryContext,
            minecraftChunkPacketDecoder.protocolRegistryContext,
        )
        assertSame(
            minecraftChunkContext.chunkCodecContext,
            minecraftChunkPacketDecoder.chunkCodecContext,
        )
        server.await()
        client.close()
    }

    private fun connectionPair(): Pair<MinecraftClientConnection, MinecraftServerPacketSession> {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val client = MinecraftClientConnection(
            minecraftClientPacketConnection = createMinecraftClientPacketConnection(
                minecraftFrameStream = clientFrames,
                closeTransport = { clientFrames.cancel() },
                minecraftConnectionDefinition = MinecraftConnectionDefinition(),
            ),
            serverAddress = "localhost",
            serverPort = 25_565,
        )
        val server = MinecraftServerPacketSession(
            MinecraftFrameStream(clientToServer, serverToClient),
        )
        return client to server
    }

    private fun createPlayLoginPacket(
        dimensionTypeId: Int = 0,
        dimension: Identifier = Identifier("overworld"),
    ): PlayLoginPacket {
        return PlayLoginPacket(
            playerId = 1,
            hardcore = false,
            levels = setOf(dimension),
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
            onlineMode = false,
            enforcesSecureChat = false,
        )
    }
}
