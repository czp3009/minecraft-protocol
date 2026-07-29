package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.data.requireRegistry
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.session.MinecraftSession
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MinecraftClientProtocolTest {
    @Test
    fun completesStatusAndPingAgainstAScriptedPeer() = runTest {
        val (clientSession, serverSession) = sessionPair()
        val client = MinecraftClientProtocol(clientSession, "localhost", 25_565)
        val server = async {
            assertIs<HandshakePacket>(serverSession.receive())
            assertEquals(StatusRequestPacket, serverSession.receive())
            serverSession.send(
                StatusResponsePacket(
                    """{"version":{"name":"26.2","protocol":776}}""",
                ),
            )
            val ping = assertIs<StatusPingRequestPacket>(serverSession.receive())
            serverSession.send(StatusPongResponsePacket(ping.timestamp))
        }

        val result = client.queryStatus(0x0102_0304_0506_0708)

        assertEquals(0x0102_0304_0506_0708, result.pong.timestamp)
        server.await()
    }

    @Test
    fun completesOfflineLoginConfigurationAndPlayEntry() = runTest {
        val (clientSession, serverSession) = sessionPair()
        val client = MinecraftClientProtocol(clientSession, "localhost", 25_565)
        val identity = MinecraftOfflineIdentity("ClientProbe")
        val loginSuccess = LoginSuccessPacket(
            profile = GameProfile(identity.id, identity.name, emptyList()),
            sessionId = Uuid(10, 20),
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
            assertEquals(
                LoginPluginResponsePacket(7, null),
                serverSession.receive(),
            )
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
            serverSession.send(FinishConfigurationPacket)
            assertEquals(
                AcknowledgeFinishConfigurationPacket,
                serverSession.receive(),
            )
            serverSession.send(playLogin)
        }

        val result = client.login(identity)

        assertEquals(loginSuccess, result.login)
        assertEquals(playLogin, result.playLogin)
        assertEquals(listOf(corePack), result.configuration.knownPacks?.knownPacks)
        assertEquals(32, clientSession.frames.codec.compressionThreshold)
        assertEquals(
            MinecraftDimensionLayout.from(
                VanillaProtocolData,
                Identifier("overworld"),
            ).sectionCount,
            clientSession.format.configuration.chunkSectionCount,
        )
        assertEquals(
            VanillaProtocolData.requireRegistry(
                Identifier("worldgen/biome"),
            ).entries.size,
            clientSession.format.configuration.biomeRegistrySize,
        )
        server.await()
    }

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

    private fun playLogin(): PlayLoginPacket {
        val overworld = Identifier("overworld")
        return PlayLoginPacket(
            playerId = 1,
            hardcore = false,
            levels = setOf(overworld),
            maxPlayers = 20,
            chunkRadius = 8,
            simulationDistance = 8,
            reducedDebugInfo = false,
            showDeathScreen = true,
            limitedCrafting = false,
            spawnInfo = CommonPlayerSpawnInfo(
                dimensionTypeId = 0,
                dimension = overworld,
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
