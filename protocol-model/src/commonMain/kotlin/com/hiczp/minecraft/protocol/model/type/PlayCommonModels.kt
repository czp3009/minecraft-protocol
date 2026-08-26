@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.MaxByteLength
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.UnsignedByte
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class StatisticEntry(
    @VarInt
    val categoryId: Int,
    @VarInt
    val statisticId: Int,
    @VarInt
    val value: Int,
)

@Serializable
data class ChunkBiomeData(
    /** Deliberately first: the packed ChunkPos writes Z in the upper 32 bits. */
    val chunkZ: Int,
    val chunkX: Int,
    @MaxByteLength(2_097_152)
    val data: ByteString,
)

@Serializable
data class CommandSuggestionMatch(
    @MaxLength(32_767)
    val match: String,
    val tooltip: TextComponent?,
)

@Serializable
enum class Difficulty {
    PEACEFUL,
    EASY,
    NORMAL,
    HARD,
}

@Serializable
enum class BossBarColor {
    PINK,
    BLUE,
    RED,
    GREEN,
    YELLOW,
    PURPLE,
    WHITE,
}

@Serializable
enum class BossBarDivision {
    NONE,
    SIX_NOTCHES,
    TEN_NOTCHES,
    TWELVE_NOTCHES,
    TWENTY_NOTCHES,
}

@Serializable(with = BossBarActionSerializer::class)
sealed interface BossBarAction {
    @Serializable
    data class Add(
        val title: TextComponent,
        val health: Float,
        val color: BossBarColor,
        val division: BossBarDivision,
        val flags: Int,
    ) : BossBarAction

    @Serializable
    data object Remove : BossBarAction

    @Serializable
    data class UpdateHealth(val health: Float) : BossBarAction

    @Serializable
    data class UpdateTitle(val title: TextComponent) : BossBarAction

    @Serializable
    data class UpdateStyle(
        val color: BossBarColor,
        val division: BossBarDivision,
    ) : BossBarAction

    @Serializable
    data class UpdateFlags(val flags: Int) : BossBarAction
}

@Serializable
private enum class BossBarActionType {
    ADD,
    REMOVE,
    UPDATE_HEALTH,
    UPDATE_TITLE,
    UPDATE_STYLE,
    UPDATE_FLAGS,
}

