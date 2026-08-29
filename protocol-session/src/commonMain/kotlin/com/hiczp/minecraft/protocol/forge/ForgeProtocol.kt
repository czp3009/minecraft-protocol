@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
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
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
        incomingCapacity: Int = MinecraftConnectionDefinition.DEFAULT_CHANNEL_CAPACITY,
        outgoingCapacity: Int = MinecraftConnectionDefinition.DEFAULT_CHANNEL_CAPACITY,
    ): MinecraftConnectionDefinition = MinecraftConnectionDefinition.compose(
        extensionCodecs = packetCodecs + extensionCodecs,
        minecraftProtocolFormat = minecraftProtocolFormat,
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
        val forgeRegistrySnapshotWire = decoder.decodeSerializableValue(
            ForgeRegistrySnapshotWire.serializer(),
        )
        return try {
            ForgeRegistrySnapshot(
                forgeRegistrySnapshotWire.ids,
                forgeRegistrySnapshotWire.aliases,
                forgeRegistrySnapshotWire.overrides,
                forgeRegistrySnapshotWire.blocked,
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
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: ForgeClientboundHandshakePacket,
        sink: Sink,
    ) {
        val forgeEnvelope = when (val forgeClientboundHandshakeMessage = packet.forgeClientboundHandshakeMessage) {
            is ForgeModVersionsMessage ->
                minecraftProtocolFormat.envelope(
                    MOD_VERSIONS,
                    ForgeModVersionsMessage.serializer(),
                    forgeClientboundHandshakeMessage
                )

            is ForgeChannelVersionsMessage ->
                minecraftProtocolFormat.envelope(
                    CHANNEL_VERSIONS,
                    ForgeChannelVersionsMessage.serializer(),
                    forgeClientboundHandshakeMessage
                )

            is ForgeRegistryListMessage ->
                minecraftProtocolFormat.envelope(
                    REGISTRY_LIST,
                    ForgeRegistryListMessage.serializer(),
                    forgeClientboundHandshakeMessage
                )

            is ForgeRegistryDataMessage ->
                minecraftProtocolFormat.envelope(
                    REGISTRY_DATA,
                    ForgeRegistryDataMessage.serializer(),
                    forgeClientboundHandshakeMessage
                )

            is ForgeConfigDataMessage ->
                minecraftProtocolFormat.envelope(
                    CONFIG_DATA,
                    ForgeConfigDataMessage.serializer(),
                    forgeClientboundHandshakeMessage
                )

            is ForgeMismatchDataMessage ->
                minecraftProtocolFormat.envelope(
                    MISMATCH_DATA,
                    ForgeMismatchDataMessage.serializer(),
                    forgeClientboundHandshakeMessage
                )
        }
        minecraftProtocolFormat.encodeToSink(forgeEnvelope, sink)
    }

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): ForgeClientboundHandshakePacket {
        val forgeEnvelope = minecraftProtocolFormat.decodeFromSource<ForgeEnvelope>(
            source,
            byteCount,
        )
        val forgeClientboundHandshakeMessage: ForgeClientboundHandshakeMessage = when (forgeEnvelope.discriminator) {
            ACKNOWLEDGE -> throw MinecraftSerializationException(
                "Forge acknowledgement is not clientbound",
            )

            MOD_VERSIONS -> minecraftProtocolFormat.decodeBody(
                ForgeModVersionsMessage.serializer(),
                forgeEnvelope,
            )

            CHANNEL_VERSIONS -> minecraftProtocolFormat.decodeBody(
                ForgeChannelVersionsMessage.serializer(),
                forgeEnvelope,
            )

            REGISTRY_LIST -> minecraftProtocolFormat.decodeBody(
                ForgeRegistryListMessage.serializer(),
                forgeEnvelope,
            )

            REGISTRY_DATA -> minecraftProtocolFormat.decodeBody(
                ForgeRegistryDataMessage.serializer(),
                forgeEnvelope,
            )

            CONFIG_DATA -> minecraftProtocolFormat.decodeBody(
                ForgeConfigDataMessage.serializer(),
                forgeEnvelope,
            )

            MISMATCH_DATA -> minecraftProtocolFormat.decodeBody(
                ForgeMismatchDataMessage.serializer(),
                forgeEnvelope,
            )

            else -> throw UnknownExtensionPacketException()
        }
        return ForgeClientboundHandshakePacket(forgeClientboundHandshakeMessage)
    }
}

