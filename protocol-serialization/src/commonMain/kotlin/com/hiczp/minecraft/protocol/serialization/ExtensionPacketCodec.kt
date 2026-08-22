package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/** Bounded physical codec for the body beneath an extension route header. */
interface PacketBodyCodec<T : Packet> {
    fun encode(
        format: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    )

    fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): T
}

/**
 * Signals that a recognized extension route contains an intentionally unknown
 * nested packet. The registry preserves the complete route body as a
 * direction-correct [UnknownPacket]; every other decoding failure propagates.
 */
class UnknownExtensionPacketException : Exception()

/** Adapts an ordinary kotlinx serializer to an extension packet body. */
class KotlinxPacketBodyCodec<T : Packet>(
    private val serializer: KSerializer<T>,
) : PacketBodyCodec<T> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    ) {
        format.encodeToSink(serializer, packet, sink)
    }

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): T = format.decodeFromSource(serializer, source, byteCount)
}

/**
 * Uses a kotlinx serializer for the wire body while mapping route metadata to
 * and from the application packet type.
 */
class MappedKotlinxPacketBodyCodec<T : Packet, Body>(
    private val serializer: KSerializer<Body>,
    private val encodeBody: (T) -> Body,
    private val decodePacket: (PacketRoute, Body) -> T,
) : PacketBodyCodec<T> {
    override fun encode(
        format: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    ) {
        format.encodeToSink(serializer, encodeBody(packet), sink)
    }

    override fun decode(
        format: MinecraftProtocolFormat,
        route: PacketRoute,
        source: Source,
        byteCount: Int,
    ): T = decodePacket(
        route,
        format.decodeFromSource(serializer, source, byteCount),
    )
}

/**
 * Immutable declaration of one application packet route. A declaration is
 * snapshotted into a connection before its pumps start and can only be
 * activated or deactivated as a whole route afterward.
 */
class PacketCodecRegistration<T : Packet> private constructor(
    val route: PacketRouteKey,
    val packetClass: KClass<T>,
    val codec: PacketBodyCodec<T>,
    private val loginRoute: ((T) -> PacketRoute.LoginQuery)?,
) {
    internal fun routeForPacket(
        packet: Packet,
        outerPacketId: Int? = null,
    ): PacketRoute {
        @Suppress("UNCHECKED_CAST")
        val typedPacket = packet as T
        return when (val key = route) {
            is PacketRouteKey.TopLevel -> PacketRoute.TopLevel(
                key.state,
                key.direction,
                key.packetId,
            )

            is PacketRouteKey.CustomPayload -> PacketRoute.CustomPayload(
                key.state,
                key.direction,
                outerPacketId ?: throw MinecraftSerializationException(
                    "Encoding ${packetClass.simpleName} requires its outer custom-payload packet ID",
                ),
                key.channel,
            )

            is PacketRouteKey.LoginQuery -> {
                val actual = loginRoute?.invoke(typedPacket)
                    ?: throw MinecraftSerializationException(
                        "Encoding ${packetClass.simpleName} requires a Login query route selector",
                    )
                if (actual.key != key) {
                    throw MinecraftSerializationException(
                        "${packetClass.simpleName} selected ${actual.key}, but its declared route is $key",
                    )
                }
                actual
            }
        }
    }

    internal fun encodeBody(
        format: MinecraftProtocolFormat,
        packet: Packet,
        sink: Sink,
    ) {
        @Suppress("UNCHECKED_CAST")
        codec.encode(format, packet as T, sink)
    }

    internal fun decodeBody(
        format: MinecraftProtocolFormat,
        actualRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): Packet = codec.decode(format, actualRoute, source, byteCount)

    companion object {
        fun <T : ClientboundPacket.Extension> clientboundTopLevel(
            state: ConnectionState,
            packetId: Int,
            packetClass: KClass<T>,
            codec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.TopLevel(
                state,
                PacketDirection.CLIENTBOUND,
                packetId,
            ),
            packetClass,
            codec,
            loginRoute = null,
        )

        fun <T : ServerboundPacket.Extension> serverboundTopLevel(
            state: ConnectionState,
            packetId: Int,
            packetClass: KClass<T>,
            codec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.TopLevel(
                state,
                PacketDirection.SERVERBOUND,
                packetId,
            ),
            packetClass,
            codec,
            loginRoute = null,
        )

        fun <T : ClientboundPacket.Extension> clientboundCustomPayload(
            state: ConnectionState,
            channel: Identifier,
            packetClass: KClass<T>,
            codec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.CustomPayload(
                state,
                PacketDirection.CLIENTBOUND,
                channel,
            ),
            packetClass,
            codec,
            loginRoute = null,
        )

        fun <T : ServerboundPacket.Extension> serverboundCustomPayload(
            state: ConnectionState,
            channel: Identifier,
            packetClass: KClass<T>,
            codec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.CustomPayload(
                state,
                PacketDirection.SERVERBOUND,
                channel,
            ),
            packetClass,
            codec,
            loginRoute = null,
        )

        fun <T : ClientboundPacket.Extension> clientboundLoginQuery(
            channel: Identifier,
            packetClass: KClass<T>,
            codec: PacketBodyCodec<T>,
            route: (T) -> PacketRoute.LoginQuery,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.LoginQuery(
                PacketDirection.CLIENTBOUND,
                channel,
            ),
            packetClass,
            codec,
            loginRoute = route,
        )

        fun <T : ServerboundPacket.Extension> serverboundLoginQuery(
            channel: Identifier,
            packetClass: KClass<T>,
            codec: PacketBodyCodec<T>,
            route: (T) -> PacketRoute.LoginQuery,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.LoginQuery(
                PacketDirection.SERVERBOUND,
                channel,
            ),
            packetClass,
            codec,
            loginRoute = route,
        )
    }
}
