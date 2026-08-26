package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier

/** A declared extension route without per-exchange wire values. */
sealed interface PacketRouteKey {
    val connectionState: ConnectionState
    val packetDirection: PacketDirection

    data class TopLevel(
        override val connectionState: ConnectionState,
        override val packetDirection: PacketDirection,
        val packetId: Int,
    ) : PacketRouteKey {
        init {
            require(packetId >= 0) { "Packet ID must be non-negative" }
        }
    }

    data class LoginQuery(
        override val packetDirection: PacketDirection,
        val channel: Identifier,
    ) : PacketRouteKey {
        override val connectionState: ConnectionState = ConnectionState.LOGIN
    }

    data class CustomPayload(
        override val connectionState: ConnectionState,
        override val packetDirection: PacketDirection,
        val channel: Identifier,
    ) : PacketRouteKey {
        init {
            require(
                connectionState == ConnectionState.CONFIGURATION ||
                        connectionState == ConnectionState.PLAY,
            ) {
                "Custom payload routes require Configuration or Play state"
            }
        }
    }
}

/**
 * The complete route of one packet after its validated outer header has been
 * removed. [packetRouteKey] is stable across exchanges and is used for codec activation.
 */
sealed interface PacketRoute {
    val connectionState: ConnectionState
    val packetDirection: PacketDirection
    val packetRouteKey: PacketRouteKey

    data class TopLevel(
        override val connectionState: ConnectionState,
        override val packetDirection: PacketDirection,
        val packetId: Int,
    ) : PacketRoute {
        override val packetRouteKey: PacketRouteKey = PacketRouteKey.TopLevel(
            connectionState,
            packetDirection,
            packetId,
        )

        init {
            require(packetId >= 0) { "Packet ID must be non-negative" }
        }
    }

    data class LoginQuery(
        override val packetDirection: PacketDirection,
        val transactionId: Int,
        val channel: Identifier,
        /** Distinguishes an absent response body from an empty response body. */
        val hasPayload: Boolean = true,
    ) : PacketRoute {
        override val connectionState: ConnectionState = ConnectionState.LOGIN
        override val packetRouteKey: PacketRouteKey = PacketRouteKey.LoginQuery(
            packetDirection,
            channel,
        )
    }

    data class CustomPayload(
        override val connectionState: ConnectionState,
        override val packetDirection: PacketDirection,
        /** The validated vanilla outer packet ID used for lossless replay. */
        val packetId: Int,
        val channel: Identifier,
    ) : PacketRoute {
        override val packetRouteKey: PacketRouteKey = PacketRouteKey.CustomPayload(
            connectionState,
            packetDirection,
            channel,
        )

        init {
            require(
                connectionState == ConnectionState.CONFIGURATION ||
                        connectionState == ConnectionState.PLAY,
            ) {
                "Custom payload routes require Configuration or Play state"
            }
            require(packetId >= 0) { "Packet ID must be non-negative" }
        }
    }
}

/** A direction-preserving, lossless packet for a route with no active codec. */
sealed interface UnknownPacket : Packet {
    val packetRoute: PacketRoute
    val data: ByteString

    data class Clientbound(
        override val packetRoute: PacketRoute,
        override val data: ByteString,
    ) : UnknownPacket, ClientboundPacket.Extension {
        init {
            require(packetRoute.packetDirection == PacketDirection.CLIENTBOUND) {
                "A clientbound unknown packet requires a clientbound route"
            }
        }
    }

    data class Serverbound(
        override val packetRoute: PacketRoute,
        override val data: ByteString,
    ) : UnknownPacket, ServerboundPacket.Extension {
        init {
            require(packetRoute.packetDirection == PacketDirection.SERVERBOUND) {
                "A serverbound unknown packet requires a serverbound route"
            }
        }
    }
}
