package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.ProtocolDataSet
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.data.requireRegistry
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketSession
import com.hiczp.minecraft.protocol.session.createMinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftClientProtocolTest {
    @Test
    fun completesStatusAndPingAgainstAScriptedPeer() = runTest {
        val (client, serverSession) = connectionPair()
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(StatusRequestPacket, serverSession.receive())
            serverSession.send(
                StatusResponsePacket(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put(
                                "version",
                                buildJsonObject {
                                    put("name", MinecraftProtocol.MINECRAFT_VERSION)
                                    put("protocol", MinecraftProtocol.PROTOCOL_VERSION)
                                },
                            )
                        },
                    ),
                ),
            )
            val ping = assertIs<StatusPingRequestPacket>(serverSession.receive())
            serverSession.send(StatusPongResponsePacket(ping.timestamp))
        }

        val result = client.queryStatus(0x0102_0304_0506_0708)

        assertEquals(0x0102_0304_0506_0708, result.pong.timestamp)
        server.await()
        client.close()
    }

    @Test
    fun completesOfflineLoginConfigurationAndPlayEntry() = runTest {
        val (client, serverSession) = connectionPair()
        val identity = MinecraftOfflineIdentity("ClientProbe")
        val loginSuccess = LoginSuccessPacket(
            profile = GameProfile(identity.id, identity.name, emptyList()),
            sessionId = Uuid.fromLongs(10, 20),
        )
        val playLogin = playLogin()
        val corePack = KnownPack("minecraft", "core", MinecraftProtocol.MINECRAFT_VERSION)
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(
                LoginStartPacket(identity.name, identity.id),
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
            val queryResponse = assertIs<UnknownPacket.Serverbound>(
                serverSession.receive(),
            )
            assertEquals(
                PacketRoute.LoginQuery(
                    direction = PacketDirection.SERVERBOUND,
                    transactionId = 7,
                    channel = Identifier("test:query"),
                    hasPayload = false,
                ),
                queryResponse.route,
            )
            assertEquals(ByteString(byteArrayOf()), queryResponse.data)
            serverSession.send(SetCompressionPacket(32))
            serverSession.send(loginSuccess)
            assertEquals(LoginAcknowledgedPacket, serverSession.receive())
            assertIs<ConfigurationClientInformationPacket>(serverSession.receive())
            serverSession.send(FeatureFlagsPacket(setOf(Identifier("vanilla"))))
            serverSession.send(
                ConfigurationClientboundKnownPacksPacket(listOf(corePack)),
            )
            assertEquals(
                ConfigurationServerboundKnownPacksPacket(listOf(corePack)),
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
            VanillaProtocolData.registryPackets(listOf(corePack)).forEach {
                serverSession.send(it)
            }
            serverSession.send(FinishConfigurationPacket)
            assertEquals(
                AcknowledgeFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(playLogin)
        }

        val result = client.negotiate(identity)

        assertEquals(loginSuccess, result.login)
        assertEquals(playLogin, result.playLogin)
        assertEquals(listOf(corePack), result.configuration.knownPacks?.knownPacks)
        val expectedDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        assertEquals(expectedDimensionLayout, result.dimensionLayout)
        assertEquals(expectedDimensionLayout.minY, result.chunkLayout.minBlockY)
        assertEquals(expectedDimensionLayout.sectionCount, result.chunkLayout.sectionCount)
        assertEquals(
            expectedDimensionLayout.sectionCount,
            client.registries.chunkSectionCount,
        )
        assertEquals(
            VanillaProtocolData.requireRegistry(
                Identifier("worldgen/biome"),
            ).entries.size,
            client.registries.biomeRegistrySize,
        )
        server.await()
        client.close()
    }

    @Test
    fun derivesPlayFormatFromTheSynchronizedCustomRegistries() = runTest {
        val (client, serverSession) = connectionPair()
        val identity = MinecraftOfflineIdentity("RegistryProbe")
        val dimension = Identifier("test:world")
        val dimensionTypeRegistry = RegistryDataPacket(
            registryId = Identifier("dimension_type"),
            entries = listOf(
                RegistryEntry(
                    id = Identifier("test:short_dimension"),
                    data = NbtCompound(
                        mapOf(
                            "min_y" to NbtInt(0),
                            "height" to NbtInt(32),
                            "has_skylight" to NbtByte(0),
                        ),
                    ),
                ),
            ),
        )
        val biomeRegistry = RegistryDataPacket(
            registryId = Identifier("worldgen/biome"),
            entries = listOf(
                RegistryEntry(Identifier("test:first"), null),
                RegistryEntry(Identifier("test:second"), null),
            ),
        )
        val compactDimensionTypeRegistry = RegistryDataPacket(
            dimensionTypeRegistry.registryId,
            dimensionTypeRegistry.entries.map { entry ->
                RegistryEntry(entry.id, null)
            },
        )
        val protocolData = object : ProtocolDataSet by VanillaProtocolData {
            override fun registryPackets(
                clientKnownPacks: List<KnownPack>,
            ): List<RegistryDataPacket> = listOf(
                dimensionTypeRegistry,
                biomeRegistry,
            )
        }
        val playLogin = playLogin(
            dimensionTypeId = 0,
            dimension = dimension,
        )
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertIs<LoginStartPacket>(serverSession.receive())
            serverSession.send(
                LoginSuccessPacket(
                    GameProfile(identity.id, identity.name, emptyList()),
                    Uuid.fromLongs(1, 2),
                ),
            )
            assertEquals(LoginAcknowledgedPacket, serverSession.receive())
            assertIs<ConfigurationClientInformationPacket>(
                serverSession.receive(),
            )
            serverSession.send(compactDimensionTypeRegistry)
            serverSession.send(biomeRegistry)
            serverSession.send(FinishConfigurationPacket)
            assertEquals(
                AcknowledgeFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(playLogin)
        }

        val result = client.negotiate(
            identity,
            options = MinecraftClientNegotiationOptions(
                protocolData = protocolData,
            ),
        )

        assertEquals(playLogin, result.playLogin)
        assertEquals(
            MinecraftDimensionLayout(
                id = Identifier("test:short_dimension"),
                registryId = 0,
                minY = 0,
                height = 32,
                hasSkyLight = false,
            ),
            result.dimensionLayout,
        )
        assertEquals(0, result.chunkLayout.minSectionY)
        assertEquals(2, result.chunkLayout.sectionCount)
        assertEquals(
            2,
            client.registries.chunkSectionCount,
        )
        assertEquals(
            2,
            client.registries.biomeRegistrySize,
        )
        assertEquals(
            1,
            client.registries.requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                Identifier("test:second"),
            ).rawId,
        )
        server.await()
        client.close()
    }

    private fun connectionPair(): Pair<MinecraftClientConnection, MinecraftServerPacketSession> {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val client = MinecraftClientConnection(
            connection = createMinecraftClientPacketConnection(
                frameStream = clientFrames,
                closeTransport = { clientFrames.cancel() },
                definition = MinecraftConnectionDefinition(),
            ),
            serverAddress = "localhost",
            serverPort = 25_565,
        )
        val server = MinecraftServerPacketSession(
            MinecraftFrameStream(clientToServer, serverToClient),
        )
        return client to server
    }

    private fun playLogin(
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
