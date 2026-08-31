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
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

object FabricSplitPayloads {
    const val CLIENTBOUND_CHUNK_SIZE: Int = 1_048_576
    const val SERVERBOUND_CHUNK_SIZE: Int = 32_767

    fun <Incoming : Packet, Outgoing : Packet> split(
        minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
        packet: Outgoing,
        maximumChunkSize: Int,
    ): List<FabricSplitPacket> = split(
        minecraftPacketConnection.encodeCustomPayload(packet),
        maximumChunkSize,
    )

    fun split(
        routedCustomPayload: RoutedCustomPayload,
        maximumChunkSize: Int,
    ): List<FabricSplitPacket> {
        require(maximumChunkSize > 0) {
            "Fabric split chunk size must be positive"
        }
        val target = encodeTarget(routedCustomPayload)
        if (target.size < maximumChunkSize) {
            val targetSize = target.size
            throw MinecraftSerializationException(
                "Fabric split target has $targetSize bytes and does not require splitting at $maximumChunkSize bytes",
            )
        }
        val lengthPrefix = MinecraftProtocolFormat.Default.encodeToByteArray(
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

    fun encodedPacketSize(routedCustomPayload: RoutedCustomPayload): Int =
        encodeTarget(routedCustomPayload).size

    private fun encodeTarget(routedCustomPayload: RoutedCustomPayload): ByteArray =
        MinecraftProtocolFormat.Default.encodeToByteArray(
            FabricSplitTarget(
                routedCustomPayload.route.packetId,
                routedCustomPayload.route.channel,
                routedCustomPayload.data,
            ),
        )
}

/** Stateful merger for one ordered Fabric split stream. */
class FabricSplitAssembler(
    private val splittableChannels: Set<Identifier>,
) {
    private var targetSize: Int? = null
    private var targetChannel: Identifier? = null
    private var bytes: ByteArray = byteArrayOf()
    private var byteCount: Int = 0

    val isCollecting: Boolean
        get() = targetSize != null

    fun accept(
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        fabricSplitPacket: FabricSplitPacket,
    ): RoutedCustomPayload? {
        require(
            connectionState == ConnectionState.CONFIGURATION ||
                    connectionState == ConnectionState.PLAY,
        ) {
            "Fabric split payloads are not valid in $connectionState"
        }
        val fragment = fabricSplitPacket.data.toByteArray()
        if (!isCollecting) start(fragment) else append(fragment)
        val expected = checkNotNull(targetSize)
        if (byteCount < expected) return null
        val complete = bytes
        clear()
        val fabricSplitTarget = MinecraftProtocolFormat.Default.decodeFromByteArray<FabricSplitTarget>(
            complete,
        )
        return RoutedCustomPayload(
            PacketRoute.CustomPayload(
                connectionState,
                packetDirection,
                fabricSplitTarget.packetId,
                fabricSplitTarget.channel,
            ),
            fabricSplitTarget.data,
        )
    }

    fun clear() {
        targetSize = null
        targetChannel = null
        bytes = byteArrayOf()
        byteCount = 0
    }

    private fun start(fragment: ByteArray) {
        val fabricSplitFirstFragment = MinecraftProtocolFormat.Default.decodeFromByteArray<FabricSplitFirstFragment>(
            fragment,
        )
        if (fabricSplitFirstFragment.packetSize <= 0) {
            throw MinecraftSerializationException(
                "Invalid Fabric split target size ${fabricSplitFirstFragment.packetSize}",
            )
        }
        val partial = MinecraftProtocolFormat.Default.decodeFromByteArray<FabricSplitTarget>(
            fabricSplitFirstFragment.data.toByteArray(),
        )
        if (partial.channel !in splittableChannels) {
            throw MinecraftSerializationException(
                "Fabric payload ${partial.channel} is not declared splittable",
            )
        }
        if (fabricSplitFirstFragment.data.size >= fabricSplitFirstFragment.packetSize) {
            throw MinecraftSerializationException(
                "Fabric payload ${partial.channel} used split framing without being split",
            )
        }
        targetSize = fabricSplitFirstFragment.packetSize
        targetChannel = partial.channel
        bytes = ByteArray(fabricSplitFirstFragment.packetSize)
        val initial = fabricSplitFirstFragment.data.toByteArray()
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
