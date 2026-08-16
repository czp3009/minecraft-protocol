package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketDirection
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.wire.RemainingBytes
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.RoutedCustomPayload
import kotlinx.serialization.Serializable

object NeoForgeSplitPayloads {
    fun <Incoming : Packet, Outgoing : Packet> split(
        connection: MinecraftPacketConnection<Incoming, Outgoing>,
        packet: Outgoing,
        maximumPartSize: Int = NeoForgeProtocol.SPLIT_PART_SIZE,
        maximumPacketSize: Int = NeoForgeProtocol.DEFAULT_MAXIMUM_SPLIT_PACKET_SIZE,
    ): List<NeoForgeSplitPacket> = split(
        connection.encodeCustomPayload(packet),
        maximumPartSize,
        maximumPacketSize,
    )

    fun split(
        payload: RoutedCustomPayload,
        maximumPartSize: Int = NeoForgeProtocol.SPLIT_PART_SIZE,
        maximumPacketSize: Int = NeoForgeProtocol.DEFAULT_MAXIMUM_SPLIT_PACKET_SIZE,
    ): List<NeoForgeSplitPacket> {
        require(maximumPartSize > 0) {
            "NeoForge split part size must be positive"
        }
        require(maximumPacketSize > 0) {
            "NeoForge split packet size must be positive"
        }
        val target = encodeTarget(payload)
        if (target.size > maximumPacketSize) {
            throw MinecraftSerializationException(
                "NeoForge split target has ${target.size} bytes; maximum is $maximumPacketSize",
            )
        }
        if (target.size <= maximumPartSize) {
            throw MinecraftSerializationException(
                "NeoForge split target has ${target.size} bytes and does not require splitting at $maximumPartSize bytes",
            )
        }
        val result = mutableListOf<NeoForgeSplitPacket>()
        var offset = 0
        while (offset < target.size) {
            val end = minOf(offset + maximumPartSize, target.size)
            val state = when {
                offset == 0 -> FIRST
                end == target.size -> LAST
                else -> MIDDLE
            }
            val fragment = ByteArray(end - offset + 1)
            fragment[0] = state
            target.copyInto(
                fragment,
                destinationOffset = 1,
                startIndex = offset,
                endIndex = end,
            )
            result += NeoForgeSplitPacket(ByteString(fragment))
            offset = end
        }
        return result
    }

    fun encodedPacketSize(payload: RoutedCustomPayload): Int =
        encodeTarget(payload).size

    private fun encodeTarget(payload: RoutedCustomPayload): ByteArray =
        MinecraftProtocolFormat.Default.encodeToByteArray(
            NeoForgeSplitTarget.serializer(),
            NeoForgeSplitTarget(
                payload.route.packetId,
                payload.route.channel,
                payload.data,
            ),
        )

    private const val MIDDLE: Byte = 0
    private const val FIRST: Byte = 1
    private const val LAST: Byte = 2
}

/** Stateful assembler for exactly one ordered NeoForge split stream. */
class NeoForgeSplitAssembler(
    val maximumPacketSize: Int = NeoForgeProtocol.DEFAULT_MAXIMUM_SPLIT_PACKET_SIZE,
    maximumChannelSizes: Map<Identifier, Int> = emptyMap(),
) {
    private val maximumChannelSizes = maximumChannelSizes.toMap()
    private val fragments = mutableListOf<ByteArray>()
    private var byteCount: Int = 0

    init {
        require(maximumPacketSize > 0) {
            "NeoForge split packet size must be positive"
        }
        require(this.maximumChannelSizes.values.all { it > 0 }) {
            "NeoForge split channel sizes must be positive"
        }
    }

    val isCollecting: Boolean
        get() = fragments.isNotEmpty()

    fun accept(
        state: ConnectionState,
        direction: PacketDirection,
        packet: NeoForgeSplitPacket,
    ): RoutedCustomPayload? {
        require(
            state == ConnectionState.CONFIGURATION ||
                    state == ConnectionState.PLAY,
        ) {
            "NeoForge split payloads are not valid in $state"
        }
        val payload = packet.payload.toByteArray()
        if (payload.isEmpty()) {
            throw MinecraftSerializationException(
                "NeoForge split fragment has no state byte",
            )
        }
        val fragmentState = payload[0]
        val data = payload.copyOfRange(1, payload.size)
        when (fragmentState) {
            FIRST -> {
                if (isCollecting) {
                    throw MinecraftSerializationException(
                        "NeoForge split stream started before the preceding stream completed",
                    )
                }
                append(data)
                return null
            }

            MIDDLE -> {
                requireCollecting("middle")
                append(data)
                return null
            }

            LAST -> {
                requireCollecting("last")
                append(data)
            }

            else -> throw MinecraftSerializationException(
                "Invalid NeoForge split fragment state $fragmentState",
            )
        }
        val targetBytes = ByteArray(byteCount)
        var offset = 0
        fragments.forEach { fragment ->
            fragment.copyInto(targetBytes, destinationOffset = offset)
            offset += fragment.size
        }
        clear()
        val target = MinecraftProtocolFormat.Default.decodeFromByteArray(
            NeoForgeSplitTarget.serializer(),
            targetBytes,
        )
        val channelMaximum = maximumChannelSizes[target.channel]
            ?: maximumPacketSize
        if (targetBytes.size > channelMaximum) {
            throw MinecraftSerializationException(
                "NeoForge split target ${target.channel} has ${targetBytes.size} bytes; maximum is $channelMaximum",
            )
        }
        return RoutedCustomPayload(
            PacketRoute.CustomPayload(
                state,
                direction,
                target.packetId,
                target.channel,
            ),
            target.data,
        )
    }

    fun clear() {
        fragments.clear()
        byteCount = 0
    }

    private fun append(fragment: ByteArray) {
        if (byteCount.toLong() + fragment.size > maximumPacketSize) {
            clear()
            throw MinecraftSerializationException(
                "NeoForge split stream exceeds $maximumPacketSize bytes",
            )
        }
        fragments += fragment
        byteCount += fragment.size
    }

    private fun requireCollecting(fragment: String) {
        if (!isCollecting) {
            throw MinecraftSerializationException(
                "NeoForge split $fragment fragment arrived before a first fragment",
            )
        }
    }

    private companion object {
        const val MIDDLE: Byte = 0
        const val FIRST: Byte = 1
        const val LAST: Byte = 2
    }
}

@Serializable
private data class NeoForgeSplitTarget(
    @VarInt
    val packetId: Int,
    val channel: Identifier,
    @RemainingBytes
    val data: ByteString,
)