private object ForgeServerboundHandshakeCodec :
    PacketBodyCodec<ForgeServerboundHandshakePacket> {
    override fun encode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: ForgeServerboundHandshakePacket,
        sink: Sink,
    ) {
        val forgeEnvelope = when (val forgeServerboundHandshakeMessage = packet.forgeServerboundHandshakeMessage) {
            is ForgeAcknowledgeMessage ->
                minecraftProtocolFormat.envelope(
                    ACKNOWLEDGE,
                    ForgeAcknowledgeMessage.serializer(),
                    forgeServerboundHandshakeMessage
                )

            is ForgeModVersionsMessage ->
                minecraftProtocolFormat.envelope(
                    MOD_VERSIONS,
                    ForgeModVersionsMessage.serializer(),
                    forgeServerboundHandshakeMessage
                )

            is ForgeChannelVersionsMessage ->
                minecraftProtocolFormat.envelope(
                    CHANNEL_VERSIONS,
                    ForgeChannelVersionsMessage.serializer(),
                    forgeServerboundHandshakeMessage
                )
        }
        minecraftProtocolFormat.encodeToSink(forgeEnvelope, sink)
    }

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): ForgeServerboundHandshakePacket {
        val forgeEnvelope = minecraftProtocolFormat.decodeFromSource<ForgeEnvelope>(
            source,
            byteCount,
        )
        val forgeServerboundHandshakeMessage: ForgeServerboundHandshakeMessage = when (forgeEnvelope.discriminator) {
            ACKNOWLEDGE -> minecraftProtocolFormat.decodeBody(
                ForgeAcknowledgeMessage.serializer(),
                forgeEnvelope,
            )

            MOD_VERSIONS -> minecraftProtocolFormat.decodeBody(
                ForgeModVersionsMessage.serializer(),
                forgeEnvelope,
            )

            CHANNEL_VERSIONS -> minecraftProtocolFormat.decodeBody(
                ForgeChannelVersionsMessage.serializer(),
                forgeEnvelope,
            )

            in REGISTRY_LIST..MISMATCH_DATA ->
                throw MinecraftSerializationException(
                    "Forge discriminator ${forgeEnvelope.discriminator} is not serverbound",
                )

            else -> throw UnknownExtensionPacketException()
        }
        return ForgeServerboundHandshakePacket(forgeServerboundHandshakeMessage)
    }
}

private object ForgeClientboundPlayHandshakeCodec :
    PacketBodyCodec<ForgeClientboundPlayHandshakePacket> {
    override fun encode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: ForgeClientboundPlayHandshakePacket,
        sink: Sink,
    ) = minecraftProtocolFormat.encodeToSink(
        ForgeEnvelope(packet.discriminator, packet.data),
        sink,
    )

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): ForgeClientboundPlayHandshakePacket = minecraftProtocolFormat.decodeFromSource<ForgeEnvelope>(
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
        minecraftProtocolFormat: MinecraftProtocolFormat,
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
        minecraftProtocolFormat.encodeToSink(
            ForgeRemainingBody(ByteString(output)),
            sink,
        )
    }

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): T {
        val byteArray = minecraftProtocolFormat.decodeFromSource<ForgeRemainingBody>(
            source,
            byteCount,
        ).data.toByteArray()
        val channels = linkedSetOf<Identifier>()
        byteArray.decodeToString().split('\u0000').forEach { literal ->
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
    kSerializer: KSerializer<T>,
    value: T,
): ForgeEnvelope = ForgeEnvelope(
    discriminator,
    ByteString(encodeToByteArray(kSerializer, value)),
)

private fun <T> MinecraftProtocolFormat.decodeBody(
    kSerializer: KSerializer<T>,
    forgeEnvelope: ForgeEnvelope,
): T = decodeFromByteArray(kSerializer, forgeEnvelope.body.toByteArray())

private const val ACKNOWLEDGE = 0
private const val MOD_VERSIONS = 1
private const val CHANNEL_VERSIONS = 2
private const val REGISTRY_LIST = 3
private const val REGISTRY_DATA = 4
private const val CONFIG_DATA = 5
private const val MISMATCH_DATA = 6
private const val PLAY_SPAWN_ENTITY = 7
private const val PLAY_OPEN_CONTAINER = 8
