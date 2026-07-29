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
            MinecraftPacketRegistry.entries.map { it.key }.toSet().size,
        )
        assertEquals(
            MinecraftPacketRegistry.entries.size,
            MinecraftPacketRegistry.entries.map { it.packetClass }.toSet().size,
        )
    }

    @Test
    fun `registry encodes and decodes by protocol identity`() {
        val packet = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )
        val encoded = MinecraftPacketRegistry.encodePayload(packet)
        assertEquals(
            PacketKey(
                ConnectionState.HANDSHAKE,
                PacketDirection.SERVERBOUND,
                0x00,
            ),
            encoded.key,
        )
        assertEquals(PacketFraming.NORMAL, encoded.framing)
        assertEquals(
            packet,
            MinecraftPacketRegistry.decodePayload(
                encoded.key.state,
                encoded.key.direction,
                encoded.key.id,
                encoded.payload,
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
        val format = MinecraftFormat(
            MinecraftFormatConfiguration(chunkSectionCount = 0),
        )
        val failures = buildList {
            for (codec in MinecraftPacketRegistry.entries) {
                if (codec.framing != PacketFraming.NORMAL) {
                    continue
                }
                try {
                    @Suppress("UNCHECKED_CAST")
                    val serializer = codec.serializer as KSerializer<Packet>
                    val sample = serializer.minimalProtocolValue()
                    val encoded = MinecraftPacketRegistry.encodePayload(sample, format)
                    assertEquals(codec.key, encoded.key)
                    assertEquals(
                        sample,
                        MinecraftPacketRegistry.decodePayload(
                            codec.key.state,
                            codec.key.direction,
                            codec.key.id,
                            encoded.payload,
                            format,
                        ),
                        codec.packetClass.simpleName,
                    )
                } catch (cause: Throwable) {
                    add(
                        "${codec.key} ${codec.packetClass.simpleName}: " +
                                "${cause::class.simpleName}: ${cause.message}",
                    )
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    @Test
    fun `generated branch profiles round trip whenever they form a valid packet`() {
        val format = MinecraftFormat(
            MinecraftFormatConfiguration(chunkSectionCount = 0),
        )
        val coveredProfiles = mutableSetOf<ProtocolSampleProfile>()
        var successfulSamples = 0
        for (codec in MinecraftPacketRegistry.entries) {
            if (codec.framing != PacketFraming.NORMAL) {
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val serializer = codec.serializer as KSerializer<Packet>
            for (profile in ProtocolSampleProfile.entries) {
                val sample = runCatching {
                    serializer.protocolValue(profile)
                }.getOrNull() ?: continue
                val encoded = runCatching {
                    MinecraftPacketRegistry.encodePayload(sample, format)
                }.getOrNull() ?: continue

                assertEquals(
                    sample,
                    MinecraftPacketRegistry.decodePayload(
                        codec.key.state,
                        codec.key.direction,
                        codec.key.id,
                        encoded.payload,
                        format,
                    ),
                    "${codec.packetClass.simpleName} $profile",
                )
                successfulSamples++
                coveredProfiles += profile
            }
        }

        val normalPacketCount = MinecraftPacketRegistry.entries.count {
            it.framing == PacketFraming.NORMAL
        }
        assertEquals(ProtocolSampleProfile.entries.toSet(), coveredProfiles)
        assertTrue(
            successfulSamples > normalPacketCount,
            "Branch profiles did not add any packet samples",
        )
    }

    @Test
    fun `legacy packet is not marked as normally framed`() {
        val encoded = MinecraftPacketRegistry.encodePayload(
            LegacyServerListPingPacket(),
        )
        assertEquals(PacketFraming.LEGACY_UNFRAMED, encoded.framing)
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
