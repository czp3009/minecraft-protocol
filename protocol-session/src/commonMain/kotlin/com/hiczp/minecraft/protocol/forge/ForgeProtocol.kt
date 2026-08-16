@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.wire.RemainingBytes
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.VarIntElements
import com.hiczp.minecraft.protocol.serialization.*
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

object ForgeProtocol {
    const val NETWORK_VERSION: Int = 0

    val packetCodecs: List<PacketCodecRegistration<out Packet>> = buildList {
        listOf(ConnectionState.CONFIGURATION, ConnectionState.PLAY).forEach { state ->
            add(
                PacketCodecRegistration.clientboundCustomPayload(
                    state,
                    ForgeChannels.Register,
                    ForgeRegisterChannelsPacket::class,
                    ForgeRegistrationCodec(::ForgeRegisterChannelsPacket),
                ),
            )
            add(
                PacketCodecRegistration.serverboundCustomPayload(
                    state,
                    ForgeChannels.Register,
                    ForgeRegisterChannelsPacket::class,
                    ForgeRegistrationCodec(::ForgeRegisterChannelsPacket),
                ),
            )
            add(
                PacketCodecRegistration.clientboundCustomPayload(
                    state,
                    ForgeChannels.Unregister,
                    ForgeUnregisterChannelsPacket::class,
                    ForgeRegistrationCodec(::ForgeUnregisterChannelsPacket),
                ),
            )
            add(
                PacketCodecRegistration.serverboundCustomPayload(
                    state,
                    ForgeChannels.Unregister,
                    ForgeUnregisterChannelsPacket::class,
                    ForgeRegistrationCodec(::ForgeUnregisterChannelsPacket),
                ),
            )
        }
        add(
            PacketCodecRegistration.clientboundCustomPayload(
                ConnectionState.CONFIGURATION,
                ForgeChannels.Handshake,
                ForgeClientboundHandshakePacket::class,
                ForgeClientboundHandshakeCodec,
            ),
        )
        add(
            PacketCodecRegistration.serverboundCustomPayload(
                ConnectionState.CONFIGURATION,
                ForgeChannels.Handshake,
                ForgeServerboundHandshakePacket::class,
                ForgeServerboundHandshakeCodec,
            ),
        )
        add(
            PacketCodecRegistration.clientboundCustomPayload(
                ConnectionState.PLAY,
                ForgeChannels.Handshake,
                ForgeClientboundPlayHandshakePacket::class,
                ForgeClientboundPlayHandshakeCodec,
            ),
        )
    }

    /** Pure factory; callers may retain and share its result across connections. */
    fun connectionDefinition(
        extensionCodecs: List<PacketCodecRegistration<out Packet>> = emptyList(),
        registries: ProtocolRegistryContext = ProtocolRegistryContext.Empty,
        formatConfiguration: MinecraftProtocolFormatConfiguration =
            MinecraftProtocolFormatConfiguration(),
        serializersModule: SerializersModule = EmptySerializersModule(),
        incomingCapacity: Int = MinecraftConnectionDefinition.DEFAULT_CHANNEL_CAPACITY,
        outgoingCapacity: Int = MinecraftConnectionDefinition.DEFAULT_CHANNEL_CAPACITY,
    ): MinecraftConnectionDefinition = MinecraftConnectionDefinition.compose(
        extensionCodecs = packetCodecs + extensionCodecs,
        registries = registries,
        formatConfiguration = formatConfiguration,
        serializersModule = serializersModule,
        incomingCapacity = incomingCapacity,
        outgoingCapacity = outgoingCapacity,
    )
}

@Serializable
private data class ForgeEnvelope(
    @VarInt
    val discriminator: Int,
    @RemainingBytes
    val body: ByteString,
)

@Serializable
private data class ForgeRegistrySnapshotWire(
    @VarIntElements
    val ids: Map<Identifier, Int>,
    val aliases: Map<Identifier, Identifier>,
    val overrides: Map<Identifier, String>,
    @VarIntElements
    val blocked: Set<Int>,
)

