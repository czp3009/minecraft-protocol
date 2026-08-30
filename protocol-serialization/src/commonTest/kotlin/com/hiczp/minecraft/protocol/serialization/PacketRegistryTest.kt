package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import kotlinx.serialization.KSerializer
import kotlin.test.*

class PacketRegistryTest {
    @Test
    fun `registry identities and packet classes are unique`() {
        assertTrue(MinecraftPacketRegistry.entries.isNotEmpty())
        assertEquals(
            MinecraftPacketRegistry.entries.size,
            MinecraftPacketRegistry.entries.map { it.packetKey }.toSet().size,
        )
        assertEquals(
            MinecraftPacketRegistry.entries.size,
            MinecraftPacketRegistry.entries.map { it.packetClass }.toSet().size,
        )
    }

    @Test
    fun `registry encodes and decodes by protocol identity`() {
        val handshakePacket = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )
        val encodedPacketPayload = MinecraftPacketRegistry.encodePayload(handshakePacket)
        assertEquals(
            PacketKey(
                ConnectionState.HANDSHAKE,
                PacketDirection.SERVERBOUND,
                0x00,
            ),
            encodedPacketPayload.packetKey,
        )
        assertEquals(PacketFraming.NORMAL, encodedPacketPayload.packetFraming)
        assertEquals(
            handshakePacket,
            MinecraftPacketRegistry.decodePayload(
                encodedPacketPayload.packetKey.connectionState,
                encodedPacketPayload.packetKey.packetDirection,
                encodedPacketPayload.packetKey.id,
                encodedPacketPayload.payload,
            ),
        )

        assertIs<StatusRequestPacket>(
            MinecraftPacketRegistry.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
                0x00,
                byteArrayOf(),
            ),
        )
    }

    @Test
    fun `every registered normal packet has an executable binary round trip`() {
        val minecraftProtocolFormat = MinecraftProtocolFormat(
            MinecraftProtocolFormatConfiguration(
                protocolRegistryContext = testProtocolRegistryContext(chunkSectionCount = 0),
            ),
        )
        val failures = buildList {
            for (packetCodec in MinecraftPacketRegistry.entries) {
                if (packetCodec.packetFraming != PacketFraming.NORMAL) {
                    continue
                }
                try {
                    @Suppress("UNCHECKED_CAST")
                    val kSerializer = packetCodec.kSerializer as KSerializer<Packet>
                    val sample = kSerializer.protocolValue(ProtocolSampleProfile.MINIMAL)
                    val encodedPacketPayload = MinecraftPacketRegistry.encodePayload(sample, minecraftProtocolFormat)
                    assertEquals(packetCodec.packetKey, encodedPacketPayload.packetKey)
                    assertEquals(
                        sample,
                        MinecraftPacketRegistry.decodePayload(
                            packetCodec.packetKey.connectionState,
                            packetCodec.packetKey.packetDirection,
                            packetCodec.packetKey.id,
                            encodedPacketPayload.payload,
                            minecraftProtocolFormat,
                        ),
                        packetCodec.packetClass.simpleName,
                    )
                } catch (cause: Throwable) {
                    val causeMessage = cause.message.orEmpty().lineSequence().joinToString(" | ")
                    add(
                        "${packetCodec.packetKey} ${packetCodec.packetClass.simpleName}: ${cause::class.simpleName}: $causeMessage",
                    )
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString())
        }
    }

    @Test
    fun `generated branch profiles round trip whenever they form a valid packet`() {
        val minecraftProtocolFormat = MinecraftProtocolFormat(
            MinecraftProtocolFormatConfiguration(
                protocolRegistryContext = testProtocolRegistryContext(chunkSectionCount = 0),
            ),
        )
        val coveredProfiles = mutableSetOf<ProtocolSampleProfile>()
        var successfulSamples = 0
        for (packetCodec in MinecraftPacketRegistry.entries) {
            if (packetCodec.packetFraming != PacketFraming.NORMAL) {
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val kSerializer = packetCodec.kSerializer as KSerializer<Packet>
            for (protocolSampleProfile in ProtocolSampleProfile.entries) {
                val sample = runCatching {
                    kSerializer.protocolValue(protocolSampleProfile)
                }.getOrNull() ?: continue
                val encodedPacketPayload = runCatching {
                    MinecraftPacketRegistry.encodePayload(sample, minecraftProtocolFormat)
                }.getOrNull() ?: continue

                assertEquals(
                    sample,
                    MinecraftPacketRegistry.decodePayload(
                        packetCodec.packetKey.connectionState,
                        packetCodec.packetKey.packetDirection,
                        packetCodec.packetKey.id,
                        encodedPacketPayload.payload,
                        minecraftProtocolFormat,
                    ),
                    "${packetCodec.packetClass.simpleName} $protocolSampleProfile",
                )
                successfulSamples++
                coveredProfiles += protocolSampleProfile
            }
        }

        val normalPacketCount = MinecraftPacketRegistry.entries.count {
            it.packetFraming == PacketFraming.NORMAL
        }
        assertEquals(ProtocolSampleProfile.entries.toSet(), coveredProfiles)
        assertTrue(
            successfulSamples > normalPacketCount,
            "Branch profiles did not add any packet samples",
        )
    }

    @Test
    fun `legacy packet is not marked as normally framed`() {
        val encodedPacketPayload = MinecraftPacketRegistry.encodePayload(
            LegacyServerListPingPacket(),
        )
        assertEquals(PacketFraming.LEGACY_UNFRAMED, encodedPacketPayload.packetFraming)
    }

    @Test
    fun `unknown packet identity is rejected`() {
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketRegistry.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.CLIENTBOUND,
                0x7F,
                byteArrayOf(),
            )
        }
    }
}
