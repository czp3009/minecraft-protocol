@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class ProfileProperty(
    @MaxLength(64)
    val name: String,
    @MaxLength(32_767)
    val value: String,
    @MaxLength(1_024)
    val signature: String?,
)

@Serializable
data class GameProfile(
    val id: Uuid,
    @MaxLength(16)
    val name: String,
    @MaxCollectionSize(16)
    val properties: List<ProfileProperty>,
)

@Serializable
data class KnownPack(
    @MaxLength(32_767)
    val namespace: String,
    @MaxLength(32_767)
    val id: String,
    @MaxLength(32_767)
    val version: String,
)

@Serializable
data class RegistryEntry(
    val id: Identifier,
    val data: NbtTag?,
)

@Serializable
data class TagDefinition(
    val name: Identifier,
    @VarIntElements
    val entries: List<Int>,
)

@Serializable
data class RegistryTags(
    val registry: Identifier,
    val tags: List<TagDefinition>,
)

@Serializable
data class ReportDetail(
    @MaxLength(128)
    val title: String,
    @MaxLength(4_096)
    val description: String,
)

@Serializable
enum class ChatMode {
    ENABLED,
    COMMANDS_ONLY,
    HIDDEN,
}

@Serializable
enum class MainHand {
    LEFT,
    RIGHT,
}

@Serializable
enum class InteractionHand {
    MAIN_HAND,
    OFF_HAND,
}

/** Vanilla's three-dimensional direction IDs in wire order. */
@Serializable
enum class BlockFace {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST,
}

@Serializable
enum class ParticleStatus {
    ALL,
    DECREASED,
    MINIMAL,
}

@Serializable
data class ClientInformation(
    @MaxLength(16)
    val locale: String,
    val viewDistance: Byte,
    val chatMode: ChatMode,
    val chatColors: Boolean,
    @UnsignedByte
    val displayedSkinParts: Int,
    val mainHand: MainHand,
    val enableTextFiltering: Boolean,
    val allowServerListings: Boolean,
    val particleStatus: ParticleStatus,
)

@Serializable
enum class ResourcePackResult {
    SUCCESSFULLY_DOWNLOADED,
    DECLINED,
    FAILED_TO_DOWNLOAD,
    ACCEPTED,
    DOWNLOADED,
    INVALID_URL,
    FAILED_TO_RELOAD,
    DISCARDED,
}

@Serializable
enum class BuiltInServerLinkLabel {
    @SerialName("bug_report")
    BUG_REPORT,

    @SerialName("community_guidelines")
    COMMUNITY_GUIDELINES,
    SUPPORT,
    STATUS,
    FEEDBACK,
    COMMUNITY,
    WEBSITE,
    FORUMS,
    NEWS,
    ANNOUNCEMENTS,
}

@Serializable(with = ServerLinkLabelSerializer::class)
sealed interface ServerLinkLabel {
    @Serializable
    data class BuiltIn(val value: BuiltInServerLinkLabel) : ServerLinkLabel

    @Serializable
    data class Custom(val value: TextComponent) : ServerLinkLabel
}

internal object ServerLinkLabelSerializer : KSerializer<ServerLinkLabel> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ServerLinkLabel",
    ) {
        element<Boolean>("isBuiltIn")
        element<BuiltInServerLinkLabel>(
            "builtIn",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
        element<TextComponent>("custom", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ServerLinkLabel) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is ServerLinkLabel.BuiltIn -> {
                output.encodeBooleanElement(descriptor, 0, true)
                output.encodeSerializableElement(
                    descriptor,
                    1,
                    BuiltInServerLinkLabel.serializer(),
                    value.value,
                )
            }

            is ServerLinkLabel.Custom -> {
                output.encodeBooleanElement(descriptor, 0, false)
                output.encodeSerializableElement(
                    descriptor,
                    2,
                    TextComponent.serializer(),
                    value.value,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ServerLinkLabel {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val result = if (input.decodeBooleanElement(descriptor, 0)) {
                ServerLinkLabel.BuiltIn(
                    input.decodeSerializableElement(
                        descriptor,
                        1,
                        BuiltInServerLinkLabel.serializer(),
                    ),
                )
            } else {
                ServerLinkLabel.Custom(
                    input.decodeSerializableElement(
                        descriptor,
                        2,
                        TextComponent.serializer(),
                    ),
                )
            }
            input.endStructure(descriptor)
            return result
        }

        var isBuiltIn: Boolean? = null
        var builtIn: BuiltInServerLinkLabel? = null
        var custom: TextComponent? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                0 -> isBuiltIn = input.decodeBooleanElement(descriptor, 0)
                1 -> builtIn = input.decodeSerializableElement(
                    descriptor,
                    1,
                    BuiltInServerLinkLabel.serializer(),
                )

                2 -> custom = input.decodeSerializableElement(
                    descriptor,
                    2,
                    TextComponent.serializer(),
                )

                -1 -> break
                else -> throw SerializationException("Unexpected ServerLinkLabel field $index")
            }
        }
        input.endStructure(descriptor)
        return when (isBuiltIn) {
            true -> ServerLinkLabel.BuiltIn(
                builtIn ?: throw SerializationException("Missing builtIn label"),
            )

            false -> ServerLinkLabel.Custom(
                custom ?: throw SerializationException("Missing custom label"),
            )

            null -> throw SerializationException("Missing isBuiltIn discriminator")
        }
    }
}

@Serializable
data class ServerLink(
    val label: ServerLinkLabel,
    val url: String,
)
