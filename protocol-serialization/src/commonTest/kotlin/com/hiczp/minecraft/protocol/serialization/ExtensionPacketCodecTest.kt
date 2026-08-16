package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.test.*

class ExtensionPacketCodecTest {
    @Test
    fun kotlinxSerializerDrivesARegisteredCustomPayloadBody() {
        val channel = Identifier("test:number")
        val registration = PacketCodecRegistration.clientboundCustomPayload(
            state = ConnectionState.CONFIGURATION,
            channel = channel,
            packetClass = TestNumberPayload::class,
            codec = KotlinxPacketBodyCodec(TestNumberPayload.serializer()),
        )
        val registry = MinecraftPacketRegistry.compose(listOf(registration))
        val packet = TestNumberPayload(300)
        val body = Buffer()

        registry.encodeExtensionPayloadToSink(packet, body)
        assertContentEquals(byteArrayOf(0xAC.toByte(), 0x02), body.readByteArray())

        val source = Buffer().apply { write(byteArrayOf(0xAC.toByte(), 0x02)) }
        assertEquals(
            packet,
            registry.decodeExtensionPayloadFromSource(
                route = PacketRoute.CustomPayload(
                    state = ConnectionState.CONFIGURATION,
                    direction = PacketDirection.CLIENTBOUND,
                    packetId = 1,
                    channel = channel,
                ),
                source = source,
                byteCount = 2,
            ),
        )
    }

    @Test
    fun aKnownExtensionNeverDowngradesMalformedBytesToUnknown() {
        val channel = Identifier("test:number")
        val registry = MinecraftPacketRegistry.compose(
            listOf(
                PacketCodecRegistration.clientboundCustomPayload(
                    ConnectionState.CONFIGURATION,
                    channel,
                    TestNumberPayload::class,
                    KotlinxPacketBodyCodec(TestNumberPayload.serializer()),
                ),
            ),
        )
        val route = PacketRoute.CustomPayload(
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            packetId = 1,
            channel = channel,
        )

        assertFailsWith<MinecraftSerializationException> {
            registry.decodeExtensionPayloadFromSource(
                route,
                Buffer().apply { writeByte(0x80.toByte()) },
                byteCount = 1,
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            registry.decodeExtensionPayloadFromSource(
                route,
                Buffer().apply { write(byteArrayOf(1, 2)) },
                byteCount = 2,
            )
        }
    }

    @Test
    fun registrationRejectsVanillaIdsAndDuplicateRoutes() {
        val topLevelCollision = PacketCodecRegistration.clientboundTopLevel(
            ConnectionState.STATUS,
            packetId = 0,
            TestNumberPayload::class,
            KotlinxPacketBodyCodec(TestNumberPayload.serializer()),
        )
        assertFailsWith<IllegalArgumentException> {
            MinecraftPacketRegistry.compose(listOf(topLevelCollision))
        }

        val channel = Identifier("test:duplicate")
        val first = PacketCodecRegistration.clientboundCustomPayload(
            ConnectionState.CONFIGURATION,
            channel,
            TestNumberPayload::class,
            KotlinxPacketBodyCodec(TestNumberPayload.serializer()),
        )
        val second = PacketCodecRegistration.clientboundCustomPayload(
            ConnectionState.CONFIGURATION,
            channel,
            OtherTestNumberPayload::class,
            KotlinxPacketBodyCodec(OtherTestNumberPayload.serializer()),
        )
        assertFailsWith<IllegalArgumentException> {
            MinecraftPacketRegistry.compose(listOf(first, second))
        }
    }

    @Test
    fun immutableBaseCodecsCanBeReindexedForAModdedProtocolTable() {
        val original = requireNotNull(
            MinecraftPacketRegistry.codec(
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
                0,
            ),
        )
        val remappedId = 0x3FFD
        val registry = PacketRegistry(
            MinecraftPacketRegistry.entries.map { codec ->
                if (codec === original) codec.withPacketId(remappedId) else codec
            },
        )

        assertNull(
            registry.codec(
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
                0,
            ),
        )
        assertEquals(
            remappedId,
            registry.encodePayload(
                com.hiczp.minecraft.protocol.model.packet.StatusRequestPacket,
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
            ).key.id,
        )
        assertEquals(
            com.hiczp.minecraft.protocol.model.packet.StatusRequestPacket,
            registry.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
                remappedId,
                byteArrayOf(),
            ),
        )
    }

    @Test
    fun topLevelExtensionsSelectTheirStateAndPreserveIntentionalUnknownBodies() {
        val statusId = 0x3FFE
        val configurationId = 0x3FFF
        val registry = MinecraftPacketRegistry.compose(
            listOf(
                PacketCodecRegistration.clientboundTopLevel(
                    ConnectionState.STATUS,
                    statusId,
                    TopLevelNumberPacket::class,
                    TopLevelNumberPacketCodec,
                ),
                PacketCodecRegistration.clientboundTopLevel(
                    ConnectionState.CONFIGURATION,
                    configurationId,
                    TopLevelNumberPacket::class,
                    TopLevelNumberPacketCodec,
                ),
            ),
        )

        assertEquals(
            statusId,
            registry.encodePayload(
                TopLevelNumberPacket(2),
                ConnectionState.STATUS,
                PacketDirection.CLIENTBOUND,
            ).key.id,
        )
        assertEquals(
            configurationId,
            registry.encodePayload(
                TopLevelNumberPacket(2),
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
            ).key.id,
        )

        assertEquals(
            UnknownPacket.Clientbound(
                PacketRoute.TopLevel(
                    ConnectionState.STATUS,
                    PacketDirection.CLIENTBOUND,
                    statusId,
                ),
                ByteString(byteArrayOf(0x7F, 4)),
            ),
            registry.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.CLIENTBOUND,
                statusId,
                byteArrayOf(0x7F, 4),
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            registry.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.CLIENTBOUND,
                statusId,
                byteArrayOf(1, 2),
            )
        }
    }
}

@Serializable
private data class TestNumberPayload(
    @VarInt val value: Int,
) : ClientboundPacket.Extension

@Serializable
private data class OtherTestNumberPayload(
    @VarInt val value: Int,
) : ClientboundPacket.Extension

private data class TopLevelNumberPacket(
    val value: Int,
) : ClientboundPacket.Extension

private data object TopLevelNumberPacketCodec :
    PacketBodyCodec<TopLevelNumberPacket> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: TopLevelNumberPacket,
        sink: kotlinx.io.Sink,
    ) {
        sink.writeByte(packet.value.toByte())
    }

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: kotlinx.io.Source,
        byteCount: Int,
    ): TopLevelNumberPacket {
        val value = source.readByte().toInt() and 0xFF
        if (value == 0x7F) throw UnknownExtensionPacketException()
        return TopLevelNumberPacket(value)
    }
}
