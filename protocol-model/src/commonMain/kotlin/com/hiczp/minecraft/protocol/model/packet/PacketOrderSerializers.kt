package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// These packets declare fields in the official Java member order. Their manual codecs use a different wire order.
// Private serialization surrogates preserve that order without changing the public constructor's logical contract.
internal object ClientboundLevelParticlesPacketSerializer : KSerializer<ClientboundLevelParticlesPacket> {
    private val delegate = LevelParticlesPayload.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ClientboundLevelParticlesPacket) = delegate.serialize(
        encoder,
        LevelParticlesPayload(
            value.overrideLimiter,
            value.alwaysShow,
            value.x,
            value.y,
            value.z,
            value.xDist,
            value.yDist,
            value.zDist,
            value.maxSpeed,
            value.count,
            value.particle
        )
    )

    override fun deserialize(decoder: Decoder): ClientboundLevelParticlesPacket = delegate.deserialize(decoder).let {
        ClientboundLevelParticlesPacket(
            it.x,
            it.y,
            it.z,
            it.xDist,
            it.yDist,
            it.zDist,
            it.maxSpeed,
            it.count,
            it.overrideLimiter,
            it.alwaysShow,
            it.particle
        )
    }
}

@Serializable
private data class LevelParticlesPayload(
    val overrideLimiter: Boolean,
    val alwaysShow: Boolean,
    val x: Double,
    val y: Double,
    val z: Double,
    val xDist: Float,
    val yDist: Float,
    val zDist: Float,
    val maxSpeed: Float,
    val count: Int,
    val particle: ParticleOptions,
)

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

internal object ServerboundSignUpdatePacketSerializer : KSerializer<ServerboundSignUpdatePacket> {
    private val delegate = SignUpdatePayload.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ServerboundSignUpdatePacket) = delegate.serialize(
        encoder,
        SignUpdatePayload(value.pos, value.isFrontText, value.lines)
    )

    override fun deserialize(decoder: Decoder): ServerboundSignUpdatePacket = delegate.deserialize(decoder).let {
        ServerboundSignUpdatePacket(it.pos, it.lines, it.isFrontText)
    }
}

@Serializable
private data class SignUpdatePayload(
    val pos: BlockPosition,
    val isFrontText: Boolean,
    @FixedLength(4) @MaxLength(384) val lines: List<String>
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

internal object ServerboundUseItemOnPacketSerializer : KSerializer<ServerboundUseItemOnPacket> {
    private val delegate = UseItemOnPayload.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ServerboundUseItemOnPacket) = delegate.serialize(
        encoder,
        UseItemOnPayload(value.hand, value.blockHit, value.sequence)
    )

    override fun deserialize(decoder: Decoder): ServerboundUseItemOnPacket = delegate.deserialize(decoder).let {
        ServerboundUseItemOnPacket(it.blockHit, it.hand, it.sequence)
    }
}

@Serializable
private data class UseItemOnPayload(val hand: InteractionHand, val blockHit: BlockHitResult, @VarInt val sequence: Int)

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
