package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.configuration.ConfigurationData
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.configuration.requireRegistryPacket
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketSession
import com.hiczp.minecraft.protocol.session.createMinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import com.hiczp.minecraft.protocol.world.*
import com.hiczp.minecraft.world.format.BiomeId
import com.hiczp.minecraft.world.format.BlockId
import com.hiczp.minecraft.world.format.BlockState
import com.hiczp.minecraft.world.format.DimensionTypeLayout
import com.hiczp.minecraft.world.format.NbtPropertyReadMappings
import io.ktor.utils.io.*
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftClientNegotiationTest {
    @Test
    fun negotiationOptionsDefaultToTheCompleteVanillaClientContract() {
        val minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions()

        assertSame(VanillaConfigurationData, minecraftClientNegotiationOptions.configurationData)
        assertEquals(
            VanillaConfigurationData.offeredKnownPacks.toSet(),
            minecraftClientNegotiationOptions.acceptedKnownPacks,
        )
        assertSame(
            VanillaConfigurationData.staticRegistrySchema,
            minecraftClientNegotiationOptions.staticRegistrySchema,
        )
    }

    @Test
    fun completesStatusAndPingAgainstAScriptedPeer() = runTest {
        val (client, serverSession) = connectionPair()
        val server = async {
            assertIs<ClientIntentionPacket>(serverSession.receive())
            assertEquals(ServerboundStatusRequestPacket, serverSession.receive())
            serverSession.send(
                ClientboundStatusResponsePacket(
                    ServerStatus(
                        version = ServerStatus.Version(
                            name = MinecraftProtocol.MINECRAFT_VERSION,
                            protocol = MinecraftProtocol.PROTOCOL_VERSION,
                        ),
                    ),
                ),
            )
            val serverboundPingRequestPacket = assertIs<ServerboundPingRequestPacket>(serverSession.receive())
            serverSession.send(ClientboundPongResponsePacket(serverboundPingRequestPacket.time))
        }

        val minecraftStatusExchange = client.queryStatus(0x0102_0304_0506_0708)

        assertEquals(
            MinecraftProtocol.PROTOCOL_VERSION,
            minecraftStatusExchange.clientboundStatusResponsePacket.status.version?.protocol,
        )
        assertEquals(0x0102_0304_0506_0708, minecraftStatusExchange.clientboundPongResponsePacket.time)
        server.await()
        client.close()
    }

    @Test
    fun completesOfflineLoginConfigurationAndPlayEntry() = runTest {
        val (client, serverSession) = connectionPair()
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("ClientProbe")
        val clientboundLoginFinishedPacket = ClientboundLoginFinishedPacket(
            gameProfile = GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
            sessionId = Uuid.fromLongs(10, 20),
        )
        val clientboundLoginPacket = createClientboundLoginPacket()
        val coreKnownPack = KnownPack("minecraft", "core", MinecraftProtocol.MINECRAFT_VERSION)
        val server = async {
            assertIs<ClientIntentionPacket>(serverSession.receive())
            assertEquals(
                ServerboundHelloPacket(minecraftOfflineIdentity.name, minecraftOfflineIdentity.id),
                serverSession.receive(),
            )
            val cookieKey = Identifier("test:cookie")
            serverSession.send(ClientboundCookieRequestPacket(cookieKey))
            assertEquals(
                ServerboundCookieResponsePacket(cookieKey, null),
                serverSession.receive(),
            )
            serverSession.send(
                ClientboundCustomQueryPacket(
                    transactionId = 7,
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
            serverSession.send(ClientboundLoginCompressionPacket(32))
            serverSession.send(clientboundLoginFinishedPacket)
            assertEquals(ServerboundLoginAcknowledgedPacket, serverSession.receive())
            assertIs<ServerboundClientInformationPacket>(serverSession.receive())
            serverSession.send(ClientboundUpdateEnabledFeaturesPacket(setOf(Identifier("vanilla"))))
            serverSession.send(
                ClientboundSelectKnownPacks(listOf(coreKnownPack)),
            )
            assertEquals(
                ServerboundSelectKnownPacks(listOf(coreKnownPack)),
                serverSession.receive(),
            )
            serverSession.send(ClientboundKeepAlivePacket(42))
            assertEquals(
                ServerboundKeepAlivePacket(42),
                serverSession.receive(),
            )
            serverSession.send(ClientboundPingPacket(19))
            assertEquals(ServerboundPongPacket(19), serverSession.receive())
            serverSession.send(ClientboundCodeOfConductPacket("Be kind."))
            assertEquals(ServerboundAcceptCodeOfConductPacket, serverSession.receive())
            serverSession.send(ClientboundUpdateTagsPacket(emptyList()))
            VanillaConfigurationData.synchronizedRegistryPackets(listOf(coreKnownPack))
                .forEach { clientboundRegistryDataPacket ->
                    serverSession.send(clientboundRegistryDataPacket)
            }
            serverSession.send(ClientboundFinishConfigurationPacket)
            assertEquals(
                ServerboundFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(clientboundLoginPacket)
            serverSession.send(ClientboundKeepAlivePacket(43))
            assertEquals(
                ServerboundKeepAlivePacket(43),
                serverSession.receive(),
            )
        }

        val minecraftClientNegotiationResult = client.negotiate(minecraftOfflineIdentity)

        assertEquals(clientboundLoginFinishedPacket, minecraftClientNegotiationResult.clientboundLoginFinishedPacket)
        assertEquals(clientboundLoginPacket, minecraftClientNegotiationResult.clientboundLoginPacket)
        assertEquals(
            listOf(coreKnownPack),
            minecraftClientNegotiationResult.dataPackConfigurationSnapshot.offeredKnownPacks,
        )
        val expectedMinecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaConfigurationData,
            Identifier("overworld"),
        )
        assertEquals(expectedMinecraftDimensionLayout, minecraftClientNegotiationResult.minecraftDimensionLayout)
        assertEquals(
            expectedMinecraftDimensionLayout.chunkLayout,
            minecraftClientNegotiationResult.chunkLayout,
        )
        assertEquals(
            VanillaConfigurationData.requireRegistryPacket(
                Identifier("worldgen/biome"),
            ).entries.size,
            client.packetCodecContext.biomeRegistrySize,
        )
        server.await()
        assertTrue(client.incoming.tryReceive().isFailure)
        client.close()
    }

    @Test
    fun retainsNegotiatedRegistryViewAfterConnectionContextChangesAndClose() = runTest {
        val (client, serverSession) = connectionPair()
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("RegistryProbe")
        val dimensionId = Identifier("test:world")
        val biomeTagId = Identifier("test:selected_biomes")
        val dimensionTypeRegistryPacket = ClientboundRegistryDataPacket(
            registry = Identifier("dimension_type"),
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
        val biomeRegistryPacket = ClientboundRegistryDataPacket(
            registry = Identifier("worldgen/biome"),
            entries = listOf(
                RegistryEntry(Identifier("test:first"), null),
                RegistryEntry(Identifier("test:second"), null),
            ),
        )
        val compactDimensionTypeRegistryPacket = ClientboundRegistryDataPacket(
            dimensionTypeRegistryPacket.registry,
            dimensionTypeRegistryPacket.entries.map { registryEntry ->
                RegistryEntry(registryEntry.id, null)
            },
        )
        val configurationData = object : ConfigurationData by VanillaConfigurationData {
            override fun synchronizedRegistryPackets(
                acceptedKnownPacks: List<KnownPack>,
            ): List<ClientboundRegistryDataPacket> = listOf(
                dimensionTypeRegistryPacket,
                biomeRegistryPacket,
            )
        }
        val clientboundLoginPacket = createClientboundLoginPacket(
            dimensionTypeId = 0,
            dimension = dimensionId,
        )
        val server = async {
            assertIs<ClientIntentionPacket>(serverSession.receive())
            assertIs<ServerboundHelloPacket>(serverSession.receive())
            serverSession.send(
                ClientboundLoginFinishedPacket(
                    GameProfile(minecraftOfflineIdentity.id, minecraftOfflineIdentity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            assertEquals(ServerboundLoginAcknowledgedPacket, serverSession.receive())
            assertIs<ServerboundClientInformationPacket>(
                serverSession.receive(),
            )
            serverSession.send(compactDimensionTypeRegistryPacket)
            serverSession.send(biomeRegistryPacket)
            serverSession.send(
                ClientboundUpdateTagsPacket(
                    listOf(
                        RegistryTags(PacketCodecContext.BIOME_REGISTRY, listOf(TagDefinition(biomeTagId, listOf(1)))),
                    )
                )
            )
            serverSession.send(ClientboundFinishConfigurationPacket)
            assertEquals(
                ServerboundFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(clientboundLoginPacket)
        }

        val minecraftClientNegotiationResult = client.negotiate(
            minecraftOfflineIdentity,
            minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                configurationData = configurationData,
            ),
        )

        assertEquals(clientboundLoginPacket, minecraftClientNegotiationResult.clientboundLoginPacket)
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
            client.packetCodecContext.biomeRegistrySize,
        )
        assertEquals(
            1,
            client.packetCodecContext.requireRegistryEntry(
                PacketCodecContext.BIOME_REGISTRY,
                Identifier("test:second"),
            ).rawId,
        )
        val chunkContext = minecraftClientNegotiationResult.minecraftDimensionContext.chunkContext(
            BlockState(BlockId("minecraft:air")), BiomeId("test:first"),
        )
        val decoder = ChunkPacketDecoder(
            ChunkPacketDecoderContext(
                chunkContext, client.packetCodecContext, ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
                { ChunkPacketMissingData("minecraft:full", 0, true) },
            )
        )
        assertSame(client.packetCodecContext, decoder.chunkPacketDecoderContext.packetCodecContext)
        assertSame(chunkContext, decoder.chunkPacketDecoderContext.chunkContext)
        server.await()

        // A later connection mapping must not reinterpret tags captured by the earlier negotiation.
        client.installPacketCodecContext(
            PacketCodecContext(
                registries = listOf(
                    RegistryIdMap(
                        PacketCodecContext.BIOME_REGISTRY,
                        listOf(RegistryIdMapping(Identifier("test:replacement"), 1)),
                    )
                ),
                blockStates = emptyList(),
            )
        )
        client.close()

        val retainedDecoder = minecraftClientNegotiationResult.chunkPacketDecoder(
            BlockState(BlockId("minecraft:air")), BiomeId("test:first"),
            ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
            { ChunkPacketMissingData("test:local", 0, false) },
        )
        assertSame(
            minecraftClientNegotiationResult.minecraftDimensionContext.packetCodecContext,
            retainedDecoder.chunkPacketDecoderContext.packetCodecContext,
        )
        assertEquals(chunkContext, retainedDecoder.chunkPacketDecoderContext.chunkContext)
        assertEquals(
            Identifier("test:second"),
            retainedDecoder.chunkPacketDecoderContext.packetCodecContext
                .requireRegistry(PacketCodecContext.BIOME_REGISTRY)[1]?.id,
        )

        val clientRegistryView = minecraftClientNegotiationResult.resolveClientRegistryView()
        assertSame(
            minecraftClientNegotiationResult.dataPackConfigurationSnapshot,
            clientRegistryView.dataPackConfigurationSnapshot,
        )
        assertSame(
            minecraftClientNegotiationResult.minecraftDimensionContext.packetCodecContext,
            clientRegistryView.packetCodecContext,
        )
        assertEquals(
            Identifier("test:second"),
            assertNotNull(clientRegistryView.tag(PacketCodecContext.BIOME_REGISTRY, biomeTagId))
                .registryIdMapEntries.single().id,
        )
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

    private fun createClientboundLoginPacket(
        dimensionTypeId: Int = 0,
        dimension: Identifier = Identifier("overworld"),
    ): ClientboundLoginPacket {
        return ClientboundLoginPacket(
            playerId = 1,
            hardcore = false,
            levels = setOf(dimension),
            maxPlayers = 20,
            chunkRadius = 8,
            simulationDistance = 8,
            reducedDebugInfo = false,
            showDeathScreen = true,
            doLimitedCrafting = false,
            commonPlayerSpawnInfo = CommonPlayerSpawnInfo(
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
