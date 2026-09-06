package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormat
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream

/** Sequential client endpoint: receives clientbound packets and sends serverbound packets. */
class MinecraftClientPacketSession(
    minecraftFrameStream: MinecraftFrameStream,
    packetRegistry: PacketRegistry = PacketRegistry.vanilla,
    minecraftPacketPayloadFormat: MinecraftPacketPayloadFormat = MinecraftPacketPayloadFormat.Default,
) : MinecraftPacketSession<ClientboundPacket, ServerboundPacket>(
    minecraftFrameStream = minecraftFrameStream,
    inboundDirection = PacketDirection.CLIENTBOUND,
    outboundDirection = PacketDirection.SERVERBOUND,
    packetRegistry = packetRegistry,
    minecraftPacketPayloadFormat = minecraftPacketPayloadFormat,
) {
    private var pendingEncryption: ByteArray? = null

    override suspend fun receive(): ClientboundPacket = ClientboundBundleCodec.receive {
        super.receive()
    }

    /** Enables the stream cipher after the next complete Encryption Response frame. */
    fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        require(sharedSecret.size == 16) {
            "Minecraft stream encryption requires a 16-byte shared secret"
        }
        check(pendingEncryption == null) {
            "Outbound stream encryption is already pending"
        }
        pendingEncryption = sharedSecret.copyOf()
    }

    override fun requireIncoming(packet: Packet): ClientboundPacket =
        packet as? ClientboundPacket
            ?: throw MinecraftSessionException("Decoded ${packet::class.simpleName} on the clientbound session")

    override fun outboundEncryptionFor(packet: Packet): ByteArray? =
        pendingEncryption.takeIf { packet is ServerboundKeyPacket }

    override fun outboundEncryptionCommitted(sharedSecret: ByteArray) {
        if (pendingEncryption === sharedSecret) pendingEncryption = null
    }

    override fun clearEndpointSensitiveState() {
        pendingEncryption?.fill(0)
        pendingEncryption = null
    }
}
