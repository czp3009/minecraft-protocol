package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketDirection
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.session.RoutedCustomPayload
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class NeoForgeProtocolCodecTest {
    private val packetRegistry = PacketRegistry(
        MinecraftPacketRegistry.entries,
        NeoForgeProtocol.packetCodecs,
    )

    @Test
    fun networkSetupIndexesChannelsByTheirEncodedIdentifiers() {
        val channelId = Identifier("test:channel")
        val neoForgeNetworkSetup = NeoForgeNetworkSetup(
            mapOf(
                NeoForgeConnectionProtocol.CONFIGURATION to listOf(
                    NeoForgeNetworkChannel(channelId, "1"),
                ),
            ),
        )

        assertEquals(
            channelId,
            neoForgeNetworkSetup.channels(NeoForgeConnectionProtocol.CONFIGURATION).getValue(channelId).id
        )
    }

    @Test
    fun commonPacketsMatchSourceDerivedBytes() {
        assertContentEquals(
            byteArrayOf(1, 1),
            encode(
                NeoForgeCommonVersionPacket(listOf(1)),
                PacketDirection.CLIENTBOUND,
            ),
        )
        assertContentEquals(
            byteArrayOf(
                1,
                4, 'p'.code.toByte(), 'l'.code.toByte(),
                'a'.code.toByte(), 'y'.code.toByte(),
                1,
                3, 'a'.code.toByte(), ':'.code.toByte(), 'b'.code.toByte(),
            ),
            encode(
                NeoForgeCommonRegisterPacket(
                    1,
                    "play",
                    setOf(Identifier("a:b")),
                ),
                PacketDirection.SERVERBOUND,
            ),
        )
    }

    @Test
    fun registrationUsesTrailingNulAndIgnoresEmptySegments() {
        val neoForgeRegisterChannelsPacket = NeoForgeRegisterChannelsPacket(
            linkedSetOf(Identifier("a:b"), Identifier("c:d")),
        )
        val byteArray = encode(neoForgeRegisterChannelsPacket, PacketDirection.CLIENTBOUND)

        assertContentEquals("a:b\u0000c:d\u0000".encodeToByteArray(), byteArray)
        assertEquals(
            neoForgeRegisterChannelsPacket,
            decode(
                NeoForgeChannels.Register,
                byteArray,
                PacketDirection.CLIENTBOUND,
            ),
        )
        assertEquals(
            neoForgeRegisterChannelsPacket,
            decode(
                NeoForgeChannels.Register,
                "\u0000a:b\u0000\u0000c:d\u0000".encodeToByteArray(),
                PacketDirection.CLIENTBOUND,
            ),
        )
    }

    @Test
    fun protocolAndFlowOrdinalsMatchNeoForgeSource() {
        val neoForgeNetworkComponent = NeoForgeNetworkComponent(
            Identifier("mod:query"),
            "1",
            NeoForgePacketFlow.CLIENTBOUND,
            optional = true,
        )
        val byteArray = encode(
            NeoForgeModdedNetworkQueryPacket(
                mapOf(
                    NeoForgeConnectionProtocol.CONFIGURATION to setOf(neoForgeNetworkComponent),
                ),
            ),
            PacketDirection.SERVERBOUND,
        )

        assertContentEquals(
            byteArrayOf(
                1,
                4,
                1,
                9,
                'm'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(),
                ':'.code.toByte(), 'q'.code.toByte(), 'u'.code.toByte(),
                'e'.code.toByte(), 'r'.code.toByte(), 'y'.code.toByte(),
                1, '1'.code.toByte(),
                1,
                1,
                1,
            ),
            byteArray,
        )
        assertEquals(
            NeoForgeModdedNetworkQueryPacket(
                mapOf(
                    NeoForgeConnectionProtocol.CONFIGURATION to setOf(neoForgeNetworkComponent),
                ),
            ),
            decode(
                NeoForgeChannels.NetworkQuery,
                byteArray,
                PacketDirection.SERVERBOUND,
            ),
        )
    }

    @Test
    fun frozenRegistryAndJsonDataMapRoundTrip() {
        val neoForgeFrozenRegistryPacket = NeoForgeFrozenRegistryPacket(
            Identifier("block"),
            NeoForgeRegistrySnapshot(
                linkedMapOf(
                    2 to Identifier("mod:block"),
                    0 to Identifier("stone"),
                ),
                mapOf(Identifier("mod:old_block") to Identifier("mod:block")),
            ),
        )
        assertEquals(
            neoForgeFrozenRegistryPacket,
            decode(
                NeoForgeChannels.FrozenRegistry,
                encode(neoForgeFrozenRegistryPacket, PacketDirection.CLIENTBOUND),
                PacketDirection.CLIENTBOUND,
            ),
        )

        val neoForgeRegistryDataMapSyncPacket = NeoForgeRegistryDataMapSyncPacket(
            Identifier("item"),
            mapOf(
                Identifier("mod:data") to mapOf(
                    Identifier("mod:entry") to buildJsonObject {
                        put("enabled", true)
                        put("weight", 3)
                    },
                ),
            ),
        )
        assertEquals(
            neoForgeRegistryDataMapSyncPacket,
            decode(
                NeoForgeChannels.RegistryDataMapSync,
                encode(
                    neoForgeRegistryDataMapSyncPacket,
                    PacketDirection.CLIENTBOUND,
                    ConnectionState.PLAY,
                ),
                PacketDirection.CLIENTBOUND,
                ConnectionState.PLAY,
            ),
        )
    }

    @Test
    fun malformedKnownPayloadPropagatesAsSerializationFailure() {
        assertFailsWith<SerializationException> {
            decode(
                NeoForgeChannels.NetworkQuery,
                byteArrayOf(1, 5),
                PacketDirection.SERVERBOUND,
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            decode(
                NeoForgeChannels.Register,
                byteArrayOf(0xC0.toByte()),
                PacketDirection.CLIENTBOUND,
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            decode(
                NeoForgeChannels.FrozenRegistry,
                byteArrayOf(1),
                PacketDirection.CLIENTBOUND,
            )
        }
    }

    @Test
    fun splitEnvelopeReassemblesFullOuterPacket() {
        val routedCustomPayload = RoutedCustomPayload(
            PacketRoute.CustomPayload(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                packetId = 7,
                channel = Identifier("mod:large"),
            ),
            ByteString(ByteArray(96) { it.toByte() }),
        )
        val fragments = NeoForgeSplitPayloads.split(
            routedCustomPayload,
            maximumPartSize = 32,
        )
        assertTrue(fragments.size > 1)
        val neoForgeSplitAssembler = NeoForgeSplitAssembler()
        var reassembledRoutedCustomPayload: RoutedCustomPayload? = null
        fragments.forEach { fragment ->
            reassembledRoutedCustomPayload = neoForgeSplitAssembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                fragment,
            )
        }

        assertEquals(routedCustomPayload, reassembledRoutedCustomPayload)
        assertFalse(neoForgeSplitAssembler.isCollecting)
        assertFailsWith<MinecraftSerializationException> {
            neoForgeSplitAssembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                NeoForgeSplitPacket(ByteString(byteArrayOf(2, 1))),
            )
        }
    }

    private fun encode(
        packet: Packet,
        packetDirection: PacketDirection,
        connectionState: ConnectionState = ConnectionState.CONFIGURATION,
    ): ByteArray {
        val buffer = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            connectionState,
            packetDirection,
            buffer,
        )
        return buffer.readByteArray()
    }

    private fun decode(
        channel: Identifier,
        bytes: ByteArray,
        packetDirection: PacketDirection,
        connectionState: ConnectionState = ConnectionState.CONFIGURATION,
    ): Packet {
        val buffer = Buffer().apply { write(bytes) }
        return packetRegistry.decodeExtensionPayloadFromSource(
            PacketRoute.CustomPayload(
                connectionState,
                packetDirection,
                packetId = 0,
                channel = channel,
            ),
            buffer,
            bytes.size,
        )
    }
}
