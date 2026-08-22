package com.hiczp.minecraft.protocol.model.packet

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
internal annotation class PacketInfo(
    val id: Int,
    val state: ConnectionState,
    val direction: PacketDirection,
    /**
     * The namespace-free packet name emitted by the vanilla data generator.
     *
     * It makes state/direction/ID shifts mechanically auditable across updates.
     */
    val officialName: String = "",
)
