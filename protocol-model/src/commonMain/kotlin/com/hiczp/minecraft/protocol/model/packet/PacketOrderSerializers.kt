package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// These packets declare fields in the official Java member order. Their manual codecs use a different wire order.
// Private serialization surrogates preserve that order without changing the public constructor's logical contract.
internal object ClientboundSetExperiencePacketSerializer : KSerializer<ClientboundSetExperiencePacket> {
    private val delegate = ExperiencePayload.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ClientboundSetExperiencePacket) = delegate.serialize(
        encoder,
        ExperiencePayload(value.experienceProgress, value.experienceLevel, value.totalExperience)
    )

    override fun deserialize(decoder: Decoder): ClientboundSetExperiencePacket = delegate.deserialize(decoder).let {
        ClientboundSetExperiencePacket(it.experienceProgress, it.totalExperience, it.experienceLevel)
    }
}

@Serializable
private data class ExperiencePayload(
    val experienceProgress: Float,
    @VarInt val experienceLevel: Int,
    @VarInt val totalExperience: Int
)

internal object ServerboundPlayerActionPacketSerializer : KSerializer<ServerboundPlayerActionPacket> {
    private val delegate = PlayerActionPayload.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ServerboundPlayerActionPacket) = delegate.serialize(
        encoder,
        PlayerActionPayload(value.action, value.pos, value.direction, value.sequence)
    )

    override fun deserialize(decoder: Decoder): ServerboundPlayerActionPacket = delegate.deserialize(decoder).let {
        ServerboundPlayerActionPacket(it.pos, it.direction, it.action, it.sequence)
    }
}

@Serializable
private data class PlayerActionPayload(
    val action: ServerboundPlayerActionPacket.Action,
    val pos: BlockPosition,
    @EnumEncoding(EnumEncodingKind.UNSIGNED_BYTE) @WrappedEnum val direction: BlockFace,
    @VarInt val sequence: Int,
)

internal object ServerboundSetCommandBlockPacketSerializer : KSerializer<ServerboundSetCommandBlockPacket> {
    private val delegate = CommandBlockPayload.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ServerboundSetCommandBlockPacket) = delegate.serialize(
        encoder,
        CommandBlockPayload(
            value.pos,
            value.command,
            value.mode,
            CommandBlockFlags(value.trackOutput, value.conditional, value.automatic)
        )
    )

    override fun deserialize(decoder: Decoder): ServerboundSetCommandBlockPacket = delegate.deserialize(decoder).let {
        ServerboundSetCommandBlockPacket(
            it.pos,
            it.command,
            it.flags.trackOutput,
            it.flags.conditional,
            it.flags.automatic,
            it.mode
        )
    }
}

@Serializable
private data class CommandBlockPayload(
    val pos: BlockPosition,
    @MaxLength(32_767) val command: String,
    val mode: CommandBlockMode,
    val flags: CommandBlockFlags
)

internal object ServerboundSetStructureBlockPacketSerializer : KSerializer<ServerboundSetStructureBlockPacket> {
    private val delegate = StructureBlockPayload.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ServerboundSetStructureBlockPacket) = delegate.serialize(
        encoder,
        StructureBlockPayload(
            value.pos,
            value.updateType,
            value.mode,
            value.name,
            value.offset,
            value.size,
            value.mirror,
            value.rotation,
            value.data,
            value.integrity,
            value.seed,
            StructureBlockFlags(value.ignoreEntities, value.showAir, value.showBoundingBox, value.strict)
        )
    )

    override fun deserialize(decoder: Decoder): ServerboundSetStructureBlockPacket = delegate.deserialize(decoder).let {
        ServerboundSetStructureBlockPacket(
            it.pos,
            it.updateType,
            it.mode,
            it.name,
            it.offset,
            it.size,
            it.mirror,
            it.rotation,
            it.data,
            it.flags.ignoreEntities,
            it.flags.strictPlacement,
            it.flags.showAir,
            it.flags.showBoundingBox,
            it.integrity,
            it.seed
        )
    }
}

@Serializable
private data class StructureBlockPayload(
    val pos: BlockPosition,
    val updateType: StructureUpdateAction,
    val mode: StructureMode,
    @MaxLength(32_767) val name: String,
    val offset: StructureOffset,
    val size: StructureSize,
    val mirror: StructureMirror,
    @WrappedEnum
    val rotation: StructureRotation,
    @MaxLength(128) val data: String,
    val integrity: StructureIntegrity,
    @VarLong val seed: Long,
    val flags: StructureBlockFlags,
)

internal object ServerboundPlayerAbilitiesPacketSerializer : KSerializer<ServerboundPlayerAbilitiesPacket> {
    private val delegate = ServerboundAbilities.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ServerboundPlayerAbilitiesPacket) =
        delegate.serialize(encoder, ServerboundAbilities(value.isFlying))

    override fun deserialize(decoder: Decoder): ServerboundPlayerAbilitiesPacket =
        ServerboundPlayerAbilitiesPacket(delegate.deserialize(decoder).flying)
}
