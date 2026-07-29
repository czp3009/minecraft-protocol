@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class ChatDecorationParameter {
    SENDER,
    TARGET,
    CONTENT,
}

@Serializable
data class ChatTypeDecoration(
    val translationKey: String,
    val parameters: List<ChatDecorationParameter>,
    val style: NbtTag,
)

@Serializable
data class ChatTypeDefinition(
    val chat: ChatTypeDecoration,
    val narration: ChatTypeDecoration,
)

@Serializable(with = ChatTypeHolderSerializer::class)
sealed interface ChatTypeHolder {
    data class Reference(val registryId: Int) : ChatTypeHolder {
        init {
            require(registryId >= 0) { "A chat-type registry ID must be non-negative" }
        }
    }

    data class Direct(val value: ChatTypeDefinition) : ChatTypeHolder
}

@Serializable
data class BoundChatType(
    val chatType: ChatTypeHolder,
    val name: TextComponent,
    val targetName: TextComponent?,
)

@Serializable
enum class GameMode {
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR,
}

@Serializable
data class CommonPlayerSpawnInfo(
    @VarInt
    val dimensionTypeId: Int,
    val dimension: Identifier,
    val seed: Long,
    @EnumEncoding(EnumEncodingKind.BYTE)
    @ZeroFallbackEnum
    val gameMode: GameMode,
    @EnumEncoding(EnumEncodingKind.BYTE)
    @NullSentinelByte
    @ZeroFallbackEnum
    val previousGameMode: GameMode?,
    val isDebug: Boolean,
    val isFlat: Boolean,
    val lastDeathLocation: GlobalPosition?,
    @VarInt
    val portalCooldown: Int,
    @VarInt
    val seaLevel: Int,
) {
    init {
        require(dimensionTypeId >= 0) {
            "A dimension-type registry ID must be non-negative"
        }
    }
}

@Serializable
data class LightUpdateData(
    val skyYMask: BitSet,
    val blockYMask: BitSet,
    val emptySkyYMask: BitSet,
    val emptyBlockYMask: BitSet,
    val skyUpdates: List<LightDataLayer>,
    val blockUpdates: List<LightDataLayer>,
)

@Serializable
data class LightDataLayer(
    @MaxByteLength(2_048)
    val bytes: ByteString,
) {
    init {
        require(bytes.size <= DATA_LAYER_BYTES) {
            "A light data layer exceeds $DATA_LAYER_BYTES bytes"
        }
    }

    companion object {
        const val DATA_LAYER_BYTES: Int = 2_048
    }
}

@Serializable
data class MapDecoration(
    @VarInt
    val typeId: Int,
    val x: Byte,
    val y: Byte,
    val rotation: Byte,
    val name: TextComponent?,
) {
    init {
        require(typeId >= 0) { "A map decoration registry ID must be non-negative" }
    }
}

@Serializable
data class MapColorPatch(
    val startX: Int,
    val startY: Int,
    val width: Int,
    val height: Int,
    val colors: ByteString,
) {
    init {
        require(startX in 0..255) { "Map patch start X is outside an unsigned byte" }
        require(startY in 0..255) { "Map patch start Y is outside an unsigned byte" }
        require(width in 1..255) { "Map patch width must be in 1..255" }
        require(height in 0..255) { "Map patch height is outside an unsigned byte" }
    }
}

@Serializable(with = NumberFormatSerializer::class)
sealed interface NumberFormat {
    data object Blank : NumberFormat

    data class Styled(val style: NbtTag) : NumberFormat

    data class Fixed(val value: TextComponent) : NumberFormat
}

@Serializable
enum class ObjectiveRenderType {
    INTEGER,
    HEARTS,
}

@Serializable
enum class TeamVisibility {
    ALWAYS,
    NEVER,
    HIDE_FOR_OTHER_TEAMS,
    HIDE_FOR_OWN_TEAM,
}

@Serializable
enum class TeamCollisionRule {
    ALWAYS,
    NEVER,
    PUSH_OTHER_TEAMS,
    PUSH_OWN_TEAM,
}

@Serializable
enum class TeamColor {
    BLACK,
    DARK_BLUE,
    DARK_GREEN,
    DARK_AQUA,
    DARK_RED,
    DARK_PURPLE,
    GOLD,
    GRAY,
    DARK_GRAY,
    BLUE,
    GREEN,
    AQUA,
    RED,
    LIGHT_PURPLE,
    YELLOW,
    WHITE,
}

