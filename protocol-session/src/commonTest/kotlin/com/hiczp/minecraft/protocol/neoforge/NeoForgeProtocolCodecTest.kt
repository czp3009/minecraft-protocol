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
    private val registry = PacketRegistry(
        MinecraftPacketRegistry.entries,
        NeoForgeProtocol.packetCodecs,
    )

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
        val packet = NeoForgeRegisterChannelsPacket(
            linkedSetOf(Identifier("a:b"), Identifier("c:d")),
        )
        val bytes = encode(packet, PacketDirection.CLIENTBOUND)

        assertContentEquals("a:b\u0000c:d\u0000".encodeToByteArray(), bytes)
        assertEquals(
            packet,
            decode(
                NeoForgeChannels.Register,
                bytes,
                PacketDirection.CLIENTBOUND,
            ),
        )
        assertEquals(
            packet,
            decode(
                NeoForgeChannels.Register,
                "\u0000a:b\u0000\u0000c:d\u0000".encodeToByteArray(),
                PacketDirection.CLIENTBOUND,
            ),
        )
    }

    @Test
    fun protocolAndFlowOrdinalsMatchNeoForgeSource() {
        val component = NeoForgeNetworkComponent(
            Identifier("mod:query"),
            "1",
            NeoForgePacketFlow.CLIENTBOUND,
            optional = true,
        )
        val bytes = encode(
            NeoForgeModdedNetworkQueryPacket(
                mapOf(
                    NeoForgeConnectionProtocol.CONFIGURATION to setOf(component),
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
            bytes,
        )
        assertEquals(
            NeoForgeModdedNetworkQueryPacket(
                mapOf(
                    NeoForgeConnectionProtocol.CONFIGURATION to setOf(component),
                ),
            ),
            decode(
                NeoForgeChannels.NetworkQuery,
                bytes,
                PacketDirection.SERVERBOUND,
            ),
        )
    }

    @Test
    fun frozenRegistryAndJsonDataMapRoundTrip() {
        val frozen = NeoForgeFrozenRegistryPacket(
            Identifier("block"),
            NeoForgeRegistrySnapshot(
                linkedMapOf(
                    0 to Identifier("stone"),
                    2 to Identifier("mod:block"),
                ),
                mapOf(Identifier("mod:old_block") to Identifier("mod:block")),
            ),
        )
        assertEquals(
            frozen,
            decode(
                NeoForgeChannels.FrozenRegistry,
                encode(frozen, PacketDirection.CLIENTBOUND),
                PacketDirection.CLIENTBOUND,
            ),
        )

        val dataMap = NeoForgeRegistryDataMapSyncPacket(
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
            dataMap,
            decode(
                NeoForgeChannels.RegistryDataMapSync,
                encode(
                    dataMap,
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
        val routed = RoutedCustomPayload(
            PacketRoute.CustomPayload(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                packetId = 7,
                channel = Identifier("mod:large"),
            ),
            ByteString(ByteArray(96) { it.toByte() }),
        )
        val fragments = NeoForgeSplitPayloads.split(
            routed,
            maximumPartSize = 32,
        )
        assertTrue(fragments.size > 1)
        val assembler = NeoForgeSplitAssembler()
        var result: RoutedCustomPayload? = null
        fragments.forEach { fragment ->
            result = assembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                fragment,
            )
        }

        assertEquals(routed, result)
        assertFalse(assembler.isCollecting)
        assertFailsWith<MinecraftSerializationException> {
            assembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                NeoForgeSplitPacket(ByteString(byteArrayOf(2, 1))),
            )
        }
    }

    private fun encode(
        packet: Packet,
        direction: PacketDirection,
        state: ConnectionState = ConnectionState.CONFIGURATION,
    ): ByteArray {
        val buffer = Buffer()
        registry.encodeExtensionPayloadToSink(
            packet,
            state,
            direction,
            buffer,
        )
        return buffer.readByteArray()
    }

    private fun decode(
        channel: Identifier,
        bytes: ByteArray,
        direction: PacketDirection,
        state: ConnectionState = ConnectionState.CONFIGURATION,
    ): Packet {
        val buffer = Buffer().apply { write(bytes) }
        return registry.decodeExtensionPayloadFromSource(
            PacketRoute.CustomPayload(
                state,
                direction,
                packetId = 0,
                channel = channel,
            ),
            buffer,
            bytes.size,
        )
    }
}
