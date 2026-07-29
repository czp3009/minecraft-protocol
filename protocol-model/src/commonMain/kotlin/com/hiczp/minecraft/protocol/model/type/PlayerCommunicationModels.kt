@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class PackedSignedMessageBody(
    @MaxLength(256)
    val content: String,
    val timestampEpochMillis: Long,
    val salt: Long,
    @MaxCollectionSize(20)
    val lastSeen: List<PackedMessageSignature>,
)

@Serializable(with = FilterMaskSerializer::class)
sealed interface FilterMask {
    data object PassThrough : FilterMask

    data object FullyFiltered : FilterMask

    data class PartiallyFiltered(val mask: BitSet) : FilterMask
}

enum class PlayerInfoAction {
    ADD_PLAYER,
    INITIALIZE_CHAT,
    UPDATE_GAME_MODE,
    UPDATE_LISTED,
    UPDATE_LATENCY,
    UPDATE_DISPLAY_NAME,
    UPDATE_LIST_ORDER,
    UPDATE_HAT,
}

@Serializable
data class PlayerListProfile(
    @MaxLength(16)
    val name: String,
    @MaxCollectionSize(16)
    val properties: List<ProfileProperty>,
)

data class PlayerInfoEntry(
    val profileId: Uuid,
    val profile: PlayerListProfile? = null,
    val chatSession: ChatSessionData? = null,
    val gameMode: GameMode = GameMode.SURVIVAL,
    val listed: Boolean = false,
    val latency: Int = 0,
    val displayName: TextComponent? = null,
    val listOrder: Int = 0,
    val showHat: Boolean = false,
)

@Serializable(with = PlayerInfoUpdatePayloadSerializer::class)
data class PlayerInfoUpdatePayload(
    val actions: Set<PlayerInfoAction>,
    val entries: List<PlayerInfoEntry>,
)

@Serializable
data class WaypointIcon(
    val style: Identifier,
    @Serializable(with = RgbColorSerializer::class)
    val color: Int? = null,
)

@Serializable(with = WaypointIdentifierSerializer::class)
sealed interface WaypointIdentifier {
    data class Entity(val uuid: Uuid) : WaypointIdentifier

    data class Named(val name: String) : WaypointIdentifier
}

@Serializable(with = TrackedWaypointSerializer::class)
sealed interface TrackedWaypoint {
    val identifier: WaypointIdentifier
    val icon: WaypointIcon

    data class Empty(
        override val identifier: WaypointIdentifier,
        override val icon: WaypointIcon,
    ) : TrackedWaypoint

    data class Position(
        override val identifier: WaypointIdentifier,
        override val icon: WaypointIcon,
        val x: Int,
        val y: Int,
        val z: Int,
    ) : TrackedWaypoint

    data class Chunk(
        override val identifier: WaypointIdentifier,
        override val icon: WaypointIcon,
        val x: Int,
        val z: Int,
    ) : TrackedWaypoint

    data class Azimuth(
        override val identifier: WaypointIdentifier,
        override val icon: WaypointIcon,
        val angle: Float,
    ) : TrackedWaypoint
}

@Serializable
enum class WaypointOperation {
    TRACK,
    UNTRACK,
    UPDATE,
}

internal object FilterMaskSerializer : KSerializer<FilterMask> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.FilterMask",
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element<BitSet>("mask", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: FilterMask) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            FilterMask.PassThrough -> output.encodeIntElement(descriptor, TYPE, 0)
            FilterMask.FullyFiltered -> output.encodeIntElement(descriptor, TYPE, 1)
            is FilterMask.PartiallyFiltered -> {
                output.encodeIntElement(descriptor, TYPE, 2)
                output.encodeSerializableElement(
                    descriptor,
                    MASK,
                    BitSet.serializer(),
                    value.mask,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): FilterMask {
        val input = decoder.beginStructure(descriptor)
        val value = when (val type = input.decodeIntElement(descriptor, TYPE)) {
            0 -> FilterMask.PassThrough
            1 -> FilterMask.FullyFiltered
            2 -> FilterMask.PartiallyFiltered(
                input.decodeSerializableElement(
                    descriptor,
                    MASK,
                    BitSet.serializer(),
                ),
            )

            else -> throw SerializationException("Unknown filter-mask type $type")
        }
        input.endStructure(descriptor)
        return value
    }

    private const val TYPE: Int = 0
    private const val MASK: Int = 1
}

