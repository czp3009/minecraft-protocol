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
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    )

    fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
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
    private val kSerializer: KSerializer<T>,
) : PacketBodyCodec<T> {
    override fun encode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    ) {
        minecraftProtocolFormat.encodeToSink(kSerializer, packet, sink)
    }

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): T = minecraftProtocolFormat.decodeFromSource(kSerializer, source, byteCount)
}

/**
 * Uses a kotlinx serializer for the wire body while mapping route metadata to
 * and from the application packet type.
 */
class MappedKotlinxPacketBodyCodec<T : Packet, Body>(
    private val kSerializer: KSerializer<Body>,
    private val encodeBody: (T) -> Body,
    private val decodePacket: (PacketRoute, Body) -> T,
) : PacketBodyCodec<T> {
    override fun encode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: T,
        sink: Sink,
    ) {
        minecraftProtocolFormat.encodeToSink(kSerializer, encodeBody(packet), sink)
    }

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): T = decodePacket(
        packetRoute,
        minecraftProtocolFormat.decodeFromSource(kSerializer, source, byteCount),
    )
}

/** Per-exchange values for a Login query whose direction and channel come from its registration. */
data class LoginQueryRouteValues(
    val transactionId: Int,
    val hasPayload: Boolean = true,
)

/**
 * Immutable declaration of one application packet route. A declaration is
 * snapshotted into a connection before its pumps start and can only be
 * activated or deactivated as a whole route afterward.
 */
class PacketCodecRegistration<T : Packet> private constructor(
    val packetRouteKey: PacketRouteKey,
    val packetClass: KClass<T>,
    val packetBodyCodec: PacketBodyCodec<T>,
    private val loginRouteValues: ((T) -> LoginQueryRouteValues)?,
) {
    internal fun routeForPacket(
        packet: Packet,
        outerPacketId: Int? = null,
    ): PacketRoute {
        @Suppress("UNCHECKED_CAST")
        val typedPacket = packet as T
        return when (val packetRouteKey = packetRouteKey) {
            is PacketRouteKey.TopLevel -> PacketRoute.TopLevel(
                packetRouteKey.connectionState,
                packetRouteKey.packetDirection,
                packetRouteKey.packetId,
            )

            is PacketRouteKey.CustomPayload -> PacketRoute.CustomPayload(
                packetRouteKey.connectionState,
                packetRouteKey.packetDirection,
                outerPacketId ?: throw MinecraftSerializationException(
                    "Encoding ${packetClass.simpleName} requires its outer custom-payload packet ID",
                ),
                packetRouteKey.channel,
            )

            is PacketRouteKey.LoginQuery -> {
                val loginQueryRouteValues = loginRouteValues?.invoke(typedPacket)
                    ?: throw MinecraftSerializationException(
                        "Encoding ${packetClass.simpleName} requires Login query route values",
                    )
                PacketRoute.LoginQuery(
                    packetDirection = packetRouteKey.packetDirection,
                    transactionId = loginQueryRouteValues.transactionId,
                    channel = packetRouteKey.channel,
                    hasPayload = loginQueryRouteValues.hasPayload,
                )
            }
        }
    }

    internal fun encodeBody(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: Packet,
        sink: Sink,
    ) {
        @Suppress("UNCHECKED_CAST")
        packetBodyCodec.encode(minecraftProtocolFormat, packet as T, sink)
    }

    internal fun decodeBody(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        actualRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): Packet = packetBodyCodec.decode(minecraftProtocolFormat, actualRoute, source, byteCount)

    companion object {
        fun <T : ClientboundPacket.Extension> clientboundTopLevel(
            connectionState: ConnectionState,
            packetId: Int,
            packetClass: KClass<T>,
            packetBodyCodec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.TopLevel(
                connectionState,
                PacketDirection.CLIENTBOUND,
                packetId,
            ),
            packetClass,
            packetBodyCodec,
            loginRouteValues = null,
        )

        fun <T : ServerboundPacket.Extension> serverboundTopLevel(
            connectionState: ConnectionState,
            packetId: Int,
            packetClass: KClass<T>,
            packetBodyCodec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.TopLevel(
                connectionState,
                PacketDirection.SERVERBOUND,
                packetId,
            ),
            packetClass,
            packetBodyCodec,
            loginRouteValues = null,
        )

        fun <T : ClientboundPacket.Extension> clientboundCustomPayload(
            connectionState: ConnectionState,
            channel: Identifier,
            packetClass: KClass<T>,
            packetBodyCodec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.CustomPayload(
                connectionState,
                PacketDirection.CLIENTBOUND,
                channel,
            ),
            packetClass,
            packetBodyCodec,
            loginRouteValues = null,
        )

        fun <T : ServerboundPacket.Extension> serverboundCustomPayload(
            connectionState: ConnectionState,
            channel: Identifier,
            packetClass: KClass<T>,
            packetBodyCodec: PacketBodyCodec<T>,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.CustomPayload(
                connectionState,
                PacketDirection.SERVERBOUND,
                channel,
            ),
            packetClass,
            packetBodyCodec,
            loginRouteValues = null,
        )

        fun <T : ClientboundPacket.Extension> clientboundLoginQuery(
            channel: Identifier,
            packetClass: KClass<T>,
            packetBodyCodec: PacketBodyCodec<T>,
            routeValues: (T) -> LoginQueryRouteValues,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.LoginQuery(
                PacketDirection.CLIENTBOUND,
                channel,
            ),
            packetClass,
            packetBodyCodec,
            loginRouteValues = routeValues,
        )

        fun <T : ServerboundPacket.Extension> serverboundLoginQuery(
            channel: Identifier,
            packetClass: KClass<T>,
            packetBodyCodec: PacketBodyCodec<T>,
            routeValues: (T) -> LoginQueryRouteValues,
        ): PacketCodecRegistration<T> = PacketCodecRegistration(
            PacketRouteKey.LoginQuery(
                PacketDirection.SERVERBOUND,
                channel,
            ),
            packetClass,
            packetBodyCodec,
            loginRouteValues = routeValues,
        )
    }
}
