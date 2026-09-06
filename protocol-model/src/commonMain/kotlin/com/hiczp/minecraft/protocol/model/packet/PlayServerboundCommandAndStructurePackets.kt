package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable(with = ServerboundSetCommandBlockPacketSerializer::class)
@PacketInfo(
    0x36,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_command_block",
)
data class ServerboundSetCommandBlockPacket(
    val pos: BlockPosition,
    @MaxLength(32_767)
    val command: String,
    val trackOutput: Boolean,
    val conditional: Boolean,
    val automatic: Boolean,
    val mode: CommandBlockMode,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x37,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_command_minecart",
)
data class ServerboundSetCommandMinecartPacket(
    @VarInt
    val entity: Int,
    @MaxLength(32_767)
    val command: String,
    val trackOutput: Boolean,
) : PlayStatePacket, ServerboundPacket


@Serializable
@PacketInfo(
    0x39,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_game_rule",
)
data class ServerboundSetGameRulePacket(
    val entries: List<Entry>,
) : PlayStatePacket, ServerboundPacket {
    @Serializable
    data class Entry(
        val gameRuleKey: Identifier,
        @MaxLength(32_767)
        val value: String,
    )
}

@Serializable
@PacketInfo(
    0x3A,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_jigsaw_block",
)
data class ServerboundSetJigsawBlockPacket(
    val pos: BlockPosition,
    val name: Identifier,
    val target: Identifier,
    val pool: Identifier,
    @MaxLength(32_767)
    val finalState: String,
    val joint: JigsawJoint,
    @VarInt
    val selectionPriority: Int,
    @VarInt
    val placementPriority: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable(with = ServerboundSetStructureBlockPacketSerializer::class)
@PacketInfo(
    0x3B,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_structure_block",
)
data class ServerboundSetStructureBlockPacket(
    val pos: BlockPosition,
    val updateType: StructureUpdateAction,
    val mode: StructureMode,
    @MaxLength(32_767)
    val name: String,
    val offset: StructureOffset,
    val size: StructureSize,
    val mirror: StructureMirror,
    val rotation: StructureRotation,
    @MaxLength(128)
    val data: String,
    val ignoreEntities: Boolean,
    val strict: Boolean,
    val showAir: Boolean,
    val showBoundingBox: Boolean,
    val integrity: StructureIntegrity,
    @VarLong
    val seed: Long,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x3C,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_test_block",
)
data class ServerboundSetTestBlockPacket(
    val position: BlockPosition,
    @ZeroFallbackEnum
    val mode: TestBlockMode,
    @MaxLength(32_767)
    val message: String,
) : PlayStatePacket, ServerboundPacket

@Serializable(with = ServerboundSignUpdatePacketSerializer::class)
@PacketInfo(
    0x3D,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "sign_update",
)
data class ServerboundSignUpdatePacket(
    val pos: BlockPosition,
    @FixedLength(4)
    @MaxLength(384)
    val lines: List<String>,
    val isFrontText: Boolean,
) : PlayStatePacket, ServerboundPacket