internal object PlayerInfoUpdatePayloadSerializer :
    KSerializer<PlayerInfoUpdatePayload> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.PlayerInfoUpdate",
    ) {
        element<Byte>("actionMask")
        element(
            "entries",
            ListSerializer(
                PlayerInfoEntrySerializer(emptySet()),
            ).descriptor,
        )
    }

    override fun serialize(encoder: Encoder, value: PlayerInfoUpdatePayload) {
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(
            descriptor,
            ACTIONS,
            value.actions.fold(0) { mask, action ->
                mask or (1 shl action.ordinal)
            }.toByte(),
        )
        output.encodeSerializableElement(
            descriptor,
            ENTRIES,
            ListSerializer(PlayerInfoEntrySerializer(value.actions)),
            value.entries,
        )
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PlayerInfoUpdatePayload {
        val input = decoder.beginStructure(descriptor)
        val mask = input.decodeByteElement(descriptor, ACTIONS).toInt() and 0xFF
        val actions = PlayerInfoAction.entries.filterTo(linkedSetOf()) {
            mask and (1 shl it.ordinal) != 0
        }
        val entries = input.decodeSerializableElement(
            descriptor,
            ENTRIES,
            ListSerializer(PlayerInfoEntrySerializer(actions)),
        )
        input.endStructure(descriptor)
        return PlayerInfoUpdatePayload(actions, entries)
    }

    private const val ACTIONS: Int = 0
    private const val ENTRIES: Int = 1
}