internal object BossBarActionSerializer : KSerializer<BossBarAction> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.BossBarAction",
    ) {
        element<BossBarActionType>("type")
        element<TextComponent>("title", isOptional = true)
        element<Float>("health", isOptional = true)
        element<BossBarColor>("color", isOptional = true)
        element<BossBarDivision>("division", isOptional = true)
        element<Int>(
            "flags",
            annotations = listOf(UnsignedByte()),
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: BossBarAction) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is BossBarAction.Add -> {
                output.encodeSerializableElement(
                    descriptor,
                    0,
                    BossBarActionType.serializer(),
                    BossBarActionType.ADD,
                )
                output.encodeSerializableElement(
                    descriptor,
                    1,
                    TextComponent.serializer(),
                    value.title,
                )
                output.encodeFloatElement(descriptor, 2, value.health)
                output.encodeSerializableElement(
                    descriptor,
                    3,
                    BossBarColor.serializer(),
                    value.color,
                )
                output.encodeSerializableElement(
                    descriptor,
                    4,
                    BossBarDivision.serializer(),
                    value.division,
                )
                output.encodeIntElement(descriptor, 5, value.flags)
            }

            BossBarAction.Remove -> output.encodeSerializableElement(
                descriptor,
                0,
                BossBarActionType.serializer(),
                BossBarActionType.REMOVE,
            )

            is BossBarAction.UpdateHealth -> {
                output.encodeSerializableElement(
                    descriptor,
                    0,
                    BossBarActionType.serializer(),
                    BossBarActionType.UPDATE_HEALTH,
                )
                output.encodeFloatElement(descriptor, 2, value.health)
            }

            is BossBarAction.UpdateTitle -> {
                output.encodeSerializableElement(
                    descriptor,
                    0,
                    BossBarActionType.serializer(),
                    BossBarActionType.UPDATE_TITLE,
                )
                output.encodeSerializableElement(
                    descriptor,
                    1,
                    TextComponent.serializer(),
                    value.title,
                )
            }

            is BossBarAction.UpdateStyle -> {
                output.encodeSerializableElement(
                    descriptor,
                    0,
                    BossBarActionType.serializer(),
                    BossBarActionType.UPDATE_STYLE,
                )
                output.encodeSerializableElement(
                    descriptor,
                    3,
                    BossBarColor.serializer(),
                    value.color,
                )
                output.encodeSerializableElement(
                    descriptor,
                    4,
                    BossBarDivision.serializer(),
                    value.division,
                )
            }

            is BossBarAction.UpdateFlags -> {
                output.encodeSerializableElement(
                    descriptor,
                    0,
                    BossBarActionType.serializer(),
                    BossBarActionType.UPDATE_FLAGS,
                )
                output.encodeIntElement(descriptor, 5, value.flags)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): BossBarAction {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val bossBarAction = when (
                input.decodeSerializableElement(
                    descriptor,
                    0,
                    BossBarActionType.serializer(),
                )
            ) {
                BossBarActionType.ADD -> BossBarAction.Add(
                    title = input.decodeSerializableElement(
                        descriptor,
                        1,
                        TextComponent.serializer(),
                    ),
                    health = input.decodeFloatElement(descriptor, 2),
                    color = input.decodeSerializableElement(
                        descriptor,
                        3,
                        BossBarColor.serializer(),
                    ),
                    division = input.decodeSerializableElement(
                        descriptor,
                        4,
                        BossBarDivision.serializer(),
                    ),
                    flags = input.decodeIntElement(descriptor, 5),
                )

                BossBarActionType.REMOVE -> BossBarAction.Remove
                BossBarActionType.UPDATE_HEALTH -> BossBarAction.UpdateHealth(
                    input.decodeFloatElement(descriptor, 2),
                )

                BossBarActionType.UPDATE_TITLE -> BossBarAction.UpdateTitle(
                    input.decodeSerializableElement(
                        descriptor,
                        1,
                        TextComponent.serializer(),
                    ),
                )

                BossBarActionType.UPDATE_STYLE -> BossBarAction.UpdateStyle(
                    input.decodeSerializableElement(
                        descriptor,
                        3,
                        BossBarColor.serializer(),
                    ),
                    input.decodeSerializableElement(
                        descriptor,
                        4,
                        BossBarDivision.serializer(),
                    ),
                )

                BossBarActionType.UPDATE_FLAGS -> BossBarAction.UpdateFlags(
                    input.decodeIntElement(descriptor, 5),
                )
            }
            input.endStructure(descriptor)
            return bossBarAction
        }

        var bossBarActionType: BossBarActionType? = null
        var title: TextComponent? = null
        var health: Float? = null
        var bossBarColor: BossBarColor? = null
        var bossBarDivision: BossBarDivision? = null
        var flags: Int? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                0 -> bossBarActionType = input.decodeSerializableElement(
                    descriptor,
                    0,
                    BossBarActionType.serializer(),
                )

                1 -> title = input.decodeSerializableElement(
                    descriptor,
                    1,
                    TextComponent.serializer(),
                )

                2 -> health = input.decodeFloatElement(descriptor, 2)
                3 -> bossBarColor = input.decodeSerializableElement(
                    descriptor,
                    3,
                    BossBarColor.serializer(),
                )

                4 -> bossBarDivision = input.decodeSerializableElement(
                    descriptor,
                    4,
                    BossBarDivision.serializer(),
                )

                5 -> flags = input.decodeIntElement(descriptor, 5)
                -1 -> break
                else -> throw SerializationException("Unexpected BossBarAction field $index")
            }
        }
        input.endStructure(descriptor)
        return when (bossBarActionType) {
            BossBarActionType.ADD -> BossBarAction.Add(
                required(title, "title"),
                required(health, "health"),
                required(bossBarColor, "color"),
                required(bossBarDivision, "division"),
                required(flags, "flags"),
            )

            BossBarActionType.REMOVE -> BossBarAction.Remove
            BossBarActionType.UPDATE_HEALTH ->
                BossBarAction.UpdateHealth(required(health, "health"))

            BossBarActionType.UPDATE_TITLE ->
                BossBarAction.UpdateTitle(required(title, "title"))

            BossBarActionType.UPDATE_STYLE -> BossBarAction.UpdateStyle(
                required(bossBarColor, "color"),
                required(bossBarDivision, "division"),
            )

            BossBarActionType.UPDATE_FLAGS ->
                BossBarAction.UpdateFlags(required(flags, "flags"))

            null -> throw SerializationException("Missing BossBarAction type")
        }
    }

    private fun <T : Any> required(value: T?, name: String): T =
        value ?: throw SerializationException("Missing BossBarAction $name")
}
