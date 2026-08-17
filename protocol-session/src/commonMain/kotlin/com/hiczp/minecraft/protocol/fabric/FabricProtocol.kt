@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.fabric

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.RemainingBytes
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.serialization.*
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

object FabricProtocol {
    const val COMMON_PACKET_VERSION: Int = 1
    const val MAX_COMMON_VERSIONS: Int = FabricProtocolLimits.MAX_COMMON_VERSIONS
    const val MAX_PROTOCOL_NAME_LENGTH: Int = FabricProtocolLimits.MAX_PROTOCOL_NAME_LENGTH
    const val MAX_CHANNELS: Int = FabricProtocolLimits.MAX_CHANNELS
    const val MAX_CHANNEL_NAME_LENGTH: Int = FabricProtocolLimits.MAX_CHANNEL_NAME_LENGTH

    val packetCodecs: List<PacketCodecRegistration<out Packet>> = buildList {
        addBidirectional(
            FabricChannels.CommonVersion,
            KotlinxPacketBodyCodec(FabricCommonVersionPacket.serializer()),
            FabricCommonVersionPacket::class,
        )
        addBidirectional(
            FabricChannels.CommonRegister,
            KotlinxPacketBodyCodec(FabricCommonRegisterPacket.serializer()),
            FabricCommonRegisterPacket::class,
        )
        addBidirectional(
            FabricChannels.Register,
            FabricRegistrationCodec(::FabricRegisterChannelsPacket),
            FabricRegisterChannelsPacket::class,
        )
        addBidirectional(
            FabricChannels.Unregister,
            FabricRegistrationCodec(::FabricUnregisterChannelsPacket),
            FabricUnregisterChannelsPacket::class,
        )
        addBidirectional(
            FabricChannels.Split,
            KotlinxPacketBodyCodec(FabricSplitPacket.serializer()),
            FabricSplitPacket::class,
        )
        add(
            PacketCodecRegistration.clientboundCustomPayload(
                ConnectionState.CONFIGURATION,
                FabricChannels.RegistrySync,
                FabricRegistrySyncPacket::class,
                FabricRegistrySyncBodyCodec,
            ),
        )
        add(
            PacketCodecRegistration.serverboundCustomPayload(
                ConnectionState.CONFIGURATION,
                FabricChannels.RegistrySyncComplete,
                FabricRegistrySyncCompletePacket::class,
                KotlinxPacketBodyCodec(
                    FabricRegistrySyncCompletePacket.serializer(),
                ),
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

private fun <T : FabricBidirectionalPacket> MutableList<PacketCodecRegistration<out Packet>>.addBidirectional(
    channel: Identifier,
    codec: PacketBodyCodec<T>,
    packetClass: kotlin.reflect.KClass<T>,
) {
    listOf(ConnectionState.CONFIGURATION, ConnectionState.PLAY).forEach { state ->
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

@Serializable
private data class RemainingBody(
    @RemainingBytes
    val data: ByteString,
)

private class FabricRegistrationCodec<T : FabricChannelRegistrationPacket>(
    private val factory: (List<Identifier>) -> T,
) : PacketBodyCodec<T> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    ) {
        require(packet.channels.size <= FabricProtocol.MAX_CHANNELS) {
            "Fabric registration has too many channels"
        }
        packet.channels.forEach(::validateChannelName)
        val bytes = packet.channels
            .joinToString("\u0000", transform = Identifier::value)
            .encodeToByteArray()
        format.encodeToSink(
            RemainingBody.serializer(),
            RemainingBody(ByteString(bytes)),
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
            RemainingBody.serializer(),
            source,
            byteCount,
        ).data.toByteArray()
        if (bytes.isEmpty()) return factory(emptyList())
        if (bytes.any { byte -> byte.toInt() and 0xFF > 0x7F }) {
            throw MinecraftSerializationException(
                "Fabric channel registration is not US-ASCII",
            )
        }
        val literals = bytes.decodeToString().split('\u0000')
        if (literals.size > FabricProtocol.MAX_CHANNELS) {
            throw MinecraftSerializationException(
                "Fabric channel registration exceeds ${FabricProtocol.MAX_CHANNELS} channels",
            )
        }
        return factory(
            literals.map { literal ->
                if (literal.isEmpty()) {
                    throw MinecraftSerializationException(
                        "Fabric channel registration contains an empty identifier",
                    )
                }
                try {
                    Identifier(literal).also(::validateChannelName)
                } catch (cause: IllegalArgumentException) {
                    throw MinecraftSerializationException(
                        "Invalid Fabric channel identifier: $literal",
                        cause,
                    )
                }
            },
        )
    }
}

private fun validateChannelName(channel: Identifier) {
    require(channel.value.length <= FabricProtocol.MAX_CHANNEL_NAME_LENGTH) {
        "Fabric channel name exceeds ${FabricProtocol.MAX_CHANNEL_NAME_LENGTH} characters: $channel"
    }
}

private object FabricRegistrySyncBodyCodec : PacketBodyCodec<FabricRegistrySyncPacket> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: FabricRegistrySyncPacket,
        sink: Sink,
    ) = format.encodeToSink(
        FabricRegistrySyncSerializer,
        packet,
        sink,
    )

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): FabricRegistrySyncPacket = format.decodeFromSource(
        FabricRegistrySyncSerializer,
        source,
        byteCount,
    )
}

private object FabricRegistrySyncSerializer : KSerializer<FabricRegistrySyncPacket> {
    override val descriptor = buildClassSerialDescriptor(
        "com.hiczp.minecraft.protocol.fabric.FabricRegistrySyncPacket",
    ) {
        element<Int>("varInt", annotations = listOf(VarInt()))
        element<String>("string", annotations = listOf(MaxLength(32_767)))
        element<Byte>("byte")
    }

