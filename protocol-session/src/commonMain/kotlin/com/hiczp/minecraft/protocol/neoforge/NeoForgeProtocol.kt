@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.wire.RemainingBytes
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

object NeoForgeProtocol {
    const val COMMON_PACKET_VERSION: Int = 1
    const val MAX_COMMON_VERSIONS: Int = 64
    const val MAX_CHANNELS: Int = 8_192
    const val MAX_CHANNEL_NAME_LENGTH: Int = 128
    const val SPLIT_PART_SIZE: Int = 2_097_126

    val packetCodecs: List<PacketCodecRegistration<out Packet>> = buildList {
        addBidirectional(
            NeoForgeChannels.Register,
            NeoForgeRegistrationCodec(::NeoForgeRegisterChannelsPacket),
            NeoForgeRegisterChannelsPacket::class,
        )
        addBidirectional(
            NeoForgeChannels.Unregister,
            NeoForgeRegistrationCodec(::NeoForgeUnregisterChannelsPacket),
            NeoForgeUnregisterChannelsPacket::class,
        )
        addBidirectional(
            NeoForgeChannels.NetworkQuery,
            KotlinxPacketBodyCodec(NeoForgeModdedNetworkQueryPacket.serializer()),
            NeoForgeModdedNetworkQueryPacket::class,
        )
        addBidirectional(
            NeoForgeChannels.Network,
            KotlinxPacketBodyCodec(NeoForgeModdedNetworkPacket.serializer()),
            NeoForgeModdedNetworkPacket::class,
        )
        addClientbound(
            NeoForgeChannels.NetworkSetupFailed,
            KotlinxPacketBodyCodec(
                NeoForgeModdedNetworkSetupFailedPacket.serializer(),
            ),
            NeoForgeModdedNetworkSetupFailedPacket::class,
            CONFIGURATION_AND_PLAY,
        )
        addBidirectional(
            NeoForgeChannels.CommonVersion,
            KotlinxPacketBodyCodec(NeoForgeCommonVersionPacket.serializer()),
            NeoForgeCommonVersionPacket::class,
        )
        addBidirectional(
            NeoForgeChannels.CommonRegister,
            KotlinxPacketBodyCodec(NeoForgeCommonRegisterPacket.serializer()),
            NeoForgeCommonRegisterPacket::class,
        )
        addBidirectional(
            NeoForgeChannels.Split,
            KotlinxPacketBodyCodec(NeoForgeSplitPacket.serializer()),
            NeoForgeSplitPacket::class,
        )
        addClientbound(
            NeoForgeChannels.FrozenRegistrySyncStart,
            KotlinxPacketBodyCodec(
                NeoForgeFrozenRegistrySyncStartPacket.serializer(),
            ),
            NeoForgeFrozenRegistrySyncStartPacket::class,
        )
        addClientbound(
            NeoForgeChannels.FrozenRegistry,
            KotlinxPacketBodyCodec(NeoForgeFrozenRegistryPacket.serializer()),
            NeoForgeFrozenRegistryPacket::class,
        )
        add(
            PacketCodecRegistration.clientboundCustomPayload(
                ConnectionState.CONFIGURATION,
                NeoForgeChannels.FrozenRegistrySyncCompleted,
                NeoForgeFrozenRegistrySyncCompletedPacket::class,
                KotlinxPacketBodyCodec(
                    NeoForgeFrozenRegistrySyncCompletedPacket.serializer(),
                ),
            ),
        )
        add(
            PacketCodecRegistration.serverboundCustomPayload(
                ConnectionState.CONFIGURATION,
                NeoForgeChannels.FrozenRegistrySyncCompleted,
                NeoForgeFrozenRegistrySyncCompletedPacket::class,
                KotlinxPacketBodyCodec(
                    NeoForgeFrozenRegistrySyncCompletedPacket.serializer(),
                ),
            ),
        )
        addClientbound(
            NeoForgeChannels.ConfigFile,
            KotlinxPacketBodyCodec(NeoForgeConfigFilePacket.serializer()),
            NeoForgeConfigFilePacket::class,
            CONFIGURATION_AND_PLAY,
        )
        addClientbound(
            NeoForgeChannels.KnownRegistryDataMaps,
            KotlinxPacketBodyCodec(
                NeoForgeKnownRegistryDataMapsPacket.serializer(),
            ),
            NeoForgeKnownRegistryDataMapsPacket::class,
        )
        addServerbound(
            NeoForgeChannels.KnownRegistryDataMapsReply,
            KotlinxPacketBodyCodec(
                NeoForgeKnownRegistryDataMapsReplyPacket.serializer(),
            ),
            NeoForgeKnownRegistryDataMapsReplyPacket::class,
        )
        addClientbound(
            NeoForgeChannels.ExtensibleEnumData,
            KotlinxPacketBodyCodec(NeoForgeExtensibleEnumDataPacket.serializer()),
            NeoForgeExtensibleEnumDataPacket::class,
        )
        addServerbound(
            NeoForgeChannels.ExtensibleEnumAcknowledge,
            KotlinxPacketBodyCodec(
                NeoForgeExtensibleEnumAcknowledgePacket.serializer(),
            ),
            NeoForgeExtensibleEnumAcknowledgePacket::class,
        )
        addClientbound(
            NeoForgeChannels.FeatureFlagData,
            KotlinxPacketBodyCodec(NeoForgeFeatureFlagDataPacket.serializer()),
            NeoForgeFeatureFlagDataPacket::class,
        )
        addServerbound(
            NeoForgeChannels.FeatureFlagAcknowledge,
            KotlinxPacketBodyCodec(
                NeoForgeFeatureFlagAcknowledgePacket.serializer(),
            ),
            NeoForgeFeatureFlagAcknowledgePacket::class,
        )
        addClientbound(
            NeoForgeChannels.RegistryDataMapSync,
            KotlinxPacketBodyCodec(
                NeoForgeRegistryDataMapSyncPacket.serializer(),
            ),
            NeoForgeRegistryDataMapSyncPacket::class,
            listOf(ConnectionState.PLAY),
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

internal object NeoForgeRegistrySnapshotSerializer :
    KSerializer<NeoForgeRegistrySnapshot> {
    override val descriptor: SerialDescriptor = NeoForgeRegistrySnapshotWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: NeoForgeRegistrySnapshot) {
        encoder.encodeSerializableValue(
            NeoForgeRegistrySnapshotWire.serializer(),
            NeoForgeRegistrySnapshotWire(value.ids, value.aliases),
        )
    }

    override fun deserialize(decoder: Decoder): NeoForgeRegistrySnapshot {
        val wire = decoder.decodeSerializableValue(
            NeoForgeRegistrySnapshotWire.serializer(),
        )
        return try {
            NeoForgeRegistrySnapshot(wire.ids, wire.aliases)
        } catch (cause: IllegalArgumentException) {
            throw MinecraftSerializationException(
                "Invalid NeoForge registry snapshot",
                cause,
            )
        }
    }
}

@Serializable
private data class NeoForgeRegistrySnapshotWire(
    @VarIntElements
    val ids: Map<Int, Identifier>,
    val aliases: Map<Identifier, Identifier>,
)

internal object NeoForgeRegistryDataMapSyncSerializer :
    KSerializer<NeoForgeRegistryDataMapSyncPacket> {
    private val json = Json
    override val descriptor: SerialDescriptor = NeoForgeRegistryDataMapSyncWire.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: NeoForgeRegistryDataMapSyncPacket,
    ) {
        encoder.encodeSerializableValue(
            NeoForgeRegistryDataMapSyncWire.serializer(),
            NeoForgeRegistryDataMapSyncWire(
                value.registry,
                value.dataMaps.mapValues { (_, entries) ->
                    entries.mapValues { (_, element) ->
                        json.encodeToString(JsonElement.serializer(), element)
                    }
                },
            ),
        )
    }

    override fun deserialize(decoder: Decoder): NeoForgeRegistryDataMapSyncPacket {
        val wire = decoder.decodeSerializableValue(
            NeoForgeRegistryDataMapSyncWire.serializer(),
        )
        val dataMaps = try {
            wire.dataMaps.mapValues { (_, entries) ->
                entries.mapValues { (_, encoded) ->
                    json.parseToJsonElement(encoded)
                }
            }
        } catch (cause: IllegalArgumentException) {
            throw MinecraftSerializationException(
                "Invalid NeoForge registry data-map JSON",
                cause,
            )
        }
        return NeoForgeRegistryDataMapSyncPacket(wire.registry, dataMaps)
    }
}

@Serializable
private data class NeoForgeRegistryDataMapSyncWire(
    val registry: Identifier,
    val dataMaps: Map<Identifier, Map<Identifier, String>>,
)

@Serializable
private data class RemainingRegistrationBody(
    @RemainingBytes
    val bytes: ByteString,
)

private class NeoForgeRegistrationCodec<T : NeoForgeChannelRegistrationPacket>(
    private val factory: (Set<Identifier>) -> T,
) : PacketBodyCodec<T> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    ) {
        require(packet.channels.size <= NeoForgeProtocol.MAX_CHANNELS) {
            "NeoForge registration has too many channels"
        }
        val encodedChannels = packet.channels.map { channel ->
            validateChannel(channel)
            channel.value.encodeToByteArray().also { bytes ->
                require(bytes.all { byte -> byte.toInt() and 0xFF <= 0x7F }) {
                    "NeoForge channel names must be US-ASCII: $channel"
                }
            }
        }
        val output = ByteArray(
            encodedChannels.sumOf(ByteArray::size) + encodedChannels.size,
        )
        var offset = 0
        encodedChannels.forEach { bytes ->
            bytes.copyInto(output, destinationOffset = offset)
            offset += bytes.size + 1
        }
        format.encodeToSink(
            RemainingRegistrationBody.serializer(),
            RemainingRegistrationBody(ByteString(output)),
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
            RemainingRegistrationBody.serializer(),
            source,
            byteCount,
        ).bytes.toByteArray()
        if (bytes.any { byte -> byte.toInt() and 0xFF > 0x7F }) {
            throw MinecraftSerializationException(
                "NeoForge channel registration is not US-ASCII",
            )
        }
        val literals = bytes.decodeToString().split('\u0000')
            .filter(String::isNotEmpty)
        if (literals.size > NeoForgeProtocol.MAX_CHANNELS) {
            throw MinecraftSerializationException(
                "NeoForge channel registration exceeds ${NeoForgeProtocol.MAX_CHANNELS} channels",
            )
        }
        val channels = literals.mapTo(linkedSetOf()) { literal ->
            try {
                Identifier(literal).also(::validateChannel)
            } catch (cause: IllegalArgumentException) {
                throw MinecraftSerializationException(
                    "Invalid NeoForge channel identifier: $literal",
                    cause,
                )
            }
        }
        return factory(channels)
    }
}