@Serializable
data class TeamParameters(
    val displayName: TextComponent,
    val playerPrefix: TextComponent,
    val playerSuffix: TextComponent,
    @ZeroFallbackEnum
    val nameTagVisibility: TeamVisibility,
    @ZeroFallbackEnum
    val collisionRule: TeamCollisionRule,
    @ZeroFallbackEnum
    val color: TeamColor?,
    val options: Byte,
)

@Serializable(with = ObjectiveUpdateSerializer::class)
sealed interface ObjectiveUpdate {
    data class Add(
        val displayName: TextComponent,
        val renderType: ObjectiveRenderType,
        val numberFormat: NumberFormat?,
    ) : ObjectiveUpdate

    data object Remove : ObjectiveUpdate

    data class Change(
        val displayName: TextComponent,
        val renderType: ObjectiveRenderType,
        val numberFormat: NumberFormat?,
    ) : ObjectiveUpdate
}

@Serializable(with = TeamUpdateSerializer::class)
sealed interface TeamUpdate {
    data class Add(
        val parameters: TeamParameters,
        val players: List<String>,
    ) : TeamUpdate

    data object Remove : TeamUpdate

    data class Change(val parameters: TeamParameters) : TeamUpdate

    data class Join(val players: List<String>) : TeamUpdate

    data class Leave(val players: List<String>) : TeamUpdate
}

@Serializable(with = DialogHolderSerializer::class)
sealed interface DialogHolder {
    data class Reference(val registryId: Int) : DialogHolder {
        init {
            require(registryId >= 0) { "A dialog registry ID must be non-negative" }
        }
    }

    data class Direct(val dialog: NbtTag) : DialogHolder
}