    override fun serialize(
        encoder: Encoder,
        value: FabricRegistrySyncPacket,
    ) {
        val registriesByNamespace = linkedMapOf<String, MutableList<RemoteRegistry>>()
        value.snapshot.registries.values.forEach { registry ->
            registriesByNamespace.getOrPut(registry.id.namespace, ::mutableListOf)
                .add(registry)
        }
        val output = encoder.beginStructure(descriptor)
        output.encodeVarInt(registriesByNamespace.size)
        registriesByNamespace.forEach { (namespace, registries) ->
            output.encodeString(optimizeNamespace(namespace))
            output.encodeVarInt(registries.size)
            registries.forEach { registry ->
                require(registry.entries.isNotEmpty()) {
                    "Fabric cannot encode an empty synchronized registry ${registry.id}"
                }
                output.encodeString(registry.id.path)
                output.encodeByte(
                    if (registry.id in value.optionalRegistries) 1 else 0,
                )
                val entriesByNamespace = linkedMapOf<String, MutableList<RemoteRegistryEntry>>()
                registry.entries.forEach { entry ->
                    entriesByNamespace.getOrPut(
                        entry.id.namespace,
                        ::mutableListOf,
                    ).add(entry)
                }
                output.encodeVarInt(entriesByNamespace.size)
                var lastBulkLastRawId = 0
                entriesByNamespace.forEach { (entryNamespace, entries) ->
                    output.encodeString(optimizeNamespace(entryNamespace))
                    val bulks = consecutiveBulks(entries.sortedBy(RemoteRegistryEntry::rawId))
                    output.encodeVarInt(bulks.size)
                    bulks.forEach { bulk ->
                        output.encodeVarInt(bulk.first().rawId - lastBulkLastRawId)
                        output.encodeVarInt(bulk.size)
                        bulk.forEach { entry ->
                            output.encodeString(entry.id.path)
                            lastBulkLastRawId = entry.rawId
                        }
                    }
                }
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): FabricRegistrySyncPacket {
        val input = decoder.beginStructure(descriptor)
        val registryNamespaceGroups = input.decodeCount("registry namespace groups")
        val registries = mutableListOf<RemoteRegistry>()
        val optionalRegistries = linkedSetOf<Identifier>()
        repeat(registryNamespaceGroups) {
            val registryNamespace = unoptimizeNamespace(input.decodeString())
            val registriesInNamespace = input.decodeCount("registries in namespace $registryNamespace")
            repeat(registriesInNamespace) {
                val registryId = identifier(
                    registryNamespace,
                    input.decodeString(),
                    "registry",
                )
                val attributes = input.decodeByte().toInt() and 0xFF
                if (attributes and 1 != 0) optionalRegistries += registryId
                val entryNamespaceGroups = input.decodeCount(
                    "entry namespace groups in $registryId",
                )
                val entries = mutableListOf<RemoteRegistryEntry>()
                var lastBulkLastRawId = 0
                repeat(entryNamespaceGroups) {
                    val entryNamespace = unoptimizeNamespace(input.decodeString())
                    val rawIdBulks = input.decodeCount(
                        "raw-ID bulks in $registryId",
                    )
                    repeat(rawIdBulks) {
                        val startDifference = input.decodeVarInt()
                        val bulkSize = input.decodeCount(
                            "entries in a raw-ID bulk",
                            allowZero = false,
                        )
                        var currentRawId = lastBulkLastRawId.toLong() + startDifference - 1
                        repeat(bulkSize) {
                            currentRawId++
                            if (currentRawId !in 0..Int.MAX_VALUE.toLong()) {
                                throw MinecraftSerializationException(
                                    "Fabric registry $registryId contains an invalid raw ID $currentRawId",
                                )
                            }
                            entries += RemoteRegistryEntry(
                                identifier(
                                    entryNamespace,
                                    input.decodeString(),
                                    "registry entry",
                                ),
                                currentRawId.toInt(),
                            )
                        }
                        lastBulkLastRawId = currentRawId.toInt()
                    }
                }
                try {
                    registries += RemoteRegistry(registryId, entries)
                } catch (cause: IllegalArgumentException) {
                    throw MinecraftSerializationException(
                        "Invalid Fabric registry mapping for $registryId",
                        cause,
                    )
                }
            }
        }
        input.endStructure(descriptor)
        val snapshot = try {
            RemoteRegistrySnapshot(registries)
        } catch (cause: IllegalArgumentException) {
            throw MinecraftSerializationException(
                "Invalid Fabric registry snapshot",
                cause,
            )
        }
        return FabricRegistrySyncPacket(snapshot, optionalRegistries)
    }

    private fun kotlinx.serialization.encoding.CompositeDecoder.decodeCount(
        description: String,
        allowZero: Boolean = true,
    ): Int {
        val count = decodeVarInt()
        if (count < if (allowZero) 0 else 1) {
            throw MinecraftSerializationException(
                "Invalid Fabric $description count: $count",
            )
        }
        return count
    }

    private fun kotlinx.serialization.encoding.CompositeEncoder.encodeVarInt(value: Int) =
        encodeIntElement(descriptor, VAR_INT_INDEX, value)

    private fun kotlinx.serialization.encoding.CompositeDecoder.decodeVarInt(): Int =
        decodeIntElement(descriptor, VAR_INT_INDEX)

    private fun kotlinx.serialization.encoding.CompositeEncoder.encodeString(value: String) =
        encodeStringElement(descriptor, STRING_INDEX, value)

    private fun kotlinx.serialization.encoding.CompositeDecoder.decodeString(): String =
        decodeStringElement(descriptor, STRING_INDEX)

    private fun kotlinx.serialization.encoding.CompositeEncoder.encodeByte(value: Int) =
        encodeByteElement(descriptor, BYTE_INDEX, value.toByte())

    private fun kotlinx.serialization.encoding.CompositeDecoder.decodeByte(): Byte =
        decodeByteElement(descriptor, BYTE_INDEX)

    private const val VAR_INT_INDEX = 0
    private const val STRING_INDEX = 1
    private const val BYTE_INDEX = 2
}

private fun consecutiveBulks(
    entries: List<RemoteRegistryEntry>,
): List<List<RemoteRegistryEntry>> {
    if (entries.isEmpty()) return emptyList()
    val bulks = mutableListOf<MutableList<RemoteRegistryEntry>>()
    entries.forEach { entry ->
        val current = bulks.lastOrNull()
        if (current == null || current.last().rawId + 1 != entry.rawId) {
            bulks += mutableListOf(entry)
        } else {
            current += entry
        }
    }
    return bulks
}

private fun optimizeNamespace(namespace: String): String =
    namespace.takeUnless { it == "minecraft" }.orEmpty()

private fun unoptimizeNamespace(namespace: String): String =
    namespace.ifEmpty { "minecraft" }

private fun identifier(
    namespace: String,
    path: String,
    description: String,
): Identifier = try {
    Identifier("$namespace:$path")
} catch (cause: IllegalArgumentException) {
    throw MinecraftSerializationException(
        "Invalid Fabric $description identifier: $namespace:$path",
        cause,
    )
}
