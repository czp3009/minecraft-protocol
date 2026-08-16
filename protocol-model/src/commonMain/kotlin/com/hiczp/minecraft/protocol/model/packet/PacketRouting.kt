package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier

/** A declared extension route without per-exchange wire values. */
sealed interface PacketRouteKey {
    val state: ConnectionState
    val direction: PacketDirection

    data class TopLevel(
        override val state: ConnectionState,
        override val direction: PacketDirection,
        val packetId: Int,
    ) : PacketRouteKey {
        init {
            require(packetId >= 0) { "Packet ID must be non-negative" }
        }
    }

    data class LoginQuery(
        override val direction: PacketDirection,
        val channel: Identifier,
    ) : PacketRouteKey {
        override val state: ConnectionState = ConnectionState.LOGIN
    }

    data class CustomPayload(
        override val state: ConnectionState,
        override val direction: PacketDirection,
        val channel: Identifier,
    ) : PacketRouteKey {
        init {
            require(
                state == ConnectionState.CONFIGURATION ||
                        state == ConnectionState.PLAY,
            ) {
                "Custom payload routes require Configuration or Play state"
            }
        }
    }
}

/**
 * The complete route of one packet after its validated outer header has been
 * removed. [key] is stable across exchanges and is used for codec activation.
 */
sealed interface PacketRoute {
    val state: ConnectionState
    val direction: PacketDirection
    val key: PacketRouteKey

    data class TopLevel(
        override val state: ConnectionState,
        override val direction: PacketDirection,
        val packetId: Int,
    ) : PacketRoute {
        override val key: PacketRouteKey = PacketRouteKey.TopLevel(
            state,
            direction,
            packetId,
        )

        init {
            require(packetId >= 0) { "Packet ID must be non-negative" }
        }
    }

    data class LoginQuery(
        override val direction: PacketDirection,
        val transactionId: Int,
        val channel: Identifier,
        /** Distinguishes an absent response body from an empty response body. */
        val hasPayload: Boolean = true,
    ) : PacketRoute {
        override val state: ConnectionState = ConnectionState.LOGIN
        override val key: PacketRouteKey = PacketRouteKey.LoginQuery(
            direction,
            channel,
        )
    }

    data class CustomPayload(
        override val state: ConnectionState,
        override val direction: PacketDirection,
        /** The validated vanilla outer packet ID used for lossless replay. */
        val packetId: Int,
        val channel: Identifier,
    ) : PacketRoute {
        override val key: PacketRouteKey = PacketRouteKey.CustomPayload(
            state,
            direction,
            channel,
        )

        init {
            require(
                state == ConnectionState.CONFIGURATION ||
                        state == ConnectionState.PLAY,
            ) {
                "Custom payload routes require Configuration or Play state"
            }
            require(packetId >= 0) { "Packet ID must be non-negative" }
        }
    }
}

/** A direction-preserving, lossless packet for a route with no active codec. */
sealed interface UnknownPacket : Packet {
    val route: PacketRoute
    val data: ByteString

    data class Clientbound(
        override val route: PacketRoute,
        override val data: ByteString,
    ) : UnknownPacket, ClientboundPacket.Extension {
        init {
            require(route.direction == PacketDirection.CLIENTBOUND) {
                "A clientbound unknown packet requires a clientbound route"
            }
        }
    }

    data class Serverbound(
        override val route: PacketRoute,
        override val data: ByteString,
    ) : UnknownPacket, ServerboundPacket.Extension {
        init {
            require(route.direction == PacketDirection.SERVERBOUND) {
                "A serverbound unknown packet requires a serverbound route"
            }
        }
    }
}
