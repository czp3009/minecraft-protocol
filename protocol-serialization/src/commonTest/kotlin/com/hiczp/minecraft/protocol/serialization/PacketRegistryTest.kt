package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ClientIntent
import com.hiczp.minecraft.protocol.model.type.DialogHolder
import kotlinx.serialization.KSerializer
import kotlin.test.*

class PacketRegistryTest {
    @Test
    fun `shared packet classes select the codec registered for their current state`() {
        val packet = ClientboundShowDialogPacket(DialogHolder.Direct(NbtString("x")))
        val configuration =
            PacketRegistry.vanilla.encodePayload(packet, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND)
        val play = PacketRegistry.vanilla.encodePayload(packet, ConnectionState.PLAY, PacketDirection.CLIENTBOUND)
        assertContentEquals("08000178".hexToByteArray(), configuration.payload)
        assertContentEquals("0008000178".hexToByteArray(), play.payload)
        for (encoded in listOf(configuration, play)) {
            assertEquals(
                packet,
                PacketRegistry.vanilla.decodePayload(
                    encoded.packetKey.connectionState,
                    encoded.packetKey.packetDirection,
                    encoded.packetKey.id,
                    encoded.payload
                )
            )
        }
        assertFailsWith<MinecraftSerializationException> { PacketRegistry.vanilla.encodePayload(packet) }
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.vanilla.encodePayload(
                ClientboundShowDialogPacket(DialogHolder.Reference(0)),
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND
            )
        }
    }

    @Test
    fun `registry identities and class routes are unique`() {
        assertTrue(PacketRegistry.vanilla.entries.isNotEmpty())
        assertEquals(
            PacketRegistry.vanilla.entries.size,
            PacketRegistry.vanilla.entries.map { it.packetKey }.toSet().size,
        )
        assertEquals(
            PacketRegistry.vanilla.entries.size,
            PacketRegistry.vanilla.entries.map {
                Triple(
                    it.packetClass,
                    it.packetKey.connectionState,
                    it.packetKey.packetDirection
                )
            }.toSet().size,
        )
    }

    @Test
    fun `registry encodes and decodes by protocol identity`() {
        val clientIntentionPacket = ClientIntentionPacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            hostName = "localhost",
            port = 25_565,
            intention = ClientIntent.STATUS,
        )
        val encodedPacketPayload = PacketRegistry.vanilla.encodePayload(clientIntentionPacket)
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
            clientIntentionPacket,
            PacketRegistry.vanilla.decodePayload(
                encodedPacketPayload.packetKey.connectionState,
                encodedPacketPayload.packetKey.packetDirection,
                encodedPacketPayload.packetKey.id,
                encodedPacketPayload.payload,
            ),
        )

        assertIs<ServerboundStatusRequestPacket>(
            PacketRegistry.vanilla.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
                0x00,
                byteArrayOf(),
            ),
        )
    }

    @Test
    fun `every registered normal packet has an executable binary round trip`() {
        val minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
            MinecraftPacketPayloadFormatConfiguration(
                packetCodecContext = testPacketCodecContext(),
            ),
        )
        val failures = buildList {
            for (packetCodec in PacketRegistry.vanilla.entries) {
                if (packetCodec.packetFraming != PacketFraming.NORMAL) {
                    continue
                }
                try {
                    @Suppress("UNCHECKED_CAST")
                    val kSerializer = packetCodec.kSerializer as KSerializer<Packet>
                    val sample = kSerializer.packetSampleValue(PacketSampleProfile.MINIMAL)
                    val encodedPacketPayload = PacketRegistry.vanilla.encodePayload(
                        sample,
                        packetCodec.packetKey.connectionState,
                        packetCodec.packetKey.packetDirection,
                        minecraftPacketPayloadFormat
                    )
                    assertEquals(packetCodec.packetKey, encodedPacketPayload.packetKey)
                    assertEquals(
                        sample,
                        PacketRegistry.vanilla.decodePayload(
                            packetCodec.packetKey.connectionState,
                            packetCodec.packetKey.packetDirection,
                            packetCodec.packetKey.id,
                            encodedPacketPayload.payload,
                            minecraftPacketPayloadFormat,
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
        val minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
            MinecraftPacketPayloadFormatConfiguration(
                packetCodecContext = testPacketCodecContext(),
            ),
        )
        val coveredProfiles = mutableSetOf<PacketSampleProfile>()
        var successfulSamples = 0
        for (packetCodec in PacketRegistry.vanilla.entries) {
            if (packetCodec.packetFraming != PacketFraming.NORMAL) {
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val kSerializer = packetCodec.kSerializer as KSerializer<Packet>
            for (packetSampleProfile in PacketSampleProfile.entries) {
                val sample = runCatching {
                    kSerializer.packetSampleValue(packetSampleProfile)
                }.getOrNull() ?: continue
                val encodedPacketPayload = runCatching {
                    PacketRegistry.vanilla.encodePayload(
                        sample,
                        packetCodec.packetKey.connectionState,
                        packetCodec.packetKey.packetDirection,
                        minecraftPacketPayloadFormat
                    )
                }.getOrNull() ?: continue

                assertEquals(
                    sample,
                    PacketRegistry.vanilla.decodePayload(
                        packetCodec.packetKey.connectionState,
                        packetCodec.packetKey.packetDirection,
                        packetCodec.packetKey.id,
                        encodedPacketPayload.payload,
                        minecraftPacketPayloadFormat,
                    ),
                    "${packetCodec.packetClass.simpleName} $packetSampleProfile",
                )
                successfulSamples++
                coveredProfiles += packetSampleProfile
            }
        }

        val normalPacketCount = PacketRegistry.vanilla.entries.count {
            it.packetFraming == PacketFraming.NORMAL
        }
        assertEquals(PacketSampleProfile.entries.toSet(), coveredProfiles)
        assertTrue(
            successfulSamples > normalPacketCount,
            "Branch profiles did not add any packet samples",
        )
    }

    @Test
    fun `legacy packet is not marked as normally framed`() {
        val encodedPacketPayload = PacketRegistry.vanilla.encodePayload(
            LegacyServerListPingPacket(),
        )
        assertEquals(PacketFraming.LEGACY_UNFRAMED, encodedPacketPayload.packetFraming)
    }

    @Test
    fun `unknown packet identity is rejected`() {
        assertFailsWith<MinecraftSerializationException> {
            PacketRegistry.vanilla.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.CLIENTBOUND,
                0x7F,
                byteArrayOf(),
            )
        }
    }
}
