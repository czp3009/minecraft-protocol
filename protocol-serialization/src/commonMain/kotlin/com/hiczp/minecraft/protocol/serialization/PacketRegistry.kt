@file:OptIn(
    InternalPacketRegistryApi::class,
)

package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
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
) {
    init {
        require(id >= 0) { "Packet ID must be non-negative" }
    }
}

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
    val serializer: KSerializer<T>?,
    private val bodyCodec: PacketBodyCodec<T>,
    val extensionRoute: PacketRouteKey? = null,
) {
    /**
     * Reuses this immutable codec at another numeric ID in the same state and
     * direction. This supports startup-time protocol tables whose registered
     * vanilla packet IDs differ from the repository base.
     */
    fun withPacketId(packetId: Int): PacketCodec<T> {
        require(extensionRoute == null) {
            "Extension packet IDs are declared by PacketCodecRegistration"
        }
        return PacketCodec(
            key = key.copy(id = packetId),
            framing = framing,
            packetClass = packetClass,
            serializer = serializer,
            bodyCodec = bodyCodec,
        )
    }

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
        bodyCodec.encode(format, packet as T, sink)
    }

    internal fun decodeFromSource(
        format: MinecraftProtocolFormat,
        source: Source,
        byteCount: Int,
    ): Packet = bodyCodec.decode(
        format,
        PacketRoute.TopLevel(
            key.state,
            key.direction,
            key.id,
        ),
        source,
        byteCount,
    )
}

/**
 * Immutable packet table. Callers may reindex or filter
 * [MinecraftPacketRegistry.entries] once at application startup, append
 * extension [registrations], and share the result across connections.
 */
class PacketRegistry(
    baseEntries: List<PacketCodec<out Packet>>,
    registrations: List<PacketCodecRegistration<out Packet>> = emptyList(),
) {
    val registrations: List<PacketCodecRegistration<out Packet>> =
        registrations.toList()

    private val registrationsByRoute = uniqueIndex(
        this.registrations,
        PacketCodecRegistration<out Packet>::route,
        "extension route",
    )
    private val registrationsByClass: Map<
            KClass<out Packet>,
            List<PacketCodecRegistration<out Packet>>,
            > = this.registrations.groupBy(
        PacketCodecRegistration<out Packet>::packetClass,
    )
    private val extensionTopLevelCodecs: List<PacketCodec<out Packet>> =
        this.registrations.mapNotNull(PacketCodecRegistration<out Packet>::toTopLevelCodec)

    val entries: List<PacketCodec<out Packet>> =
        baseEntries.toList() + extensionTopLevelCodecs

    private val byKey = uniqueIndex(this.entries, PacketCodec<out Packet>::key, "packet key")
    private val byClass: Map<KClass<out Packet>, List<PacketCodec<out Packet>>> =
        this.entries.groupBy(PacketCodec<out Packet>::packetClass)

    init {
        val baseClasses = baseEntries.map(PacketCodec<out Packet>::packetClass).toSet()
        registrationsByClass.forEach { (packetClass, classRegistrations) ->
            require(packetClass !in baseClasses) {
                "Extension packet class collides with a base packet class: ${packetClass.simpleName}"
            }
            val phases = classRegistrations.map { registration ->
                registration.route.state to registration.route.direction
            }
            require(phases.distinct().size == phases.size) {
                "Extension packet class ${packetClass.simpleName} has multiple routes in one state and direction"
            }
        }
        registrationsByRoute.keys.forEach { route ->
            if (
                route is PacketRouteKey.CustomPayload &&
                route.channel == BRAND_CHANNEL
            ) {
                throw IllegalArgumentException(
                    "minecraft:brand is a built-in custom payload route",
                )
            }
        }
    }

    val declaredExtensionRoutes: Set<PacketRouteKey> =
        registrationsByRoute.keys.toSet()

    fun codec(
        state: ConnectionState,
        direction: PacketDirection,
        id: Int,
    ): PacketCodec<out Packet>? = byKey[PacketKey(state, direction, id)]

    fun codec(packet: Packet): PacketCodec<out Packet>? =
        singleClassValue(byClass[packet::class], packet::class, "packet codec")

    fun codec(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
    ): PacketCodec<out Packet>? = byClass[packet::class]
        ?.singleOrNull { codec ->
            codec.key.state == state && codec.key.direction == direction
        }

    fun registration(
        route: PacketRouteKey,
    ): PacketCodecRegistration<out Packet>? = registrationsByRoute[route]

    fun registration(
        packet: Packet,
    ): PacketCodecRegistration<out Packet>? = singleClassValue(
        registrationsByClass[packet::class],
        packet::class,
        "extension registration",
    )

    fun registration(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
    ): PacketCodecRegistration<out Packet>? = registrationsByClass[packet::class]
        ?.singleOrNull { registration ->
            registration.route.state == state &&
                    registration.route.direction == direction
        }

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

    fun encodePayload(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): EncodedPacketPayload {
        val buffer = Buffer()
        val encoding = encodePayloadToSink(
            packet,
            state,
            direction,
            buffer,
            format,
        )
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

    fun encodePayloadToSink(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
        sink: Sink,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): PacketPayloadEncoding {
        val codec = codec(packet, state, direction)
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for ${packet::class.simpleName} in $state $direction",
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
        if (codec.extensionRoute == null) {
            return codec.decodeFromSource(format, source, byteCount)
        }
        val route = PacketRoute.TopLevel(state, direction, id)
        val body = readBoundedPayload(source, byteCount)
        val original = body.peek().readByteArray()
        val packet = try {
            codec.decodeFromSource(format, body, byteCount)
        } catch (_: UnknownExtensionPacketException) {
            return unknownPacket(route, original)
        }
        requireExhausted(body, "Extension payload")
        return packet
    }

    fun encodeExtensionPayloadToSink(
        packet: Packet,
        sink: Sink,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ) {
        val registration = registration(packet)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${packet::class.simpleName}",
            )
        registration.encodeBody(format, packet, sink)
    }

    fun encodeExtensionPayloadToSink(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
        sink: Sink,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ) {
        val registration = registration(packet, state, direction)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${packet::class.simpleName} in $state $direction",
            )
        registration.encodeBody(format, packet, sink)
    }

    fun decodeExtensionPayloadFromSource(
        route: PacketRoute,
        source: Source,
        byteCount: Int,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet {
        val registration = registration(route.key)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${route.key}",
            )
        val body = readBoundedPayload(source, byteCount)
        val original = body.peek().readByteArray()
        val packet = try {
            registration.decodeBody(
                format,
                route,
                body,
                byteCount,
            )
        } catch (_: UnknownExtensionPacketException) {
            return unknownPacket(route, original)
        }
        requireExhausted(body, "Extension payload")
        return packet
    }

    fun extensionRoute(
        packet: Packet,
        outerPacketId: Int? = null,
    ): PacketRoute {
        val registration = registration(packet)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${packet::class.simpleName}",
            )
        return registration.routeForPacket(packet, outerPacketId)
    }

    fun extensionRoute(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
        outerPacketId: Int? = null,
    ): PacketRoute {
        val registration = registration(packet, state, direction)
            ?: throw MinecraftSerializationException(
                "No extension route is registered for ${packet::class.simpleName} in $state $direction",
            )
        return registration.routeForPacket(packet, outerPacketId)
    }
}

