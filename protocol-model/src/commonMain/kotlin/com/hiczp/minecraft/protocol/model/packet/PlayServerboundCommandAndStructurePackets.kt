package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x36,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_command_block",
)
data class ProgramCommandBlockPacket(
    val location: BlockPosition,
    @MaxLength(32_767)
    val command: String,
    val mode: CommandBlockMode,
    val flags: CommandBlockFlags,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x37,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_command_minecart",
)
data class ProgramCommandBlockMinecartPacket(
    @VarInt
    val entityId: Int,
    @MaxLength(32_767)
    val command: String,
    val trackOutput: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
data class GameRuleChange(
    val name: Identifier,
    @MaxLength(32_767)
    val value: String,
)

@Serializable
@PacketInfo(
    0x39,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_game_rule",
)
data class SetGameRulesPacket(
    val rules: List<GameRuleChange>,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x3A,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_jigsaw_block",
)
data class ProgramJigsawBlockPacket(
    val location: BlockPosition,
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

@Serializable
@PacketInfo(
    0x3B,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_structure_block",
)
data class ProgramStructureBlockPacket(
    val location: BlockPosition,
    val action: StructureUpdateAction,
    val mode: StructureMode,
    @MaxLength(32_767)
    val name: String,
    val offset: StructureOffset,
    val size: StructureSize,
    val mirror: StructureMirror,
    val rotation: StructureRotation,
    @MaxLength(128)
    val metadata: String,
    val integrity: StructureIntegrity,
    @VarLong
    val seed: Long,
    val flags: StructureBlockFlags,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x3C,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_test_block",
)
data class SetTestBlockPacket(
    val position: BlockPosition,
    @ZeroFallbackEnum
    val mode: TestBlockMode,
    @MaxLength(32_767)
    val message: String,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x3D,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "sign_update",
)
data class UpdateSignPacket(
    val location: BlockPosition,
    val frontText: Boolean,
    @FixedLength(4)
    @MaxLength(384)
    val lines: List<String>,
) : PlayStatePacket, ServerboundPacket
