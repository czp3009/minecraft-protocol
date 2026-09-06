package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.ClientIntent
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlin.test.*

class ForgeProtocolCodecTest {
    private val packetRegistry = PacketRegistry(
        PacketRegistry.vanilla.entries,
        ForgeProtocol.packetCodecs,
    )

    @Test
    fun hostnameMarkerMatchesSelectedForgeRevision() {
        val clientIntentionPacket = ClientIntentionPacket(
            1,
            "example.test",
            25_565,
            ClientIntent.LOGIN,
        )
        val enhanced = ForgeHandshake.enhance(clientIntentionPacket)

        assertEquals("example.test\u0000FORGE", enhanced.hostName)
        assertEquals(
            ForgeHandshakeIntent(true, 0, "example.test"),
            ForgeHandshake.inspect(enhanced.hostName),
        )
        assertEquals(
            ForgeHandshakeIntent(true, 3, "example.test"),
            ForgeHandshake.inspect("example.test\u0000FORGE3"),
        )
        assertFailsWith<ForgeNegotiationException> {
            ForgeHandshake.inspect("example.test\u0000FORGEx")
        }
    }

    @Test
    fun registrationIsTrailingNulSeparatedUtf8() {
        val forgeRegisterChannelsPacket = ForgeRegisterChannelsPacket(
            linkedSetOf(Identifier("a:b"), Identifier("mod:channel")),
        )
        val byteArray = encode(forgeRegisterChannelsPacket, PacketDirection.CLIENTBOUND)

        assertContentEquals("a:b\u0000mod:channel\u0000".encodeToByteArray(), byteArray)
        assertEquals(
            forgeRegisterChannelsPacket,
            decode(
                ForgeChannels.Register,
                byteArray,
                PacketDirection.CLIENTBOUND,
            ),
        )
        assertEquals(
            ForgeRegisterChannelsPacket(setOf(Identifier("a:b"))),
            decode(
                ForgeChannels.Register,
                "bad channel\u0000a:b\u0000".encodeToByteArray(),
                PacketDirection.CLIENTBOUND,
            ),
        )
    }

    @Test
    fun handshakeDiscriminatorsAndBodiesMatchForgeSource() {
        val mods = ForgeClientboundClientIntentionPacket(
            ForgeModVersionsMessage(
                mapOf("example" to ForgeModInfo("Example", "1.0")),
            ),
        )
        assertContentEquals(
            byteArrayOf(
                1,
                1,
                7,
                'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
                'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
                'e'.code.toByte(),
                7,
                'E'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
                'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
                'e'.code.toByte(),
                3, '1'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(),
            ),
            encode(mods, PacketDirection.CLIENTBOUND),
        )
        assertEquals(
            mods,
            decode(
                ForgeChannels.Handshake,
                encode(mods, PacketDirection.CLIENTBOUND),
                PacketDirection.CLIENTBOUND,
            ),
        )

        val channels = ForgeServerboundClientIntentionPacket(
            ForgeChannelVersionsMessage(
                mapOf(Identifier("mod:main") to 300),
            ),
        )
        assertEquals(2, encode(channels, PacketDirection.SERVERBOUND).first().toInt())
        assertEquals(
            channels,
            decode(
                ForgeChannels.Handshake,
                encode(channels, PacketDirection.SERVERBOUND),
                PacketDirection.SERVERBOUND,
            ),
        )
    }

    @Test
    fun registrySnapshotPreservesAliasesOverridesBlockedAndVarInts() {
        val forgeClientboundClientIntentionPacket = ForgeClientboundClientIntentionPacket(
            ForgeRegistryDataMessage(
                2,
                Identifier("block"),
                ForgeRegistrySnapshot(
                    linkedMapOf(
                        Identifier("stone") to 0,
                        Identifier("mod:block") to 300,
                    ),
                    mapOf(
                        Identifier("mod:old") to Identifier("mod:block"),
                    ),
                    mapOf(Identifier("mod:block") to "example"),
                    setOf(299),
                ),
            ),
        )
        val byteArray = encode(forgeClientboundClientIntentionPacket, PacketDirection.CLIENTBOUND)
        val decoded = decode(
            ForgeChannels.Handshake,
            byteArray,
            PacketDirection.CLIENTBOUND,
        )

        assertEquals(forgeClientboundClientIntentionPacket, decoded)
        assertTrue(
            byteArray.asList().windowed(2).any { pair ->
                pair == listOf(0xAC.toByte(), 0x02.toByte())
            },
        )
    }

    @Test
    fun unknownInnerDiscriminatorBecomesDirectionCorrectUnknownPacket() {
        val byteArray = byteArrayOf(42, 1, 2, 3)
        val decoded = assertIs<UnknownPacket.Clientbound>(
            decode(
                ForgeChannels.Handshake,
                byteArray,
                PacketDirection.CLIENTBOUND,
            ),
        )

        assertContentEquals(byteArray, decoded.data.toByteArray())
        assertEquals(
            ForgeChannels.Handshake,
            assertIs<PacketRoute.CustomPayload>(decoded.packetRoute).channel,
        )
    }

    @Test
    fun malformedKnownHandshakeMessagePropagates() {
        assertFailsWith<SerializationException> {
            decode(
                ForgeChannels.Handshake,
                byteArrayOf(1, 1),
                PacketDirection.CLIENTBOUND,
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            decode(
                ForgeChannels.Handshake,
                byteArrayOf(0, 0),
                PacketDirection.CLIENTBOUND,
            )
        }
    }

    @Test
    fun loginWrapperUsesItsEncodedChannelAndPreservesBytes() {
        val request = ForgeLoginQueries.query(
            7,
            Identifier("mod:query"),
            ByteString(byteArrayOf(9)),
        )
        val response = ForgeLoginQueries.response(
            request,
            ByteString(byteArrayOf(1, 2, 3)),
        )
        val unwrapped = ForgeLoginQueries.unwrap(response)

        assertEquals(Identifier("mod:query"), unwrapped?.channel)
        assertContentEquals(byteArrayOf(1, 2, 3), unwrapped?.data?.toByteArray())
        val reroutedResponse = UnknownPacket.Serverbound(
            PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                7,
                Identifier("outer:route"),
                hasPayload = true,
            ),
            response.data,
        )
        assertEquals(Identifier("mod:query"), ForgeLoginQueries.unwrap(reroutedResponse)?.channel)
        assertNull(ForgeLoginQueries.unwrap(ForgeLoginQueries.unsupported(request)))
    }

    private fun encode(
        packet: Packet,
        packetDirection: PacketDirection,
    ): ByteArray {
        val buffer = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            ConnectionState.CONFIGURATION,
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
