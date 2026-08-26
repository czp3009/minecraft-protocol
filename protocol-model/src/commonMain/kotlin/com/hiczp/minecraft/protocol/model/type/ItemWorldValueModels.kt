@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid

@Serializable
data class TypedEntityData(
    @VarInt
    val typeRegistryId: Int,
    @NetworkNbt
    val data: NbtCompound,
)

@Serializable
data class InstrumentValue(
    val sound: SoundEventHolder,
    val useDuration: Float,
    val range: Float,
    val description: TextComponent,
)

@Serializable(with = InstrumentHolderSerializer::class)
sealed interface InstrumentHolder {
    data class Reference(
        val registryId: Int,
    ) : InstrumentHolder {
        init {
            require(registryId >= 0) { "A registry ID must be non-negative" }
        }
    }

    data class Direct(
        val value: InstrumentValue,
    ) : InstrumentHolder
}

@Serializable
data class JukeboxSongValue(
    val sound: SoundEventHolder,
    val description: TextComponent,
    val lengthInSeconds: Float,
    @VarInt
    val comparatorOutput: Int,
)

@Serializable(with = JukeboxSongHolderSerializer::class)
sealed interface JukeboxSongHolder {
    data class Reference(
        val registryId: Int,
    ) : JukeboxSongHolder {
        init {
            require(registryId >= 0) { "A registry ID must be non-negative" }
        }
    }

    data class Direct(
        val value: JukeboxSongValue,
    ) : JukeboxSongHolder
}

@Serializable
enum class FireworkExplosionShape {
    SMALL_BALL,
    LARGE_BALL,
    STAR,
    CREEPER,
    BURST,
}

@Serializable
data class FireworkExplosion(
    @ZeroFallbackEnum
    val shape: FireworkExplosionShape,
    val colors: List<Int>,
    val fadeColors: List<Int>,
    val hasTrail: Boolean,
    val hasTwinkle: Boolean,
)

@Serializable
data class BannerPatternLayer(
    @VarInt
    val patternRegistryId: Int,
    @ZeroFallbackEnum
    val color: DyeColor,
)

@Serializable
data class BeeOccupant(
    val entityData: TypedEntityData,
    @VarInt
    val ticksInHive: Int,
    @VarInt
    val minimumTicksInHive: Int,
)

@Serializable
data class PartialGameProfile(
    @MaxLength(16)
    val name: String? = null,
    val id: Uuid? = null,
    @MaxCollectionSize(16)
    val properties: List<ProfileProperty> = emptyList(),
)

@Serializable(with = ProfileIdentitySerializer::class)
sealed interface ProfileIdentity {
    data class Full(
        val profile: GameProfile,
    ) : ProfileIdentity

    data class Partial(
        val profile: PartialGameProfile,
    ) : ProfileIdentity
}

@Serializable(with = PlayerModelTypeSerializer::class)
enum class PlayerModelType {
    SLIM,
    WIDE,
}

@Serializable
data class PlayerSkinPatch(
    val body: Identifier? = null,
    val cape: Identifier? = null,
    val elytra: Identifier? = null,
    val model: PlayerModelType? = null,
)

internal object InstrumentHolderSerializer :
    DirectHolderSerializer<InstrumentValue, InstrumentHolder>(
        "minecraft.InstrumentHolder",
        InstrumentValue.serializer(),
    ) {
    override fun registryId(value: InstrumentHolder): Int? =
        (value as? InstrumentHolder.Reference)?.registryId

    override fun directValue(value: InstrumentHolder): InstrumentValue? =
        (value as? InstrumentHolder.Direct)?.value

    override fun reference(registryId: Int): InstrumentHolder =
        InstrumentHolder.Reference(registryId)

    override fun direct(value: InstrumentValue): InstrumentHolder =
        InstrumentHolder.Direct(value)
}

internal object JukeboxSongHolderSerializer :
    DirectHolderSerializer<JukeboxSongValue, JukeboxSongHolder>(
        "minecraft.JukeboxSongHolder",
        JukeboxSongValue.serializer(),
    ) {
    override fun registryId(value: JukeboxSongHolder): Int? =
        (value as? JukeboxSongHolder.Reference)?.registryId

    override fun directValue(value: JukeboxSongHolder): JukeboxSongValue? =
        (value as? JukeboxSongHolder.Direct)?.value

    override fun reference(registryId: Int): JukeboxSongHolder =
        JukeboxSongHolder.Reference(registryId)

    override fun direct(value: JukeboxSongValue): JukeboxSongHolder =
        JukeboxSongHolder.Direct(value)
}

internal object ProfileIdentitySerializer : KSerializer<ProfileIdentity> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ProfileIdentity",
    ) {
        element<Boolean>("full")
        element(
            "fullProfile",
            GameProfile.serializer().descriptor,
            isOptional = true,
        )
        element(
            "partialProfile",
            PartialGameProfile.serializer().descriptor,
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: ProfileIdentity) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is ProfileIdentity.Full -> {
                output.encodeBooleanElement(descriptor, IS_FULL, true)
                output.encodeSerializableElement(
                    descriptor,
                    FULL,
                    GameProfile.serializer(),
                    value.profile,
                )
            }

            is ProfileIdentity.Partial -> {
                output.encodeBooleanElement(descriptor, IS_FULL, false)
                output.encodeSerializableElement(
                    descriptor,
                    PARTIAL,
                    PartialGameProfile.serializer(),
                    value.profile,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ProfileIdentity {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "ProfileIdentity requires ordered decoding",
            )
        }
        val profileIdentity = if (
            input.decodeBooleanElement(descriptor, IS_FULL)
        ) {
            ProfileIdentity.Full(
                input.decodeSerializableElement(
                    descriptor,
                    FULL,
                    GameProfile.serializer(),
                ),
            )
        } else {
            ProfileIdentity.Partial(
                input.decodeSerializableElement(
                    descriptor,
                    PARTIAL,
                    PartialGameProfile.serializer(),
                ),
            )
        }
        input.endStructure(descriptor)
        return profileIdentity
    }

    private const val IS_FULL: Int = 0
    private const val FULL: Int = 1
    private const val PARTIAL: Int = 2
}

internal object PlayerModelTypeSerializer : KSerializer<PlayerModelType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.PlayerModelType",
        PrimitiveKind.BOOLEAN,
    )

    override fun serialize(encoder: Encoder, value: PlayerModelType) {
        encoder.encodeBoolean(value == PlayerModelType.SLIM)
    }

    override fun deserialize(decoder: Decoder): PlayerModelType =
        if (decoder.decodeBoolean()) {
            PlayerModelType.SLIM
        } else {
            PlayerModelType.WIDE
        }
}