internal object ChatTypeHolderSerializer : KSerializer<ChatTypeHolder> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ChatTypeHolder",
    ) {
        element<Int>("holderId", annotations = listOf(VarInt()))
        element<ChatTypeDefinition>("direct", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ChatTypeHolder) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is ChatTypeHolder.Reference -> {
                if (value.registryId == Int.MAX_VALUE) {
                    throw SerializationException("Chat-type registry ID overflows its holder ID")
                }
                output.encodeIntElement(descriptor, HOLDER_ID, value.registryId + 1)
            }

            is ChatTypeHolder.Direct -> {
                output.encodeIntElement(descriptor, HOLDER_ID, 0)
                output.encodeSerializableElement(
                    descriptor,
                    DIRECT,
                    ChatTypeDefinition.serializer(),
                    value.value,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ChatTypeHolder {
        val input = decoder.beginStructure(descriptor)
        val holderId = input.decodeIntElement(descriptor, HOLDER_ID)
        val result = when {
            holderId == 0 -> ChatTypeHolder.Direct(
                input.decodeSerializableElement(
                    descriptor,
                    DIRECT,
                    ChatTypeDefinition.serializer(),
                ),
            )

            holderId > 0 -> ChatTypeHolder.Reference(holderId - 1)
            else -> throw SerializationException("Invalid chat-type holder ID $holderId")
        }
        input.endStructure(descriptor)
        return result
    }

    private const val HOLDER_ID: Int = 0
    private const val DIRECT: Int = 1
}

internal object NullableMapColorPatchSerializer : KSerializer<MapColorPatch?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.NullableMapColorPatch",
    ) {
        element<Int>("width", annotations = listOf(UnsignedByte()))
        element<Int>("height", annotations = listOf(UnsignedByte()), isOptional = true)
        element<Int>("startX", annotations = listOf(UnsignedByte()), isOptional = true)
        element<Int>("startY", annotations = listOf(UnsignedByte()), isOptional = true)
        element<ByteString>("colors", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: MapColorPatch?) {
        val output = encoder.beginStructure(descriptor)
        if (value == null) {
            output.encodeIntElement(descriptor, WIDTH, 0)
        } else {
            output.encodeIntElement(descriptor, WIDTH, value.width)
            output.encodeIntElement(descriptor, HEIGHT, value.height)
            output.encodeIntElement(descriptor, START_X, value.startX)
            output.encodeIntElement(descriptor, START_Y, value.startY)
            output.encodeSerializableElement(
                descriptor,
                COLORS,
                ByteString.serializer(),
                value.colors,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): MapColorPatch? {
        val input = decoder.beginStructure(descriptor)
        val width = input.decodeIntElement(descriptor, WIDTH)
        val value = if (width == 0) {
            null
        } else {
            val height = input.decodeIntElement(descriptor, HEIGHT)
            val startX = input.decodeIntElement(descriptor, START_X)
            val startY = input.decodeIntElement(descriptor, START_Y)
            val colors = input.decodeSerializableElement(
                descriptor,
                COLORS,
                ByteString.serializer(),
            )
            MapColorPatch(startX, startY, width, height, colors)
        }
        input.endStructure(descriptor)
        return value
    }

    private const val WIDTH: Int = 0
    private const val HEIGHT: Int = 1
    private const val START_X: Int = 2
    private const val START_Y: Int = 3
    private const val COLORS: Int = 4
}

internal object NumberFormatSerializer : KSerializer<NumberFormat> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.NumberFormat",
    ) {
        element<Int>("typeId", annotations = listOf(VarInt()))
        element<NbtTag>("style", isOptional = true)
        element<TextComponent>("value", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: NumberFormat) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            NumberFormat.Blank -> output.encodeIntElement(descriptor, TYPE, 0)
            is NumberFormat.Styled -> {
                output.encodeIntElement(descriptor, TYPE, 1)
                output.encodeSerializableElement(
                    descriptor,
                    STYLE,
                    NbtTag.serializer(),
                    value.style,
                )
            }

            is NumberFormat.Fixed -> {
                output.encodeIntElement(descriptor, TYPE, 2)
                output.encodeSerializableElement(
                    descriptor,
                    VALUE,
                    TextComponent.serializer(),
                    value.value,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): NumberFormat {
        val input = decoder.beginStructure(descriptor)
        val result = when (val type = input.decodeIntElement(descriptor, TYPE)) {
            0 -> NumberFormat.Blank
            1 -> NumberFormat.Styled(
                input.decodeSerializableElement(
                    descriptor,
                    STYLE,
                    NbtTag.serializer(),
                ),
            )

            2 -> NumberFormat.Fixed(
                input.decodeSerializableElement(
                    descriptor,
                    VALUE,
                    TextComponent.serializer(),
                ),
            )

            else -> throw SerializationException("Unknown number-format type $type")
        }
        input.endStructure(descriptor)
        return result
    }

    private const val TYPE: Int = 0
    private const val STYLE: Int = 1
    private const val VALUE: Int = 2
}

internal object ObjectiveUpdateSerializer : KSerializer<ObjectiveUpdate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ObjectiveUpdate",
    ) {
        element<Byte>("method")
        element<TextComponent>("displayName", isOptional = true)
        element<ObjectiveRenderType>("renderType", isOptional = true)
        element<NumberFormat?>("numberFormat", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ObjectiveUpdate) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is ObjectiveUpdate.Add -> {
                output.encodeByteElement(descriptor, METHOD, 0)
                encodeObjectivePayload(output, value.displayName, value.renderType, value.numberFormat)
            }

            ObjectiveUpdate.Remove -> output.encodeByteElement(descriptor, METHOD, 1)
            is ObjectiveUpdate.Change -> {
                output.encodeByteElement(descriptor, METHOD, 2)
                encodeObjectivePayload(output, value.displayName, value.renderType, value.numberFormat)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ObjectiveUpdate {
        val input = decoder.beginStructure(descriptor)
        val result = when (val method = input.decodeByteElement(descriptor, METHOD).toInt()) {
            0 -> decodeObjectivePayload(input, add = true)
            1 -> ObjectiveUpdate.Remove
            2 -> decodeObjectivePayload(input, add = false)
            else -> ObjectiveUpdate.Remove
        }
        input.endStructure(descriptor)
        return result
    }

    private fun encodeObjectivePayload(
        output: kotlinx.serialization.encoding.CompositeEncoder,
        displayName: TextComponent,
        renderType: ObjectiveRenderType,
        numberFormat: NumberFormat?,
    ) {
        output.encodeSerializableElement(
            descriptor,
            DISPLAY_NAME,
            TextComponent.serializer(),
            displayName,
        )
        output.encodeSerializableElement(
            descriptor,
            RENDER_TYPE,
            ObjectiveRenderType.serializer(),
            renderType,
        )
        output.encodeNullableSerializableElement(
            descriptor,
            NUMBER_FORMAT,
            NumberFormat.serializer(),
            numberFormat,
        )
    }

    private fun decodeObjectivePayload(
        input: kotlinx.serialization.encoding.CompositeDecoder,
        add: Boolean,
    ): ObjectiveUpdate {
        val displayName = input.decodeSerializableElement(
            descriptor,
            DISPLAY_NAME,
            TextComponent.serializer(),
        )
        val renderType = input.decodeSerializableElement(
            descriptor,
            RENDER_TYPE,
            ObjectiveRenderType.serializer(),
        )
        val numberFormat: NumberFormat? = input.decodeNullableSerializableElement(
            descriptor,
            NUMBER_FORMAT,
            NumberFormat.serializer().nullable,
        )
        return if (add) {
            ObjectiveUpdate.Add(displayName, renderType, numberFormat)
        } else {
            ObjectiveUpdate.Change(displayName, renderType, numberFormat)
        }
    }

    private const val METHOD: Int = 0
    private const val DISPLAY_NAME: Int = 1
    private const val RENDER_TYPE: Int = 2
    private const val NUMBER_FORMAT: Int = 3
}

internal object TeamUpdateSerializer : KSerializer<TeamUpdate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.TeamUpdate",
    ) {
        element<Byte>("method")
        element<TeamParameters>("parameters", isOptional = true)
        element<List<String>>("players", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: TeamUpdate) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is TeamUpdate.Add -> {
                output.encodeByteElement(descriptor, METHOD, 0)
                encodeParameters(output, value.parameters)
                encodePlayers(output, value.players)
            }

            TeamUpdate.Remove -> output.encodeByteElement(descriptor, METHOD, 1)
            is TeamUpdate.Change -> {
                output.encodeByteElement(descriptor, METHOD, 2)
                encodeParameters(output, value.parameters)
            }

            is TeamUpdate.Join -> {
                output.encodeByteElement(descriptor, METHOD, 3)
                encodePlayers(output, value.players)
            }

            is TeamUpdate.Leave -> {
                output.encodeByteElement(descriptor, METHOD, 4)
                encodePlayers(output, value.players)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): TeamUpdate {
        val input = decoder.beginStructure(descriptor)
        val result = when (val method = input.decodeByteElement(descriptor, METHOD).toInt()) {
            0 -> TeamUpdate.Add(decodeParameters(input), decodePlayers(input))
            1 -> TeamUpdate.Remove
            2 -> TeamUpdate.Change(decodeParameters(input))
            3 -> TeamUpdate.Join(decodePlayers(input))
            4 -> TeamUpdate.Leave(decodePlayers(input))
            else -> throw SerializationException("Unknown team update method $method")
        }
        input.endStructure(descriptor)
        return result
    }

    private fun encodeParameters(
        output: kotlinx.serialization.encoding.CompositeEncoder,
        value: TeamParameters,
    ) {
        output.encodeSerializableElement(
            descriptor,
            PARAMETERS,
            TeamParameters.serializer(),
            value,
        )
    }

    private fun encodePlayers(
        output: kotlinx.serialization.encoding.CompositeEncoder,
        value: List<String>,
    ) {
        output.encodeSerializableElement(
            descriptor,
            PLAYERS,
            ListSerializer(String.serializer()),
            value,
        )
    }

    private fun decodeParameters(
        input: kotlinx.serialization.encoding.CompositeDecoder,
    ): TeamParameters = input.decodeSerializableElement(
        descriptor,
        PARAMETERS,
        TeamParameters.serializer(),
    )

    private fun decodePlayers(
        input: kotlinx.serialization.encoding.CompositeDecoder,
    ): List<String> = input.decodeSerializableElement(
        descriptor,
        PLAYERS,
        ListSerializer(String.serializer()),
    )

    private const val METHOD: Int = 0
    private const val PARAMETERS: Int = 1
    private const val PLAYERS: Int = 2
}

internal object DialogHolderSerializer : KSerializer<DialogHolder> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.DialogHolder",
    ) {
        element<Int>("holderId", annotations = listOf(VarInt()))
        element<NbtTag>("direct", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: DialogHolder) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is DialogHolder.Reference -> {
                if (value.registryId == Int.MAX_VALUE) {
                    throw SerializationException("Dialog registry ID overflows its holder ID")
                }
                output.encodeIntElement(descriptor, HOLDER_ID, value.registryId + 1)
            }

            is DialogHolder.Direct -> {
                output.encodeIntElement(descriptor, HOLDER_ID, 0)
                output.encodeSerializableElement(
                    descriptor,
                    DIRECT,
                    NbtTag.serializer(),
                    value.dialog,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): DialogHolder {
        val input = decoder.beginStructure(descriptor)
        val holderId = input.decodeIntElement(descriptor, HOLDER_ID)
        val value = when {
            holderId == 0 -> DialogHolder.Direct(
                input.decodeSerializableElement(
                    descriptor,
                    DIRECT,
                    NbtTag.serializer(),
                ),
            )

            holderId > 0 -> DialogHolder.Reference(holderId - 1)
            else -> throw SerializationException("Invalid dialog holder ID $holderId")
        }
        input.endStructure(descriptor)
        return value
    }

    private const val HOLDER_ID: Int = 0
    private const val DIRECT: Int = 1
}