object MinecraftPacketRegistry {
    private val delegate: PacketRegistry = PacketRegistry(
        generatedPacketCodecs(),
    )

    val entries: List<PacketCodec<out Packet>>
        get() = delegate.entries

    fun compose(
        registrations: List<PacketCodecRegistration<out Packet>>,
    ): PacketRegistry = PacketRegistry(
        generatedPacketCodecs(),
        registrations,
    )

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

    fun encodePayload(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): EncodedPacketPayload = delegate.encodePayload(
        packet,
        state,
        direction,
        format,
    )

    fun encodePayloadToSink(
        packet: Packet,
        sink: Sink,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): PacketPayloadEncoding = delegate.encodePayloadToSink(packet, sink, format)

    fun encodePayloadToSink(
        packet: Packet,
        state: ConnectionState,
        direction: PacketDirection,
        sink: Sink,
        format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): PacketPayloadEncoding = delegate.encodePayloadToSink(
        packet,
        state,
        direction,
        sink,
        format,
    )

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
        bodyCodec = KotlinxPacketBodyCodec(serializer),
    )

private fun PacketCodecRegistration<out Packet>.toTopLevelCodec(): PacketCodec<out Packet>? {
    val topLevel = route as? PacketRouteKey.TopLevel ?: return null
    return toTopLevelCodec(topLevel)
}

private fun <T : Packet> PacketCodecRegistration<T>.toTopLevelCodec(
    topLevel: PacketRouteKey.TopLevel,
): PacketCodec<T> = PacketCodec(
    key = PacketKey(
        topLevel.state,
        topLevel.direction,
        topLevel.packetId,
    ),
    framing = PacketFraming.NORMAL,
    packetClass = packetClass,
    serializer = null,
    bodyCodec = codec,
    extensionRoute = topLevel,
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

private fun <T> singleClassValue(
    values: List<T>?,
    packetClass: KClass<out Packet>,
    kind: String,
): T? {
    if (values == null) return null
    if (values.size != 1) {
        throw MinecraftSerializationException(
            "${packetClass.simpleName} has ${values.size} $kind declarations; select by state and direction",
        )
    }
    return values.single()
}

private fun readBoundedPayload(
    source: Source,
    byteCount: Int,
): Buffer {
    require(byteCount >= 0) { "Packet byte count must be non-negative" }
    val body = Buffer()
    var remaining = byteCount.toLong()
    while (remaining > 0) {
        val read = source.readAtMostTo(body, remaining)
        if (read < 0) {
            throw MinecraftSerializationException(
                "Extension payload ended with $remaining byte(s) missing",
            )
        }
        remaining -= read
    }
    return body
}

private fun requireExhausted(
    body: Buffer,
    description: String,
) {
    if (!body.exhausted()) {
        throw MinecraftSerializationException(
            "$description has ${body.size} unread byte(s)",
        )
    }
}

private fun unknownPacket(
    route: PacketRoute,
    data: ByteArray,
): UnknownPacket = when (route.direction) {
    PacketDirection.CLIENTBOUND ->
        UnknownPacket.Clientbound(route, ByteString(data))

    PacketDirection.SERVERBOUND ->
        UnknownPacket.Serverbound(route, ByteString(data))
}

private val BRAND_CHANNEL = com.hiczp.minecraft.protocol.model.type.Identifier(
    "minecraft:brand",
)