private class PlayerInfoEntrySerializer(
    private val actions: Set<PlayerInfoAction>,
) : KSerializer<PlayerInfoEntry> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.PlayerInfoEntry",
    ) {
        element<Uuid>("profileId")
        element<PlayerListProfile>("profile", isOptional = true)
        element<ChatSessionData?>("chatSession", isOptional = true)
        element<GameMode>(
            "gameMode",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
        element<Boolean>("listed", isOptional = true)
        element<Int>(
            "latency",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element<TextComponent?>("displayName", isOptional = true)
        element<Int>(
            "listOrder",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element<Boolean>("showHat", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: PlayerInfoEntry) {
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(
            descriptor,
            PROFILE_ID,
            Uuid.serializer(),
            value.profileId,
        )
        for (action in actions.sortedBy { it.ordinal }) {
            when (action) {
                PlayerInfoAction.ADD_PLAYER -> output.encodeSerializableElement(
                    descriptor,
                    PROFILE,
                    PlayerListProfile.serializer(),
                    value.profile ?: throw SerializationException(
                        "ADD_PLAYER requires profile data",
                    ),
                )

                PlayerInfoAction.INITIALIZE_CHAT ->
                    output.encodeNullableSerializableElement(
                        descriptor,
                        CHAT_SESSION,
                        ChatSessionData.serializer(),
                        value.chatSession,
                    )

                PlayerInfoAction.UPDATE_GAME_MODE ->
                    output.encodeSerializableElement(
                        descriptor,
                        GAME_MODE,
                        GameMode.serializer(),
                        value.gameMode,
                    )

                PlayerInfoAction.UPDATE_LISTED ->
                    output.encodeBooleanElement(descriptor, LISTED, value.listed)

                PlayerInfoAction.UPDATE_LATENCY ->
                    output.encodeIntElement(descriptor, LATENCY, value.latency)

                PlayerInfoAction.UPDATE_DISPLAY_NAME ->
                    output.encodeNullableSerializableElement(
                        descriptor,
                        DISPLAY_NAME,
                        TextComponent.serializer(),
                        value.displayName,
                    )

                PlayerInfoAction.UPDATE_LIST_ORDER ->
                    output.encodeIntElement(descriptor, LIST_ORDER, value.listOrder)

                PlayerInfoAction.UPDATE_HAT ->
                    output.encodeBooleanElement(descriptor, SHOW_HAT, value.showHat)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PlayerInfoEntry {
        val input = decoder.beginStructure(descriptor)
        val profileId = input.decodeSerializableElement(
            descriptor,
            PROFILE_ID,
            Uuid.serializer(),
        )
        var profile: PlayerListProfile? = null
        var chatSession: ChatSessionData? = null
        var gameMode = GameMode.SURVIVAL
        var listed = false
        var latency = 0
        var displayName: TextComponent? = null
        var listOrder = 0
        var showHat = false
        for (action in actions.sortedBy { it.ordinal }) {
            when (action) {
                PlayerInfoAction.ADD_PLAYER -> profile =
                    input.decodeSerializableElement(
                        descriptor,
                        PROFILE,
                        PlayerListProfile.serializer(),
                    )

                PlayerInfoAction.INITIALIZE_CHAT -> chatSession =
                    input.decodeNullableSerializableElement(
                        descriptor,
                        CHAT_SESSION,
                        ChatSessionData.serializer().nullable,
                    )

                PlayerInfoAction.UPDATE_GAME_MODE -> gameMode =
                    input.decodeSerializableElement(
                        descriptor,
                        GAME_MODE,
                        GameMode.serializer(),
                    )

                PlayerInfoAction.UPDATE_LISTED -> listed =
                    input.decodeBooleanElement(descriptor, LISTED)

                PlayerInfoAction.UPDATE_LATENCY -> latency =
                    input.decodeIntElement(descriptor, LATENCY)

                PlayerInfoAction.UPDATE_DISPLAY_NAME -> displayName =
                    input.decodeNullableSerializableElement(
                        descriptor,
                        DISPLAY_NAME,
                        TextComponent.serializer().nullable,
                    )

                PlayerInfoAction.UPDATE_LIST_ORDER -> listOrder =
                    input.decodeIntElement(descriptor, LIST_ORDER)

                PlayerInfoAction.UPDATE_HAT -> showHat =
                    input.decodeBooleanElement(descriptor, SHOW_HAT)
            }
        }
        input.endStructure(descriptor)
        return PlayerInfoEntry(
            profileId,
            profile,
            chatSession,
            gameMode,
            listed,
            latency,
            displayName,
            listOrder,
            showHat,
        )
    }

    private companion object {
        const val PROFILE_ID: Int = 0
        const val PROFILE: Int = 1
        const val CHAT_SESSION: Int = 2
        const val GAME_MODE: Int = 3
        const val LISTED: Int = 4
        const val LATENCY: Int = 5
        const val DISPLAY_NAME: Int = 6
        const val LIST_ORDER: Int = 7
        const val SHOW_HAT: Int = 8
    }
}

internal object RgbColorSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.RgbColor",
    ) {
        element<Int>("red", annotations = listOf(UnsignedByte()))
        element<Int>("green", annotations = listOf(UnsignedByte()))
        element<Int>("blue", annotations = listOf(UnsignedByte()))
    }

    override fun serialize(encoder: Encoder, value: Int) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, RED, value ushr 16 and 0xFF)
        output.encodeIntElement(descriptor, GREEN, value ushr 8 and 0xFF)
        output.encodeIntElement(descriptor, BLUE, value and 0xFF)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): Int {
        val input = decoder.beginStructure(descriptor)
        val value = input.decodeIntElement(descriptor, RED) shl 16 or
                (input.decodeIntElement(descriptor, GREEN) shl 8) or
                input.decodeIntElement(descriptor, BLUE)
        input.endStructure(descriptor)
        return value
    }

    private const val RED: Int = 0
    private const val GREEN: Int = 1
    private const val BLUE: Int = 2
}

internal object WaypointIdentifierSerializer : KSerializer<WaypointIdentifier> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.WaypointIdentifier",
    ) {
        element<Boolean>("isUuid")
        element<Uuid>("uuid", isOptional = true)
        element<String>("name", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: WaypointIdentifier) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is WaypointIdentifier.Entity -> {
                output.encodeBooleanElement(descriptor, IS_UUID, true)
                output.encodeSerializableElement(
                    descriptor,
                    UUID,
                    Uuid.serializer(),
                    value.uuid,
                )
            }

            is WaypointIdentifier.Named -> {
                output.encodeBooleanElement(descriptor, IS_UUID, false)
                output.encodeStringElement(descriptor, NAME, value.name)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): WaypointIdentifier {
        val input = decoder.beginStructure(descriptor)
        val value = if (input.decodeBooleanElement(descriptor, IS_UUID)) {
            WaypointIdentifier.Entity(
                input.decodeSerializableElement(
                    descriptor,
                    UUID,
                    Uuid.serializer(),
                ),
            )
        } else {
            WaypointIdentifier.Named(
                input.decodeStringElement(descriptor, NAME),
            )
        }
        input.endStructure(descriptor)
        return value
    }

    private const val IS_UUID: Int = 0
    private const val UUID: Int = 1
    private const val NAME: Int = 2
}

internal object TrackedWaypointSerializer : KSerializer<TrackedWaypoint> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.TrackedWaypoint",
    ) {
        element<WaypointIdentifier>("identifier")
        element<WaypointIcon>("icon")
        element<Int>("type", annotations = listOf(VarInt()))
        element<Int>("x", annotations = listOf(VarInt()), isOptional = true)
        element<Int>("y", annotations = listOf(VarInt()), isOptional = true)
        element<Int>("z", annotations = listOf(VarInt()), isOptional = true)
        element<Float>("angle", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: TrackedWaypoint) {
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(
            descriptor,
            IDENTIFIER,
            WaypointIdentifier.serializer(),
            value.identifier,
        )
        output.encodeSerializableElement(
            descriptor,
            ICON,
            WaypointIcon.serializer(),
            value.icon,
        )
        when (value) {
            is TrackedWaypoint.Empty ->
                output.encodeIntElement(descriptor, TYPE, 0)

            is TrackedWaypoint.Position -> {
                output.encodeIntElement(descriptor, TYPE, 1)
                output.encodeIntElement(descriptor, X, value.x)
                output.encodeIntElement(descriptor, Y, value.y)
                output.encodeIntElement(descriptor, Z, value.z)
            }

            is TrackedWaypoint.Chunk -> {
                output.encodeIntElement(descriptor, TYPE, 2)
                output.encodeIntElement(descriptor, X, value.x)
                output.encodeIntElement(descriptor, Z, value.z)
            }

            is TrackedWaypoint.Azimuth -> {
                output.encodeIntElement(descriptor, TYPE, 3)
                output.encodeFloatElement(descriptor, ANGLE, value.angle)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): TrackedWaypoint {
        val input = decoder.beginStructure(descriptor)
        val identifier = input.decodeSerializableElement(
            descriptor,
            IDENTIFIER,
            WaypointIdentifier.serializer(),
        )
        val icon = input.decodeSerializableElement(
            descriptor,
            ICON,
            WaypointIcon.serializer(),
        )
        val value = when (val type = input.decodeIntElement(descriptor, TYPE)) {
            0 -> TrackedWaypoint.Empty(identifier, icon)
            1 -> TrackedWaypoint.Position(
                identifier,
                icon,
                input.decodeIntElement(descriptor, X),
                input.decodeIntElement(descriptor, Y),
                input.decodeIntElement(descriptor, Z),
            )

            2 -> TrackedWaypoint.Chunk(
                identifier,
                icon,
                input.decodeIntElement(descriptor, X),
                input.decodeIntElement(descriptor, Z),
            )

            3 -> TrackedWaypoint.Azimuth(
                identifier,
                icon,
                input.decodeFloatElement(descriptor, ANGLE),
            )

            else -> throw SerializationException("Unknown waypoint type $type")
        }
        input.endStructure(descriptor)
        return value
    }

    private const val IDENTIFIER: Int = 0
    private const val ICON: Int = 1
    private const val TYPE: Int = 2
    private const val X: Int = 3
    private const val Y: Int = 4
    private const val Z: Int = 5
    private const val ANGLE: Int = 6
}
