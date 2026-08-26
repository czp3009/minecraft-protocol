package com.hiczp.minecraft.protocol.fabric

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketDirection
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.session.RoutedCustomPayload
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.*

class FabricProtocolCodecTest {
    private val packetRegistry = PacketRegistry(
        MinecraftPacketRegistry.entries,
        FabricProtocol.packetCodecs,
    )

    @Test
    fun commonPacketsMatchSourceDerivedBytes() {
        assertContentEquals(
            byteArrayOf(1, 1),
            encode(
                FabricCommonVersionPacket(listOf(1)),
                ConnectionState.CONFIGURATION,
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
                FabricCommonRegisterPacket(
                    1,
                    "play",
                    setOf(Identifier("a:b")),
                ),
                ConnectionState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
            ),
        )
    }

    @Test
    fun legacyRegistrationIsUnprefixedNulSeparatedAscii() {
        val fabricRegisterChannelsPacket = FabricRegisterChannelsPacket(
            listOf(Identifier("a:b"), Identifier("c:d")),
        )
        val byteArray = encode(
            fabricRegisterChannelsPacket,
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
        )
        assertContentEquals("a:b\u0000c:d".encodeToByteArray(), byteArray)
        assertEquals(
            fabricRegisterChannelsPacket,
            decode(
                FabricChannels.Register,
                byteArray,
                PacketDirection.CLIENTBOUND,
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            decode(
                FabricChannels.Register,
                byteArrayOf(0xC0.toByte()),
                PacketDirection.CLIENTBOUND,
            )
        }
    }

    @Test
    fun optimizedRegistrySnapshotRoundTripsExactGrouping() {
        val fabricRegistrySyncPacket = FabricRegistrySyncPacket(
            RemoteRegistrySnapshot(
                listOf(
                    RemoteRegistry(
                        Identifier("block"),
                        listOf(
                            RemoteRegistryEntry(Identifier("stone"), 0),
                            RemoteRegistryEntry(Identifier("mod:block"), 2),
                        ),
                    ),
                ),
            ),
        )
        val byteArray = encode(
            fabricRegistrySyncPacket,
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
        )
        assertContentEquals(
            byteArrayOf(
                1,
                0,
                1,
                5, 'b'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
                'c'.code.toByte(), 'k'.code.toByte(),
                0,
                2,
                0,
                1,
                0,
                1,
                5, 's'.code.toByte(), 't'.code.toByte(), 'o'.code.toByte(),
                'n'.code.toByte(), 'e'.code.toByte(),
                3, 'm'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(),
                1,
                2,
                1,
                5, 'b'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
                'c'.code.toByte(), 'k'.code.toByte(),
            ),
            byteArray,
        )
        assertEquals(
            fabricRegistrySyncPacket,
            decode(
                FabricChannels.RegistrySync,
                byteArray,
                PacketDirection.CLIENTBOUND,
            ),
        )
    }

    @Test
    fun malformedKnownFabricPayloadDoesNotBecomeUnknown() {
        assertFailsWith<MinecraftSerializationException> {
            decode(
                FabricChannels.CommonVersion,
                byteArrayOf(1),
                PacketDirection.CLIENTBOUND,
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            decode(
                FabricChannels.RegistrySync,
                byteArrayOf(1, 0, 1),
                PacketDirection.CLIENTBOUND,
            )
        }
    }

    @Test
    fun splitEnvelopeReassemblesFullOuterPayload() {
        val routedCustomPayload = RoutedCustomPayload(
            PacketRoute.CustomPayload(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                packetId = 7,
                channel = Identifier("mod:large"),
            ),
            ByteString(ByteArray(96) { it.toByte() }),
        )
        val fragments = FabricSplitPayloads.split(
            routedCustomPayload,
            maximumChunkSize = 32,
        )
        assertTrue(fragments.size > 1)
        val fabricSplitAssembler = FabricSplitAssembler(
            setOf(Identifier("mod:large")),
        )
        var reassembledRoutedCustomPayload: RoutedCustomPayload? = null
        fragments.forEach { fragment ->
            reassembledRoutedCustomPayload = fabricSplitAssembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                fragment,
            )
        }
        assertEquals(routedCustomPayload, reassembledRoutedCustomPayload)
        assertFalse(fabricSplitAssembler.isCollecting)
    }

    private fun encode(
        packet: Packet,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
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
    ): Packet {
        val buffer = Buffer().apply { write(bytes) }
        return packetRegistry.decodeExtensionPayloadFromSource(
            PacketRoute.CustomPayload(
                ConnectionState.CONFIGURATION,
                packetDirection,
                packetId = 0,
                channel = channel,
            ),
            buffer,
            bytes.size,
        )
    }
}
