@file:OptIn(
    InternalPacketRegistryApi::class,
)

package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

data class PacketKey(
    val state: ConnectionState,
    val direction: PacketDirection,
    val id: Int,
)

data class EncodedPacketPayload(
    val key: PacketKey,
    val framing: PacketFraming,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EncodedPacketPayload &&
                key == other.key &&
                framing == other.framing &&
                payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * key.hashCode() + framing.hashCode()) + payload.contentHashCode()
}

data class PacketPayloadEncoding(
    val key: PacketKey,
    val framing: PacketFraming,
)

class PacketCodec<T : Packet> internal constructor(
    val key: PacketKey,
    val framing: PacketFraming,
    val packetClass: KClass<T>,
    val serializer: KSerializer<T>,
) {
    internal fun encodeToSink(
        format: MinecraftProtocolFormat,
        packet: Packet,
        sink: Sink,
    ) {
        if (packet::class != packetClass) {
            throw MinecraftSerializationException(
                "Codec for ${packetClass.simpleName} cannot encode ${packet::class.simpleName}",
            )
        }
        @Suppress("UNCHECKED_CAST")
        format.encodeToSink(serializer, packet as T, sink)
    }

    internal fun decodeFromSource(
        format: MinecraftProtocolFormat,
        source: Source,
        byteCount: Int,
    ): Packet = format.decodeFromSource(serializer, source, byteCount)
}

class PacketRegistry(
    entries: List<PacketCodec<out Packet>>,
) {
    val entries: List<PacketCodec<out Packet>> = entries.toList()

    private val byKey = uniqueIndex(this.entries, PacketCodec<out Packet>::key, "packet key")
    private val byClass = uniqueIndex(
        this.entries,
        PacketCodec<out Packet>::packetClass,
        "packet class",
    )

    fun codec(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
    ): PacketCodec<out Packet>? = byKey[PacketKey(state, direction, id)]

    fun codec(packet: Packet): PacketCodec<out Packet>? = byClass[packet::class]

    fun encodePayload(
        packet: Packet,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): EncodedPacketPayload {
        val buffer = Buffer()
        val encoding = encodePayloadToSink(packet, buffer, format)
        return EncodedPacketPayload(
            encoding.key,
            encoding.framing,
            buffer.readByteArray(),
        )
    }

    fun encodePayloadToSink(
        packet: Packet,
        sink: Sink,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): PacketPayloadEncoding {
        val codec = codec(packet)
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for ${packet::class.simpleName}",
            )
        codec.encodeToSink(format, packet, sink)
        return PacketPayloadEncoding(codec.key, codec.framing)
    }

    fun decodePayload(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
        payload: ByteArray,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet {
        val buffer = Buffer()
        buffer.write(payload)
        return decodePayloadFromSource(
            state,
            direction,
            id,
            buffer,
            payload.size,
            format,
        )
    }

    fun decodePayloadFromSource(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
        source: Source,
        byteCount: Int,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet {
        val key = PacketKey(state, direction, id)
        val codec = byKey[key]
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for $key",
            )
        return codec.decodeFromSource(format, source, byteCount)
    }
}

object MinecraftPacketRegistry {
    private val delegate: PacketRegistry = PacketRegistry(
        generatedPacketCodecs(),
    )

    val entries: List<PacketCodec<out Packet>>
        get() = delegate.entries

    fun codec(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
    ): PacketCodec<out Packet>? = delegate.codec(state, direction, id)

    fun codec(packet: Packet): PacketCodec<out Packet>? = delegate.codec(packet)

    fun encodePayload(
        packet: Packet,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): EncodedPacketPayload = delegate.encodePayload(packet, format)

    fun encodePayloadToSink(
        packet: Packet,
        sink: Sink,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): PacketPayloadEncoding = delegate.encodePayloadToSink(packet, sink, format)

    fun decodePayload(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
        payload: ByteArray,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet = delegate.decodePayload(state, direction, id, payload, format)

    fun decodePayloadFromSource(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
        source: Source,
        byteCount: Int,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet = delegate.decodePayloadFromSource(
        state,
        direction,
        id,
        source,
        byteCount,
        format,
    )
}

private fun generatedPacketCodecs(): List<PacketCodec<out Packet>> =
    GeneratedPacketDefinitions.entries.map { definition ->
        definition.toPacketCodec()
    }

private fun <T : Packet> PacketDefinition<T>.toPacketCodec(): PacketCodec<T> =
    PacketCodec(
        key = PacketKey(state, direction, id),
        framing = framing,
        packetClass = packetClass,
        serializer = serializer,
    )

private fun <K, V> uniqueIndex(
    values: List<V>,
    key: (V) -> K,
    kind: String,
): Map<K, V> {
    val result = LinkedHashMap<K, V>(values.size)
    for (value in values) {
        val previous = result.put(key(value), value)
        require(previous == null) { "Duplicate $kind: ${key(value)}" }
    }
    return result
}
