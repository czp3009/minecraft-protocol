@file:OptIn(
    InternalPacketRegistryApi::class,
)

package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

data class PacketKey(
    val connectionState: ConnectionState,
    val packetDirection: PacketDirection,
    val id: Int,
) {
    init {
        require(id >= 0) { "Packet ID must be non-negative" }
    }
}

data class EncodedPacketPayload(
    val packetKey: PacketKey,
    val packetFraming: PacketFraming,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EncodedPacketPayload &&
                packetKey == other.packetKey &&
                packetFraming == other.packetFraming &&
                payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * packetKey.hashCode() + packetFraming.hashCode()) + payload.contentHashCode()
}

data class PacketPayloadEncoding(
    val packetKey: PacketKey,
    val packetFraming: PacketFraming,
)

data class PacketCodec<T : Packet>(
    val packetKey: PacketKey,
    val packetFraming: PacketFraming,
    val packetClass: KClass<T>,
    val kSerializer: KSerializer<T>?,
    val packetBodyCodec: PacketBodyCodec<T>,
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
            packetKey = packetKey.copy(id = packetId),
            packetFraming = packetFraming,
            packetClass = packetClass,
            kSerializer = kSerializer,
            packetBodyCodec = packetBodyCodec,
        )
    }

    internal fun encodeToSink(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: Packet,
        sink: Sink,
    ) {
        @Suppress("UNCHECKED_CAST")
        packetBodyCodec.encode(minecraftProtocolFormat, packet as T, sink)
    }

    internal fun decodeFromSource(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        source: Source,
        byteCount: Int,
    ): Packet = packetBodyCodec.decode(
        minecraftProtocolFormat,
        PacketRoute.TopLevel(
            packetKey.connectionState,
            packetKey.packetDirection,
            packetKey.id,
        ),
        source,
        byteCount,
    )
}

/**
 * Read-only packet table. Callers may reindex or filter [MinecraftPacketRegistry.entries] once at application startup,
 * append extension [registrations], and share the result across connections. Supplied registration lists must remain
 * stable because lookup indexes are built during construction.
 */
