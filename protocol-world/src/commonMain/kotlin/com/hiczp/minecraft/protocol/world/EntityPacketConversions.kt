package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.ClientboundAddEntityPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.world.format.Entity

fun Entity.toPairingPackets(
    entityPacketEncoder: EntityPacketEncoder,
    entityPairingData: EntityPairingData,
): List<ClientboundPacket> = entityPacketEncoder.encode(this, entityPairingData)

fun Entity.toPairingPackets(
    entityPacketEncoderContext: EntityPacketEncoderContext,
    entityPairingData: EntityPairingData,
): List<ClientboundPacket> = EntityPacketEncoder(entityPacketEncoderContext).encode(this, entityPairingData)

fun ClientboundAddEntityPacket.toEntity(entityPacketDecoder: EntityPacketDecoder): EntityPacketDecodeResult =
    entityPacketDecoder.decode(this)

fun ClientboundAddEntityPacket.toEntity(entityPacketDecoderContext: EntityPacketDecoderContext): EntityPacketDecodeResult =
    EntityPacketDecoder(entityPacketDecoderContext).decode(this)

fun Iterable<ClientboundPacket>.toEntity(entityPacketDecoder: EntityPacketDecoder): EntityPacketDecodeResult =
    entityPacketDecoder.decode(this)

fun Iterable<ClientboundPacket>.toEntity(entityPacketDecoderContext: EntityPacketDecoderContext): EntityPacketDecodeResult =
    EntityPacketDecoder(entityPacketDecoderContext).decode(this)
