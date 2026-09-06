package com.hiczp.minecraft.protocol.model.packet

import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/**
 * Marker for packet payloads. Framing, compression, encryption, packet IDs, and
 * connection-state transitions are intentionally outside the payload model.
 */
sealed interface Packet

sealed interface ClientboundPacket : Packet {
    /**
     * Explicitly extensible branch for clientbound packets declared by an
     * application or an optional protocol integration.
     */
    interface Extension : ClientboundPacket
}

/** Clientbound packets vanilla may omit when their payload cannot be encoded. */
sealed interface SkippableClientboundPacket : ClientboundPacket

sealed interface ServerboundPacket : Packet {
    /**
     * Explicitly extensible branch for serverbound packets declared by an
     * application or an optional protocol integration.
     */
    interface Extension : ServerboundPacket
}

sealed interface HandshakeStatePacket : Packet
sealed interface StatusStatePacket : Packet
sealed interface LoginStatePacket : Packet
sealed interface ConfigurationStatePacket : Packet
sealed interface PlayStatePacket : Packet

enum class ConnectionState {
    HANDSHAKE,
    STATUS,
    LOGIN,
    CONFIGURATION,
    PLAY,
}

enum class PacketDirection {
    CLIENTBOUND,
    SERVERBOUND,
}

enum class PacketFraming {
    NORMAL,
    LEGACY_UNFRAMED,
}

/** Source metadata consumed by KSP to build the portable runtime registry. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class PacketInfo(
    val id: Int,
    val connectionState: ConnectionState,
    val packetDirection: PacketDirection,
    /**
     * The namespace-free packet name emitted by the vanilla data generator.
     *
     * It makes state/direction/ID shifts mechanically auditable across updates.
     */
    val officialName: String = "",
    /** A specific Kotlin representation or runtime-container difference, reviewed against the class evidence. */
    val shapeException: String = "",
    /** Only needed when the official Java name cannot be used as this Kotlin declaration's name. */
    val nameException: String = "",
    /** The official registration may select a different codec for the same packet value in another state. */
    val serializer: KClass<out KSerializer<*>> = Nothing::class,
)
