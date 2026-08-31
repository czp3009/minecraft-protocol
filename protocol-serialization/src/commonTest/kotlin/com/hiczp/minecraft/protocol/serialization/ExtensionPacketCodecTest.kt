package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.test.*

class ExtensionPacketCodecTest {
    @Test
    fun kotlinxSerializerDrivesARegisteredCustomPayloadBody() {
        val channel = Identifier("test:number")
        val packetCodecRegistration = PacketCodecRegistration.clientboundCustomPayload(
            connectionState = ConnectionState.CONFIGURATION,
            channel = channel,
            packetClass = TestNumberPayload::class,
            packetBodyCodec = KotlinxPacketBodyCodec(TestNumberPayload.serializer()),
        )
        val packetRegistry = PacketRegistry(MinecraftPacketRegistry.entries, listOf(packetCodecRegistration))
        val testNumberPayload = TestNumberPayload(300)
        val body = Buffer()

        packetRegistry.encodeExtensionPayloadToSink(testNumberPayload, body)
        assertContentEquals(byteArrayOf(0xAC.toByte(), 0x02), body.readByteArray())

        val source = Buffer().apply { write(byteArrayOf(0xAC.toByte(), 0x02)) }
        assertEquals(
            testNumberPayload,
            packetRegistry.decodeExtensionPayloadFromSource(
                packetRoute = PacketRoute.CustomPayload(
                    connectionState = ConnectionState.CONFIGURATION,
                    packetDirection = PacketDirection.CLIENTBOUND,
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
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            listOf(
                PacketCodecRegistration.clientboundCustomPayload(
                    ConnectionState.CONFIGURATION,
                    channel,
                    TestNumberPayload::class,
                    KotlinxPacketBodyCodec(TestNumberPayload.serializer()),
                ),
            ),
        )
        val customPayload = PacketRoute.CustomPayload(
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            packetId = 1,
            channel = channel,
        )

        assertFailsWith<MinecraftSerializationException> {
            packetRegistry.decodeExtensionPayloadFromSource(
                customPayload,
                Buffer().apply { writeByte(0x80.toByte()) },
                byteCount = 1,
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            packetRegistry.decodeExtensionPayloadFromSource(
                customPayload,
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
            PacketRegistry(MinecraftPacketRegistry.entries, listOf(topLevelCollision))
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
            PacketRegistry(MinecraftPacketRegistry.entries, listOf(first, second))
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
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries.map { packetCodec ->
                if (packetCodec === original) packetCodec.withPacketId(remappedId) else packetCodec
            },
        )

        assertNull(
            packetRegistry.codec(
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
                0,
            ),
        )
        assertEquals(
            remappedId,
            packetRegistry.encodePayload(
                StatusRequestPacket,
                ConnectionState.STATUS,
                PacketDirection.SERVERBOUND,
            ).packetKey.id,
        )
        assertEquals(
            StatusRequestPacket,
            packetRegistry.decodePayload(
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
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
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
            packetRegistry.encodePayload(
                TopLevelNumberPacket(2),
                ConnectionState.STATUS,
                PacketDirection.CLIENTBOUND,
            ).packetKey.id,
        )
        assertEquals(
            configurationId,
            packetRegistry.encodePayload(
                TopLevelNumberPacket(2),
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
            ).packetKey.id,
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
            packetRegistry.decodePayload(
                ConnectionState.STATUS,
                PacketDirection.CLIENTBOUND,
                statusId,
                byteArrayOf(0x7F, 4),
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            packetRegistry.decodePayload(
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
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: TopLevelNumberPacket,
        sink: Sink,
    ) {
        sink.writeByte(packet.value.toByte())
    }

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): TopLevelNumberPacket {
        val value = source.readByte().toInt() and 0xFF
        if (value == 0x7F) throw UnknownExtensionPacketException()
        return TopLevelNumberPacket(value)
    }
}
