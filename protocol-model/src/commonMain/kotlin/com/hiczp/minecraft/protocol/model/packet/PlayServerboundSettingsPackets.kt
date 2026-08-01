@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.RecipeBookCategory
import com.hiczp.minecraft.protocol.model.type.ResourcePackResult
import com.hiczp.minecraft.protocol.model.wire.MaxLength
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
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(
    0x2E,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "recipe_book_change_settings",
)
data class ChangeRecipeBookSettingsPacket(
    val book: RecipeBookCategory,
    val open: Boolean,
    val filtering: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x2F,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "recipe_book_seen_recipe",
)
data class SetSeenRecipePacket(
    @VarInt
    val recipeId: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x30,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "rename_item",
)
data class RenameItemPacket(
    @MaxLength(32_767)
    val itemName: String,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x31,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "resource_pack",
)
data class PlayResourcePackResponsePacket(
    val id: Uuid,
    val result: ResourcePackResult,
) : PlayStatePacket, ServerboundPacket

@Serializable
enum class SeenAdvancementsActionType {
    OPENED_TAB,
    CLOSED_SCREEN,
}

@Serializable(with = SeenAdvancementsActionSerializer::class)
sealed interface SeenAdvancementsAction {
    @Serializable
    data class OpenedTab(
        val tab: Identifier,
    ) : SeenAdvancementsAction

    @Serializable
    data object ClosedScreen : SeenAdvancementsAction
}

internal object SeenAdvancementsActionSerializer : KSerializer<SeenAdvancementsAction> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.SeenAdvancementsAction",
    ) {
        element<SeenAdvancementsActionType>("action")
        element<Identifier>("tab", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: SeenAdvancementsAction) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is SeenAdvancementsAction.OpenedTab -> {
                output.encodeSerializableElement(
                    descriptor,
                    ACTION,
                    SeenAdvancementsActionType.serializer(),
                    SeenAdvancementsActionType.OPENED_TAB,
                )
                output.encodeSerializableElement(
                    descriptor,
                    TAB,
                    Identifier.serializer(),
                    value.tab,
                )
            }

            SeenAdvancementsAction.ClosedScreen -> {
                output.encodeSerializableElement(
                    descriptor,
                    ACTION,
                    SeenAdvancementsActionType.serializer(),
                    SeenAdvancementsActionType.CLOSED_SCREEN,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): SeenAdvancementsAction {
        val input = decoder.beginStructure(descriptor)
        var action: SeenAdvancementsActionType? = null
        var tab: Identifier? = null
        if (input.decodeSequentially()) {
            action = input.decodeSerializableElement(
                descriptor,
                ACTION,
                SeenAdvancementsActionType.serializer(),
            )
            if (action == SeenAdvancementsActionType.OPENED_TAB) {
                tab = input.decodeSerializableElement(
                    descriptor,
                    TAB,
                    Identifier.serializer(),
                )
            }
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    ACTION -> action = input.decodeSerializableElement(
                        descriptor,
                        ACTION,
                        SeenAdvancementsActionType.serializer(),
                    )

                    TAB -> tab = input.decodeSerializableElement(
                        descriptor,
                        TAB,
                        Identifier.serializer(),
                    )

                    -1 -> break
                    else -> throw SerializationException(
                        "Unexpected SeenAdvancementsAction field $index",
                    )
                }
            }
        }
        input.endStructure(descriptor)
        return when (
            action ?: throw SerializationException(
                "Missing SeenAdvancementsAction action",
            )
        ) {
            SeenAdvancementsActionType.OPENED_TAB ->
                SeenAdvancementsAction.OpenedTab(
                    tab ?: throw SerializationException(
                        "OPENED_TAB requires a tab identifier",
                    ),
                )

            SeenAdvancementsActionType.CLOSED_SCREEN -> {
                if (tab != null) {
                    throw SerializationException("CLOSED_SCREEN cannot contain a tab")
                }
                SeenAdvancementsAction.ClosedScreen
            }
        }
    }

    private const val ACTION: Int = 0
    private const val TAB: Int = 1
}

@Serializable
@PacketInfo(
    0x32,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "seen_advancements",
)
data class SeenAdvancementsPacket(
    val action: SeenAdvancementsAction,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x33,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "select_trade",
)
data class SelectTradePacket(
    @VarInt
    val selectedSlot: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x34,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_beacon",
)
data class SetBeaconEffectPacket(
    @VarInt
    val primaryEffectId: Int?,
    @VarInt
    val secondaryEffectId: Int?,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x35,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_carried_item",
)
data class ServerboundSetHeldItemPacket(
    val slot: Short,
) : PlayStatePacket, ServerboundPacket