private fun validateChannel(channel: Identifier) {
    require(channel.value.length <= NeoForgeProtocol.MAX_CHANNEL_NAME_LENGTH) {
        "NeoForge channel name exceeds ${NeoForgeProtocol.MAX_CHANNEL_NAME_LENGTH} characters: $channel"
    }
}

private fun <T : NeoForgeBidirectionalPacket>
        MutableList<PacketCodecRegistration<out Packet>>.addBidirectional(
    channel: Identifier,
    codec: PacketBodyCodec<T>,
    packetClass: kotlin.reflect.KClass<T>,
) {
    CONFIGURATION_AND_PLAY.forEach { state ->
        add(
            PacketCodecRegistration.clientboundCustomPayload(
                state,
                channel,
                packetClass,
                codec,
            ),
        )
        add(
            PacketCodecRegistration.serverboundCustomPayload(
                state,
                channel,
                packetClass,
                codec,
            ),
        )
    }
}

private fun <T : com.hiczp.minecraft.protocol.model.packet.ClientboundPacket.Extension>
        MutableList<PacketCodecRegistration<out Packet>>.addClientbound(
    channel: Identifier,
    codec: PacketBodyCodec<T>,
    packetClass: kotlin.reflect.KClass<T>,
    states: List<ConnectionState> = listOf(ConnectionState.CONFIGURATION),
) {
    states.forEach { state ->
        add(
            PacketCodecRegistration.clientboundCustomPayload(
                state,
                channel,
                packetClass,
                codec,
            ),
        )
    }
}

private fun <T : com.hiczp.minecraft.protocol.model.packet.ServerboundPacket.Extension>
        MutableList<PacketCodecRegistration<out Packet>>.addServerbound(
    channel: Identifier,
    codec: PacketBodyCodec<T>,
    packetClass: kotlin.reflect.KClass<T>,
    states: List<ConnectionState> = listOf(ConnectionState.CONFIGURATION),
) {
    states.forEach { state ->
        add(
            PacketCodecRegistration.serverboundCustomPayload(
                state,
                channel,
                packetClass,
                codec,
            ),
        )
    }
}

private val CONFIGURATION_AND_PLAY = listOf(
    ConnectionState.CONFIGURATION,
    ConnectionState.PLAY,
)