internal object ForgeRegistrySnapshotSerializer :
    KSerializer<ForgeRegistrySnapshot> {
    override val descriptor: SerialDescriptor = ForgeRegistrySnapshotWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ForgeRegistrySnapshot) {
        encoder.encodeSerializableValue(
            ForgeRegistrySnapshotWire.serializer(),
            ForgeRegistrySnapshotWire(
                value.ids,
                value.aliases,
                value.overrides,
                value.blocked,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): ForgeRegistrySnapshot {
        val wire = decoder.decodeSerializableValue(
            ForgeRegistrySnapshotWire.serializer(),
        )
        return try {
            ForgeRegistrySnapshot(
                wire.ids,
                wire.aliases,
                wire.overrides,
                wire.blocked,
            )
        } catch (cause: IllegalArgumentException) {
            throw MinecraftSerializationException(
                "Invalid Forge registry snapshot",
                cause,
            )
        }
    }
}

private object ForgeClientboundHandshakeCodec :
    PacketBodyCodec<ForgeClientboundHandshakePacket> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: ForgeClientboundHandshakePacket,
        sink: Sink,
    ) {
        val envelope = when (val message = packet.message) {
            is ForgeModVersionsMessage ->
                format.envelope(MOD_VERSIONS, ForgeModVersionsMessage.serializer(), message)

            is ForgeChannelVersionsMessage ->
                format.envelope(CHANNEL_VERSIONS, ForgeChannelVersionsMessage.serializer(), message)

            is ForgeRegistryListMessage ->
                format.envelope(REGISTRY_LIST, ForgeRegistryListMessage.serializer(), message)

            is ForgeRegistryDataMessage ->
                format.envelope(REGISTRY_DATA, ForgeRegistryDataMessage.serializer(), message)

            is ForgeConfigDataMessage ->
                format.envelope(CONFIG_DATA, ForgeConfigDataMessage.serializer(), message)

            is ForgeMismatchDataMessage ->
                format.envelope(MISMATCH_DATA, ForgeMismatchDataMessage.serializer(), message)
        }
        format.encodeToSink(ForgeEnvelope.serializer(), envelope, sink)
    }

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): ForgeClientboundHandshakePacket {
        val envelope = format.decodeFromSource(
            ForgeEnvelope.serializer(),
            source,
            byteCount,
        )
        val message: ForgeClientboundHandshakeMessage = when (envelope.discriminator) {
            ACKNOWLEDGE -> throw MinecraftSerializationException(
                "Forge acknowledgement is not clientbound",
            )

            MOD_VERSIONS -> format.decodeBody(
                ForgeModVersionsMessage.serializer(),
                envelope,
            )

            CHANNEL_VERSIONS -> format.decodeBody(
                ForgeChannelVersionsMessage.serializer(),
                envelope,
            )

            REGISTRY_LIST -> format.decodeBody(
                ForgeRegistryListMessage.serializer(),
                envelope,
            )

            REGISTRY_DATA -> format.decodeBody(
                ForgeRegistryDataMessage.serializer(),
                envelope,
            )

            CONFIG_DATA -> format.decodeBody(
                ForgeConfigDataMessage.serializer(),
                envelope,
            )

            MISMATCH_DATA -> format.decodeBody(
                ForgeMismatchDataMessage.serializer(),
                envelope,
            )

            else -> throw UnknownExtensionPacketException()
        }
        return ForgeClientboundHandshakePacket(message)
    }
}

private object ForgeServerboundHandshakeCodec :
    PacketBodyCodec<ForgeServerboundHandshakePacket> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: ForgeServerboundHandshakePacket,
        sink: Sink,
    ) {
        val envelope = when (val message = packet.message) {
            is ForgeAcknowledgeMessage ->
                format.envelope(ACKNOWLEDGE, ForgeAcknowledgeMessage.serializer(), message)

            is ForgeModVersionsMessage ->
                format.envelope(MOD_VERSIONS, ForgeModVersionsMessage.serializer(), message)

            is ForgeChannelVersionsMessage ->
                format.envelope(CHANNEL_VERSIONS, ForgeChannelVersionsMessage.serializer(), message)
        }
        format.encodeToSink(ForgeEnvelope.serializer(), envelope, sink)
    }

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): ForgeServerboundHandshakePacket {
        val envelope = format.decodeFromSource(
            ForgeEnvelope.serializer(),
            source,
            byteCount,
        )
        val message: ForgeServerboundHandshakeMessage = when (envelope.discriminator) {
            ACKNOWLEDGE -> format.decodeBody(
                ForgeAcknowledgeMessage.serializer(),
                envelope,
            )

            MOD_VERSIONS -> format.decodeBody(
                ForgeModVersionsMessage.serializer(),
                envelope,
            )

            CHANNEL_VERSIONS -> format.decodeBody(
                ForgeChannelVersionsMessage.serializer(),
                envelope,
            )

            in REGISTRY_LIST..MISMATCH_DATA ->
                throw MinecraftSerializationException(
                    "Forge discriminator ${envelope.discriminator} is not serverbound",
                )

            else -> throw UnknownExtensionPacketException()
        }
        return ForgeServerboundHandshakePacket(message)
    }
}

