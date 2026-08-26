package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream

/** Sequential client endpoint: receives clientbound packets and sends serverbound packets. */
class MinecraftClientPacketSession(
    minecraftFrameStream: MinecraftFrameStream,
    packetRegistry: PacketRegistry = MinecraftPacketRegistry,
    minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
) : MinecraftPacketSession<ClientboundPacket, ServerboundPacket>(
    minecraftFrameStream = minecraftFrameStream,
    inboundDirection = PacketDirection.CLIENTBOUND,
    outboundDirection = PacketDirection.SERVERBOUND,
    packetRegistry = packetRegistry,
    minecraftProtocolFormat = minecraftProtocolFormat,
) {
    private var pendingEncryption: ByteArray? = null

    override suspend fun receive(): ClientboundPacket = ClientboundBundleCodec.receive {
        super.receive()
    }

    /** Enables the stream cipher after the next complete Encryption Response frame. */
    fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        requireMinecraftEncryptionKey(sharedSecret)
        check(pendingEncryption == null) {
            "Outbound stream encryption is already pending"
        }
        pendingEncryption = sharedSecret.copyOf()
    }

    override fun requireIncoming(packet: Packet): ClientboundPacket =
        packet as? ClientboundPacket
            ?: throw MinecraftSessionException("Decoded ${packet::class.simpleName} on the clientbound session")

    override fun outboundEncryptionFor(packet: Packet): ByteArray? =
        pendingEncryption.takeIf { packet is EncryptionResponsePacket }

    override fun outboundEncryptionCommitted(sharedSecret: ByteArray) {
        if (pendingEncryption === sharedSecret) pendingEncryption = null
    }

    override fun clearEndpointSensitiveState() {
        pendingEncryption?.fill(0)
        pendingEncryption = null
    }
}
