package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.model.type.Difficulty
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.WrappedEnum
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x00,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "accept_teleportation",
)
data class ServerboundAcceptTeleportationPacket(
    @VarInt
    val id: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x01,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "attack",
)
data class ServerboundAttackPacket(
    @VarInt
    val entityId: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x02,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "block_entity_tag_query",
)
data class ServerboundBlockEntityTagQueryPacket(
    @VarInt
    val transactionId: Int,
    val pos: BlockPosition,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x03,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "bundle_item_selected",
)
data class ServerboundSelectBundleItemPacket(
    @VarInt
    val slotId: Int,
    @VarInt
    val selectedItemIndex: Int,
) : PlayStatePacket, ServerboundPacket {
    init {
        require(selectedItemIndex == -1 || selectedItemIndex >= 0) {
            "selectedItemIndex must be -1 or non-negative"
        }
    }
}

@Serializable
@PacketInfo(
    0x04,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "change_difficulty",
)
data class ServerboundChangeDifficultyPacket(
    @WrappedEnum
    val difficulty: Difficulty,
) : PlayStatePacket, ServerboundPacket

@Serializable
enum class GameMode {
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR,
}

@Serializable
@PacketInfo(
    0x05,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "change_game_mode",
)
data class ServerboundChangeGameModePacket(
    @ZeroFallbackEnum
    val mode: GameMode,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x06,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "chat_ack",
)
data class ServerboundChatAckPacket(
    @VarInt
    val offset: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x07,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "chat_command",
)
data class ServerboundChatCommandPacket(
    @MaxLength(32_767)
    val command: String,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x0B,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "chunk_batch_received",
)
data class ServerboundChunkBatchReceivedPacket(
    val desiredChunksPerTick: Float,
) : PlayStatePacket, ServerboundPacket


@Serializable
@PacketInfo(
    0x0C,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "client_command",
)
data class ServerboundClientCommandPacket(
    val action: Action,
) : PlayStatePacket, ServerboundPacket {
    @Serializable
    enum class Action {
        PERFORM_RESPAWN,
        REQUEST_STATS,
        REQUEST_GAMERULE_VALUES,
    }
}

@Serializable
@PacketInfo(
    0x0D,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "client_tick_end",
)
data object ServerboundClientTickEndPacket : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x0F,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "command_suggestion",
)
data class ServerboundCommandSuggestionPacket(
    @VarInt
    val id: Int,
    @MaxLength(32_500)
    val command: String,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x10,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "configuration_acknowledged",
)
data object ServerboundConfigurationAcknowledgedPacket :
    PlayStatePacket,
    ServerboundPacket