private object ForgeClientboundPlayHandshakeCodec :
    PacketBodyCodec<ForgeClientboundPlayHandshakePacket> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: ForgeClientboundPlayHandshakePacket,
        sink: Sink,
    ) = format.encodeToSink(
        ForgeEnvelope.serializer(),
        ForgeEnvelope(packet.discriminator, packet.data),
        sink,
    )

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): ForgeClientboundPlayHandshakePacket = format.decodeFromSource(
        ForgeEnvelope.serializer(),
        source,
        byteCount,
    ).let { envelope ->
        if (envelope.discriminator !in PLAY_SPAWN_ENTITY..PLAY_OPEN_CONTAINER) {
            throw UnknownExtensionPacketException()
        }
        ForgeClientboundPlayHandshakePacket(
            envelope.discriminator,
            envelope.body,
        )
    }
}

@Serializable
private data class ForgeRemainingBody(
    @RemainingBytes
    val data: ByteString,
)

private class ForgeRegistrationCodec<T : ForgeChannelRegistrationPacket>(
    private val factory: (Set<Identifier>) -> T,
) : PacketBodyCodec<T> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    ) {
        require(packet.channels.size <= ForgeProtocolLimits.MAX_CHANNELS) {
            "Forge registration contains too many channels"
        }
        val bytes = buildList {
            packet.channels.forEach { channel ->
                add(channel.value.encodeToByteArray())
            }
        }
        val output = ByteArray(bytes.sumOf(ByteArray::size) + bytes.size)
        var offset = 0
        bytes.forEach { encoded ->
            encoded.copyInto(output, destinationOffset = offset)
            offset += encoded.size + 1
        }
        format.encodeToSink(
            ForgeRemainingBody.serializer(),
            ForgeRemainingBody(ByteString(output)),
            sink,
        )
    }

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): T {
        val bytes = format.decodeFromSource(
            ForgeRemainingBody.serializer(),
            source,
            byteCount,
        ).data.toByteArray()
        val channels = linkedSetOf<Identifier>()
        bytes.decodeToString().split('\u0000').forEach { literal ->
            if (literal.isEmpty()) return@forEach
            try {
                channels += Identifier(literal)
            } catch (_: IllegalArgumentException) {
                // Forge's selected revision intentionally ignores invalid names.
            }
        }
        if (channels.size > ForgeProtocolLimits.MAX_CHANNELS) {
            throw MinecraftSerializationException(
                "Forge registration contains too many channels",
            )
        }
        return factory(channels)
    }
}

private fun <T> MinecraftProtocolFormat.envelope(
    discriminator: Int,
    serializer: KSerializer<T>,
    value: T,
): ForgeEnvelope = ForgeEnvelope(
    discriminator,
    ByteString(encodeToByteArray(serializer, value)),
)

private fun <T> MinecraftProtocolFormat.decodeBody(
    serializer: KSerializer<T>,
    envelope: ForgeEnvelope,
): T = decodeFromByteArray(serializer, envelope.body.toByteArray())

private const val ACKNOWLEDGE = 0
private const val MOD_VERSIONS = 1
private const val CHANNEL_VERSIONS = 2
private const val REGISTRY_LIST = 3
private const val REGISTRY_DATA = 4
private const val CONFIG_DATA = 5
private const val MISMATCH_DATA = 6
private const val PLAY_SPAWN_ENTITY = 7
private const val PLAY_OPEN_CONTAINER = 8
