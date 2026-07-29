@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class FoxVariant {
    RED,
    SNOW,
}

@Serializable
enum class SalmonVariant {
    SMALL,
    MEDIUM,
    LARGE,
}

@Serializable
enum class ParrotVariant {
    RED_BLUE,
    BLUE,
    GREEN,
    YELLOW_BLUE,
    GRAY,
}

@Serializable(with = TropicalFishPatternSerializer::class)
enum class TropicalFishPattern {
    KOB,
    SUNSTREAK,
    SNOOPER,
    DASHER,
    BRINELY,
    SPOTTY,
    FLOPPER,
    STRIPEY,
    GLITTER,
    BLOCKFISH,
    BETTY,
    CLAYFISH,
}

@Serializable
enum class MooshroomVariant {
    RED,
    BROWN,
}

@Serializable(with = RabbitVariantSerializer::class)
enum class RabbitVariant {
    BROWN,
    WHITE,
    BLACK,
    WHITE_SPLOTCHED,
    GOLD,
    SALT,
    EVIL,
}

@Serializable
enum class HorseVariant {
    WHITE,
    CREAMY,
    CHESTNUT,
    BROWN,
    BLACK,
    GRAY,
    DARK_BROWN,
}

@Serializable
enum class LlamaVariant {
    CREAMY,
    WHITE,
    BROWN,
    GRAY,
}

@Serializable
enum class AxolotlVariant {
    LUCY,
    WILD,
    GOLD,
    CYAN,
    BLUE,
}

@Serializable
data class PaintingVariantValue(
    @VarInt
    val width: Int,
    @VarInt
    val height: Int,
    val assetId: Identifier,
    val title: TextComponent? = null,
    val author: TextComponent? = null,
)

@Serializable(with = PaintingVariantHolderSerializer::class)
sealed interface PaintingVariantHolder {
    data class Reference(
        val registryId: Int,
    ) : PaintingVariantHolder {
        init {
            require(registryId >= 0) { "A registry ID must be non-negative" }
        }
    }

    data class Direct(
        val value: PaintingVariantValue,
    ) : PaintingVariantHolder
}

internal object PaintingVariantHolderSerializer :
    DirectHolderSerializer<PaintingVariantValue, PaintingVariantHolder>(
        "minecraft.PaintingVariantHolder",
        PaintingVariantValue.serializer(),
    ) {
    override fun registryId(value: PaintingVariantHolder): Int? =
        (value as? PaintingVariantHolder.Reference)?.registryId

    override fun directValue(
        value: PaintingVariantHolder,
    ): PaintingVariantValue? =
        (value as? PaintingVariantHolder.Direct)?.value

    override fun reference(registryId: Int): PaintingVariantHolder =
        PaintingVariantHolder.Reference(registryId)

    override fun direct(value: PaintingVariantValue): PaintingVariantHolder =
        PaintingVariantHolder.Direct(value)
}

internal object RabbitVariantSerializer : KSerializer<RabbitVariant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.RabbitVariant",
        PrimitiveKind.INT,
    )

    override fun serialize(encoder: Encoder, value: RabbitVariant) {
        encoder.encodeInt(
            when (value) {
                RabbitVariant.BROWN -> 0
                RabbitVariant.WHITE -> 1
                RabbitVariant.BLACK -> 2
                RabbitVariant.WHITE_SPLOTCHED -> 3
                RabbitVariant.GOLD -> 4
                RabbitVariant.SALT -> 5
                RabbitVariant.EVIL -> 99
            },
        )
    }

    override fun deserialize(decoder: Decoder): RabbitVariant = when (
        decoder.decodeInt()
    ) {
        1 -> RabbitVariant.WHITE
        2 -> RabbitVariant.BLACK
        3 -> RabbitVariant.WHITE_SPLOTCHED
        4 -> RabbitVariant.GOLD
        5 -> RabbitVariant.SALT
        99 -> RabbitVariant.EVIL
        else -> RabbitVariant.BROWN
    }
}

internal object TropicalFishPatternSerializer :
    KSerializer<TropicalFishPattern> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.TropicalFishPattern",
        PrimitiveKind.INT,
    )

    override fun serialize(encoder: Encoder, value: TropicalFishPattern) {
        encoder.encodeInt(
            when (value) {
                TropicalFishPattern.KOB -> 0
                TropicalFishPattern.SUNSTREAK -> 256
                TropicalFishPattern.SNOOPER -> 512
                TropicalFishPattern.DASHER -> 768
                TropicalFishPattern.BRINELY -> 1024
                TropicalFishPattern.SPOTTY -> 1280
                TropicalFishPattern.FLOPPER -> 1
                TropicalFishPattern.STRIPEY -> 257
                TropicalFishPattern.GLITTER -> 513
                TropicalFishPattern.BLOCKFISH -> 769
                TropicalFishPattern.BETTY -> 1025
                TropicalFishPattern.CLAYFISH -> 1281
            },
        )
    }

    override fun deserialize(decoder: Decoder): TropicalFishPattern = when (
        decoder.decodeInt()
    ) {
        256 -> TropicalFishPattern.SUNSTREAK
        512 -> TropicalFishPattern.SNOOPER
        768 -> TropicalFishPattern.DASHER
        1024 -> TropicalFishPattern.BRINELY
        1280 -> TropicalFishPattern.SPOTTY
        1 -> TropicalFishPattern.FLOPPER
        257 -> TropicalFishPattern.STRIPEY
        513 -> TropicalFishPattern.GLITTER
        769 -> TropicalFishPattern.BLOCKFISH
        1025 -> TropicalFishPattern.BETTY
        1281 -> TropicalFishPattern.CLAYFISH
        else -> TropicalFishPattern.KOB
    }
}