class PacketRegistry(
    baseEntries: List<PacketCodec<out Packet>>,
    val registrations: List<PacketCodecRegistration<out Packet>> = emptyList(),
) {
    private val registrationsByRoute = uniqueIndex(
        this.registrations,
        PacketCodecRegistration<out Packet>::packetRouteKey,
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
        if (extensionTopLevelCodecs.isEmpty()) baseEntries else baseEntries + extensionTopLevelCodecs

    private val byKey = uniqueIndex(this.entries, PacketCodec<out Packet>::packetKey, "packet key")
    private val byClass: Map<KClass<out Packet>, List<PacketCodec<out Packet>>> =
        this.entries.groupBy(PacketCodec<out Packet>::packetClass)

    init {
        val baseClasses = baseEntries.map(PacketCodec<out Packet>::packetClass).toSet()
        registrationsByClass.forEach { (packetClass, classRegistrations) ->
            require(packetClass !in baseClasses) {
                "Extension packet class collides with a base packet class: ${packetClass.simpleName}"
            }
            val phases = classRegistrations.map { packetCodecRegistration ->
                packetCodecRegistration.packetRouteKey.connectionState to packetCodecRegistration.packetRouteKey.packetDirection
            }
            require(phases.distinct().size == phases.size) {
                "Extension packet class ${packetClass.simpleName} has multiple routes in one state and direction"
            }
        }
        registrationsByRoute.keys.forEach { packetRouteKey ->
            if (
                packetRouteKey is PacketRouteKey.CustomPayload &&
                packetRouteKey.channel == BRAND_CHANNEL
            ) {
                throw IllegalArgumentException(
                    "minecraft:brand is a built-in custom payload route",
                )
            }
        }
    }

    val declaredExtensionRoutes: Set<PacketRouteKey> = registrationsByRoute.keys

    fun codec(
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        id: Int,
    ): PacketCodec<out Packet>? = byKey[PacketKey(connectionState, packetDirection, id)]

    fun codec(packet: Packet): PacketCodec<out Packet>? =
        singleClassValue(byClass[packet::class], packet::class, "packet codec")

    fun codec(
        packet: Packet,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
    ): PacketCodec<out Packet>? = byClass[packet::class]
        ?.singleOrNull { packetCodec ->
            packetCodec.packetKey.connectionState == connectionState && packetCodec.packetKey.packetDirection == packetDirection
        }

    fun registration(
        packetRouteKey: PacketRouteKey,
    ): PacketCodecRegistration<out Packet>? = registrationsByRoute[packetRouteKey]

    fun registration(
        packet: Packet,
    ): PacketCodecRegistration<out Packet>? = singleClassValue(
        registrationsByClass[packet::class],
        packet::class,
        "extension registration",
    )

    fun registration(
        packet: Packet,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
    ): PacketCodecRegistration<out Packet>? = registrationsByClass[packet::class]
        ?.singleOrNull { packetCodecRegistration ->
            packetCodecRegistration.packetRouteKey.connectionState == connectionState &&
                    packetCodecRegistration.packetRouteKey.packetDirection == packetDirection
        }

    fun encodePayload(
        packet: Packet,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): EncodedPacketPayload {
        val buffer = Buffer()
        val packetPayloadEncoding = encodePayloadToSink(packet, buffer, minecraftProtocolFormat)
        return EncodedPacketPayload(
            packetPayloadEncoding.packetKey,
            packetPayloadEncoding.packetFraming,
            buffer.readByteArray(),
        )
    }

    fun encodePayload(
        packet: Packet,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): EncodedPacketPayload {
        val buffer = Buffer()
        val packetPayloadEncoding = encodePayloadToSink(
            packet,
            connectionState,
            packetDirection,
            buffer,
            minecraftProtocolFormat,
        )
        return EncodedPacketPayload(
            packetPayloadEncoding.packetKey,
            packetPayloadEncoding.packetFraming,
            buffer.readByteArray(),
        )
    }

    fun encodePayloadToSink(
        packet: Packet,
        sink: Sink,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): PacketPayloadEncoding {
        val packetCodec = codec(packet)
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for ${packet::class.simpleName}",
            )
        packetCodec.encodeToSink(minecraftProtocolFormat, packet, sink)
        return PacketPayloadEncoding(packetCodec.packetKey, packetCodec.packetFraming)
    }

    fun encodePayloadToSink(
        packet: Packet,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        sink: Sink,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): PacketPayloadEncoding {
        val packetCodec = codec(packet, connectionState, packetDirection)
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for ${packet::class.simpleName} in $connectionState $packetDirection",
            )
        packetCodec.encodeToSink(minecraftProtocolFormat, packet, sink)
        return PacketPayloadEncoding(packetCodec.packetKey, packetCodec.packetFraming)
    }

    fun decodePayload(
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        id: Int,
        payload: ByteArray,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet {
        val buffer = Buffer()
        buffer.write(payload)
        return decodePayloadFromSource(
            connectionState,
            packetDirection,
            id,
            buffer,
            payload.size,
            minecraftProtocolFormat,
        )
    }

    fun decodePayloadFromSource(
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        id: Int,
        source: Source,
        byteCount: Int,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet {
        val packetKey = PacketKey(connectionState, packetDirection, id)
        val packetCodec = byKey[packetKey]
            ?: throw MinecraftSerializationException(
                "No packet codec is registered for $packetKey",
            )
        if (packetCodec.extensionRoute == null) {
            return packetCodec.decodeFromSource(minecraftProtocolFormat, source, byteCount)
        }
        val topLevel = PacketRoute.TopLevel(connectionState, packetDirection, id)
        val body = readBoundedPayload(source, byteCount)
        val decodedBody = body.copy()
        val packet = try {
            packetCodec.decodeFromSource(minecraftProtocolFormat, decodedBody, byteCount)
        } catch (_: UnknownExtensionPacketException) {
            return unknownPacket(topLevel, body.readByteArray())
        }
        requireExhausted(decodedBody, "Extension payload")
        return packet
    }

    fun encodeExtensionPayloadToSink(
        packet: Packet,
        sink: Sink,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ) {
        val packetCodecRegistration = registration(packet)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${packet::class.simpleName}",
            )
        packetCodecRegistration.encodeBody(minecraftProtocolFormat, packet, sink)
    }

    fun encodeExtensionPayloadToSink(
        packet: Packet,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        sink: Sink,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ) {
        val packetCodecRegistration = registration(packet, connectionState, packetDirection)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${packet::class.simpleName} in $connectionState $packetDirection",
            )
        packetCodecRegistration.encodeBody(minecraftProtocolFormat, packet, sink)
    }

    fun decodeExtensionPayloadFromSource(
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
        minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    ): Packet {
        val packetCodecRegistration = registration(packetRoute.packetRouteKey)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${packetRoute.packetRouteKey}",
            )
        val body = readBoundedPayload(source, byteCount)
        val decodedBody = body.copy()
        val packet = try {
            packetCodecRegistration.decodeBody(
                minecraftProtocolFormat,
                packetRoute,
                decodedBody,
                byteCount,
            )
        } catch (_: UnknownExtensionPacketException) {
            return unknownPacket(packetRoute, body.readByteArray())
        }
        requireExhausted(decodedBody, "Extension payload")
        return packet
    }

    fun extensionRoute(
        packet: Packet,
        outerPacketId: Int? = null,
    ): PacketRoute {
        val packetCodecRegistration = registration(packet)
            ?: throw MinecraftSerializationException(
                "No extension codec is registered for ${packet::class.simpleName}",
            )
        return packetCodecRegistration.routeForPacket(packet, outerPacketId)
    }

    fun extensionRoute(
        packet: Packet,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        outerPacketId: Int? = null,
    ): PacketRoute {
        val packetCodecRegistration = registration(packet, connectionState, packetDirection)
            ?: throw MinecraftSerializationException(
                "No extension route is registered for ${packet::class.simpleName} in $connectionState $packetDirection",
            )
        return packetCodecRegistration.routeForPacket(packet, outerPacketId)
    }
}

