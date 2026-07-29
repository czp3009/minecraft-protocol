package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketDirection
import com.hiczp.minecraft.protocol.model.packet.PacketFraming
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

class PacketCodec<T : Packet> internal constructor(
    val key: PacketKey,
    val framing: PacketFraming,
    val packetClass: KClass<T>,
    val serializer: KSerializer<T>,
) {
    internal fun encode(
        format: MinecraftFormat,
        packet: Packet,
    ): ByteArray {
        if (packet::class != packetClass) {
            throw MinecraftSerializationException(
                "Codec for ${packetClass.simpleName} cannot encode ${packet::class.simpleName}",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return format.encodeToByteArray(serializer, packet as T)
    }

    internal fun decode(
        format: MinecraftFormat,
        payload: ByteArray,
    ): Packet = format.decodeFromByteArray(serializer, payload)
}

class PacketRegistry(
    entries: List<PacketCodec<out Packet>>,
) {
    val entries: List<PacketCodec<out Packet>> = entries.toList()

    private val byKey: Map<PacketKey, PacketCodec<out Packet>>
    private val byClass: Map<KClass<out Packet>, PacketCodec<out Packet>>

    init {
        byKey = uniqueIndex(this.entries, PacketCodec<out Packet>::key, "packet key")
        byClass = uniqueIndex(
            this.entries,
            PacketCodec<out Packet>::packetClass,
            "packet class",
        )
    }

    fun codec(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
    ): PacketCodec<out Packet>? = byKey[PacketKey(state, direction, id)]

    fun codec(packet: Packet): PacketCodec<out Packet>? = byClass[packet::class]

    fun encodePayload(
        packet: Packet,
        format: MinecraftFormat = MinecraftFormat.Default,
    ): EncodedPacketPayload {
        val codec = codec(packet)
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for ${packet::class.simpleName}",
            )
        return EncodedPacketPayload(
            codec.key,
            codec.framing,
            codec.encode(format, packet),
        )
    }

    fun decodePayload(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
        payload: ByteArray,
        format: MinecraftFormat = MinecraftFormat.Default,
    ): Packet {
        val key = PacketKey(state, direction, id)
        val codec = byKey[key]
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for $key",
            )
        return codec.decode(format, payload)
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
        format: MinecraftFormat = MinecraftFormat.Default,
    ): EncodedPacketPayload = delegate.encodePayload(packet, format)

    fun decodePayload(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
        payload: ByteArray,
        format: MinecraftFormat = MinecraftFormat.Default,
    ): Packet = delegate.decodePayload(state, direction, id, payload, format)
}

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
