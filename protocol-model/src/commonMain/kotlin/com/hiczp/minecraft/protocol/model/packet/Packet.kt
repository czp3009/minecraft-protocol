package com.hiczp.minecraft.protocol.model.packet

/**
 * Marker for packet payloads. Framing, compression, encryption, packet IDs, and
 * connection-state transitions are intentionally outside the payload model.
 */
sealed interface Packet

sealed interface ClientboundPacket : Packet
sealed interface ServerboundPacket : Packet

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

/**
 * Human-readable protocol metadata. The runtime registry remains explicit so it
 * works on every Kotlin target without reflection.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PacketInfo(
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