val MinecraftPacketRegistry: PacketRegistry = PacketRegistry(
    GeneratedPacketDefinitions.entries.map { packetDefinition ->
        packetDefinition.toPacketCodec()
    },
)

private fun <T : Packet> PacketDefinition<T>.toPacketCodec(): PacketCodec<T> =
    PacketCodec(
        packetKey = PacketKey(connectionState, packetDirection, id),
        packetFraming = packetFraming,
        packetClass = packetClass,
        kSerializer = kSerializer,
        packetBodyCodec = KotlinxPacketBodyCodec(kSerializer),
    )

private fun PacketCodecRegistration<out Packet>.toTopLevelCodec(): PacketCodec<out Packet>? {
    val topLevel = packetRouteKey as? PacketRouteKey.TopLevel ?: return null
    return toTopLevelCodec(topLevel)
}

private fun <T : Packet> PacketCodecRegistration<T>.toTopLevelCodec(
    topLevel: PacketRouteKey.TopLevel,
): PacketCodec<T> = PacketCodec(
    packetKey = PacketKey(
        topLevel.connectionState,
        topLevel.packetDirection,
        topLevel.packetId,
    ),
    packetFraming = PacketFraming.NORMAL,
    packetClass = packetClass,
    kSerializer = null,
    packetBodyCodec = packetBodyCodec,
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
    packetRoute: PacketRoute,
    data: ByteArray,
): UnknownPacket = when (packetRoute.packetDirection) {
    PacketDirection.CLIENTBOUND ->
        UnknownPacket.Clientbound(packetRoute, ByteString(data))

    PacketDirection.SERVERBOUND ->
        UnknownPacket.Serverbound(packetRoute, ByteString(data))
}

private val BRAND_CHANNEL = Identifier(
    "minecraft:brand",
)
