package com.hiczp.minecraft.protocol.fabric

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

object FabricSplitPayloads {
    const val CLIENTBOUND_CHUNK_SIZE: Int = 1_048_576
    const val SERVERBOUND_CHUNK_SIZE: Int = 32_767

    fun <Incoming : Packet, Outgoing : Packet> split(
        connection: MinecraftPacketConnection<Incoming, Outgoing>,
        packet: Outgoing,
        maximumChunkSize: Int,
        maximumPacketSize: Int = FabricProtocol.DEFAULT_MAXIMUM_SPLIT_PACKET_SIZE,
    ): List<FabricSplitPacket> = split(
        connection.encodeCustomPayload(packet),
        maximumChunkSize,
        maximumPacketSize,
    )

    fun split(
        payload: RoutedCustomPayload,
        maximumChunkSize: Int,
        maximumPacketSize: Int = FabricProtocol.DEFAULT_MAXIMUM_SPLIT_PACKET_SIZE,
    ): List<FabricSplitPacket> {
        require(maximumChunkSize > 0) {
            "Fabric split chunk size must be positive"
        }
        require(maximumPacketSize > 0) {
            "Fabric split packet size must be positive"
        }
        val target = encodeTarget(payload)
        if (target.size > maximumPacketSize) {
            throw MinecraftSerializationException(
                "Fabric split target has ${target.size} bytes; maximum is $maximumPacketSize",
            )
        }
        if (target.size < maximumChunkSize) {
            throw MinecraftSerializationException(
                "Fabric split target has ${target.size} bytes and does not require splitting at $maximumChunkSize bytes",
            )
        }
        val lengthPrefix = MinecraftProtocolFormat.Default.encodeToByteArray(
            FabricSplitLength.serializer(),
            FabricSplitLength(target.size),
        )
        if (lengthPrefix.size >= maximumChunkSize) {
            throw MinecraftSerializationException(
                "Fabric split chunk size $maximumChunkSize cannot hold its length prefix",
            )
        }
        val result = mutableListOf<FabricSplitPacket>()
        var offset = maximumChunkSize - lengthPrefix.size
        val first = ByteArray(maximumChunkSize)
        lengthPrefix.copyInto(first)
        target.copyInto(
            first,
            destinationOffset = lengthPrefix.size,
            endIndex = offset,
        )
        result += FabricSplitPacket(ByteString(first))
        while (offset < target.size) {
            val end = minOf(offset + maximumChunkSize, target.size)
            result += FabricSplitPacket(
                ByteString(target.copyOfRange(offset, end)),
            )
            offset = end
        }
        return result
    }

    fun encodedPacketSize(payload: RoutedCustomPayload): Int =
        encodeTarget(payload).size

    private fun encodeTarget(payload: RoutedCustomPayload): ByteArray =
        MinecraftProtocolFormat.Default.encodeToByteArray(
            FabricSplitTarget.serializer(),
            FabricSplitTarget(
                payload.route.packetId,
                payload.route.channel,
                payload.data,
            ),
        )
}

/** Stateful merger for one ordered Fabric split stream. */
class FabricSplitAssembler(
    maximumPacketSizes: Map<Identifier, Int>,
) {
    private val maximumPacketSizes: Map<Identifier, Int> =
        maximumPacketSizes.toMap()
    private var targetSize: Int? = null
    private var targetChannel: Identifier? = null
    private var bytes: ByteArray = byteArrayOf()
    private var byteCount: Int = 0

    init {
        require(this.maximumPacketSizes.values.all { it > 0 }) {
            "Fabric split maximum packet sizes must be positive"
        }
    }

    val isCollecting: Boolean
        get() = targetSize != null

    fun accept(
        state: ConnectionState,
        direction: PacketDirection,
        packet: FabricSplitPacket,
    ): RoutedCustomPayload? {
        require(
            state == ConnectionState.CONFIGURATION ||
                    state == ConnectionState.PLAY,
        ) {
            "Fabric split payloads are not valid in $state"
        }
        val fragment = packet.data.toByteArray()
        if (!isCollecting) start(fragment) else append(fragment)
        val expected = checkNotNull(targetSize)
        if (byteCount < expected) return null
        val complete = bytes
        clear()
        val target = MinecraftProtocolFormat.Default.decodeFromByteArray(
            FabricSplitTarget.serializer(),
            complete,
        )
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
        targetSize = null
        targetChannel = null
        bytes = byteArrayOf()
        byteCount = 0
    }

    private fun start(fragment: ByteArray) {
        val first = MinecraftProtocolFormat.Default.decodeFromByteArray(
            FabricSplitFirstFragment.serializer(),
            fragment,
        )
        if (first.packetSize <= 0) {
            throw MinecraftSerializationException(
                "Invalid Fabric split target size ${first.packetSize}",
            )
        }
        val partial = MinecraftProtocolFormat.Default.decodeFromByteArray(
            FabricSplitTarget.serializer(),
            first.data.toByteArray(),
        )
        val maximum = maximumPacketSizes[partial.channel]
            ?: throw MinecraftSerializationException(
                "Fabric payload ${partial.channel} is not declared splittable",
            )
        if (first.packetSize > maximum) {
            throw MinecraftSerializationException(
                "Fabric payload ${partial.channel} has ${first.packetSize} bytes; maximum is $maximum",
            )
        }
        if (first.data.size >= first.packetSize) {
            throw MinecraftSerializationException(
                "Fabric payload ${partial.channel} used split framing without being split",
            )
        }
        targetSize = first.packetSize
        targetChannel = partial.channel
        bytes = ByteArray(first.packetSize)
        val initial = first.data.toByteArray()
        initial.copyInto(bytes)
        byteCount = initial.size
    }

    private fun append(fragment: ByteArray) {
        val expected = checkNotNull(targetSize)
        if (byteCount.toLong() + fragment.size > expected) {
            throw MinecraftSerializationException(
                "Fabric split stream for $targetChannel exceeds its declared $expected bytes",
            )
        }
        fragment.copyInto(bytes, destinationOffset = byteCount)
        byteCount += fragment.size
    }
}

@Serializable
private data class FabricSplitLength(
    @VarInt
    val packetSize: Int,
)

@Serializable
private data class FabricSplitFirstFragment(
    @VarInt
    val packetSize: Int,
    @RemainingBytes
    val data: ByteString,
)

@Serializable
private data class FabricSplitTarget(
    @VarInt
    val packetId: Int,
    val channel: Identifier,
    @RemainingBytes
    val data: ByteString,
)
