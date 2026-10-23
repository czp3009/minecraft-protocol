package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

/** Adds a transient client block using its global block-state registry ID. */
@Serializable
@PacketInfo(0x25, ConnectionState.PLAY, PacketDirection.CLIENTBOUND, "add_transient_block")
data class ClientboundAddTransientBlockPacket(val pos: BlockPosition, @VarInt val blockState: Int) :
    PlayStatePacket, ClientboundPacket
