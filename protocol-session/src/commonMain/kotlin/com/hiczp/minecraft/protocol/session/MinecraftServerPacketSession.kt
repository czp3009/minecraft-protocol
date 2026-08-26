package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream

/** Sequential server endpoint: receives serverbound packets and sends clientbound packets. */
class MinecraftServerPacketSession(
    frameStream: MinecraftFrameStream,
    packetRegistry: PacketRegistry = MinecraftPacketRegistry,
    format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
) : MinecraftPacketSession<ServerboundPacket, ClientboundPacket>(
    frameStream = frameStream,
    inboundDirection = PacketDirection.SERVERBOUND,
    outboundDirection = PacketDirection.CLIENTBOUND,
    packetRegistry = packetRegistry,
    format = format,
) {
    override suspend fun send(packet: ClientboundPacket) {
        ClientboundBundleCodec.rejectStandaloneDelimiter(packet)
        if (packet is ClientboundBundlePacket) {
            ClientboundBundleCodec.send(packet, ::sendClientboundPacket)
        } else {
            sendClientboundPacket(packet)
        }
    }

    /** Enables the stream cipher after a complete Encryption Response was received. */
    fun enableEncryption(sharedSecret: ByteArray) {
        requireMinecraftEncryptionKey(sharedSecret)
        frameStream.enableEncryption(sharedSecret)
    }

    override fun requireIncoming(packet: Packet): ServerboundPacket =
        packet as? ServerboundPacket
            ?: throw MinecraftSessionException("Decoded ${packet::class.simpleName} on the serverbound session")

    private suspend fun sendClientboundPacket(packet: ClientboundPacket) {
        try {
            super.send(packet)
        } catch (_: SkippablePacketEncodingException) {
            // Vanilla omits this small, explicit packet set when payload encoding fails.
        }
    }
}
