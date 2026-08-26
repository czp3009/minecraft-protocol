package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.OptionalVarInt
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.VarIntElements
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = SlotDisplaySerializer::class)
sealed interface SlotDisplay {
    data object Empty : SlotDisplay

    data object AnyFuel : SlotDisplay

    data class WithAnyPotion(val display: SlotDisplay) : SlotDisplay

    data class OnlyWithComponent(
        val source: SlotDisplay,
        val component: DataComponentType,
    ) : SlotDisplay

    data class Item(val itemRegistryId: Int) : SlotDisplay {
        init {
            require(itemRegistryId >= 0) { "An item registry ID must be non-negative" }
        }
    }

    data class ItemStackValue(val stack: ItemStackTemplate) : SlotDisplay

    data class Tag(val tag: Identifier) : SlotDisplay

    data class Dyed(
        val dye: SlotDisplay,
        val target: SlotDisplay,
    ) : SlotDisplay

    data class SmithingTrim(
        val base: SlotDisplay,
        val material: SlotDisplay,
        val pattern: TrimPatternHolder,
    ) : SlotDisplay

    data class WithRemainder(
        val input: SlotDisplay,
        val remainder: SlotDisplay,
    ) : SlotDisplay

    data class Composite(val contents: List<SlotDisplay>) : SlotDisplay
}

@Serializable(with = RecipeDisplaySerializer::class)
sealed interface RecipeDisplay {
    data class Shapeless(
        val ingredients: List<SlotDisplay>,
        val result: SlotDisplay,
        val craftingStation: SlotDisplay,
    ) : RecipeDisplay

    data class Shaped(
        val width: Int,
        val height: Int,
        val ingredients: List<SlotDisplay>,
        val result: SlotDisplay,
        val craftingStation: SlotDisplay,
    ) : RecipeDisplay {
        init {
            require(width >= 0 && height >= 0) {
                "A shaped recipe's dimensions must be non-negative"
            }
            require(ingredients.size == width * height) {
                "A shaped recipe must contain width * height ingredients"
            }
        }
    }

    data class Furnace(
        val ingredient: SlotDisplay,
        val fuel: SlotDisplay,
        val result: SlotDisplay,
        val craftingStation: SlotDisplay,
        val duration: Int,
        val experience: Float,
    ) : RecipeDisplay

    data class Stonecutter(
        val input: SlotDisplay,
        val result: SlotDisplay,
        val craftingStation: SlotDisplay,
    ) : RecipeDisplay

    data class Smithing(
        val template: SlotDisplay,
        val base: SlotDisplay,
        val addition: SlotDisplay,
        val result: SlotDisplay,
        val craftingStation: SlotDisplay,
    ) : RecipeDisplay
}

@Serializable
data class RecipeDisplayEntry(
    @VarInt
    val id: Int,
    val display: RecipeDisplay,
    @OptionalVarInt
    val group: Int?,
    @VarInt
    val categoryId: Int,
    val craftingRequirements: List<RegistryHolderSet>?,
) {
    init {
        require(id >= 0) { "A recipe display ID must be non-negative" }
        require(group == null || group >= 0) { "A recipe group must be non-negative" }
        require(categoryId >= 0) { "A recipe category ID must be non-negative" }
    }
}

@Serializable
data class RecipeBookEntry(
    val contents: RecipeDisplayEntry,
    val flags: Byte,
) {
    val notification: Boolean
        get() = flags.toInt() and NOTIFICATION != 0

    val highlight: Boolean
        get() = flags.toInt() and HIGHLIGHT != 0

    companion object {
        const val NOTIFICATION: Int = 0x01
        const val HIGHLIGHT: Int = 0x02

        fun of(
            contents: RecipeDisplayEntry,
            notification: Boolean,
            highlight: Boolean,
        ): RecipeBookEntry = RecipeBookEntry(
            contents,
            ((if (notification) NOTIFICATION else 0) or
                    (if (highlight) HIGHLIGHT else 0)).toByte(),
        )
    }
}

@Serializable
data class RecipePropertySet(
    @VarIntElements
    val itemRegistryIds: List<Int>,
) {
    init {
        require(itemRegistryIds.all { it >= 0 }) {
            "Recipe property-set item IDs must be non-negative"
        }
    }
}

@Serializable
data class StonecutterRecipeOption(
    val input: RegistryHolderSet,
    val optionDisplay: SlotDisplay,
)

@Serializable
private data class SingleSlotPayload(val display: SlotDisplay)

@Serializable
private data class ComponentSlotPayload(
    val source: SlotDisplay,
    @VarInt
    val componentTypeId: Int,
)

@Serializable
private data class ItemSlotPayload(
    @VarInt
    val itemRegistryId: Int,
)

@Serializable
private data class ItemStackSlotPayload(val stack: ItemStackTemplate)

@Serializable
private data class TagSlotPayload(val tag: Identifier)

@Serializable
private data class PairSlotPayload(
    val first: SlotDisplay,
    val second: SlotDisplay,
)

@Serializable
private data class SmithingTrimSlotPayload(
    val base: SlotDisplay,
    val material: SlotDisplay,
    val pattern: TrimPatternHolder,
)

@Serializable
private data class CompositeSlotPayload(val contents: List<SlotDisplay>)

internal object SlotDisplaySerializer : KSerializer<SlotDisplay> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.SlotDisplay",
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element<SingleSlotPayload>("single", isOptional = true)
        element<ComponentSlotPayload>("component", isOptional = true)
        element<ItemSlotPayload>("item", isOptional = true)
        element<ItemStackSlotPayload>("itemStack", isOptional = true)
        element<TagSlotPayload>("tag", isOptional = true)
        element<PairSlotPayload>("pair", isOptional = true)
        element<SmithingTrimSlotPayload>("smithingTrim", isOptional = true)
        element<CompositeSlotPayload>("composite", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: SlotDisplay) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            SlotDisplay.Empty -> output.type(0)
            SlotDisplay.AnyFuel -> output.type(1)
            is SlotDisplay.WithAnyPotion -> output.payload(
                2,
                SINGLE,
                SingleSlotPayload.serializer(),
                SingleSlotPayload(value.display),
            )

            is SlotDisplay.OnlyWithComponent -> output.payload(
                3,
                COMPONENT,
                ComponentSlotPayload.serializer(),
                ComponentSlotPayload(value.source, value.component.protocolId),
            )

            is SlotDisplay.Item -> output.payload(
                4,
                ITEM,
                ItemSlotPayload.serializer(),
                ItemSlotPayload(value.itemRegistryId),
            )

            is SlotDisplay.ItemStackValue -> output.payload(
                5,
                ITEM_STACK,
                ItemStackSlotPayload.serializer(),
                ItemStackSlotPayload(value.stack),
            )

            is SlotDisplay.Tag -> output.payload(
                6,
                TAG,
                TagSlotPayload.serializer(),
                TagSlotPayload(value.tag),
            )

            is SlotDisplay.Dyed -> output.payload(
                7,
                PAIR,
                PairSlotPayload.serializer(),
                PairSlotPayload(value.dye, value.target),
            )

            is SlotDisplay.SmithingTrim -> output.payload(
                8,
                SMITHING_TRIM,
                SmithingTrimSlotPayload.serializer(),
                SmithingTrimSlotPayload(value.base, value.material, value.pattern),
            )

            is SlotDisplay.WithRemainder -> output.payload(
                9,
                PAIR,
                PairSlotPayload.serializer(),
                PairSlotPayload(value.input, value.remainder),
            )

            is SlotDisplay.Composite -> output.payload(
                10,
                COMPOSITE,
                CompositeSlotPayload.serializer(),
                CompositeSlotPayload(value.contents),
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): SlotDisplay {
        val input = decoder.beginStructure(descriptor)
        val value = when (val type = input.decodeIntElement(descriptor, TYPE)) {
            0 -> SlotDisplay.Empty
            1 -> SlotDisplay.AnyFuel
            2 -> SlotDisplay.WithAnyPotion(
                input.payload(SINGLE, SingleSlotPayload.serializer()).display,
            )

            3 -> input.payload(
                COMPONENT,
                ComponentSlotPayload.serializer(),
            ).let {
                SlotDisplay.OnlyWithComponent(
                    it.source,
                    DataComponentType.fromProtocolId(it.componentTypeId)
                        ?: throw SerializationException(
                            "Unknown data component type ${it.componentTypeId}",
                        ),
                )
            }

            4 -> SlotDisplay.Item(
                input.payload(ITEM, ItemSlotPayload.serializer()).itemRegistryId,
            )

            5 -> SlotDisplay.ItemStackValue(
                input.payload(
                    ITEM_STACK,
                    ItemStackSlotPayload.serializer(),
                ).stack,
            )

            6 -> SlotDisplay.Tag(
                input.payload(TAG, TagSlotPayload.serializer()).tag,
            )

            7 -> input.payload(PAIR, PairSlotPayload.serializer()).let {
                SlotDisplay.Dyed(it.first, it.second)
            }

            8 -> input.payload(
                SMITHING_TRIM,
                SmithingTrimSlotPayload.serializer(),
            ).let {
                SlotDisplay.SmithingTrim(it.base, it.material, it.pattern)
            }

            9 -> input.payload(PAIR, PairSlotPayload.serializer()).let {
                SlotDisplay.WithRemainder(it.first, it.second)
            }

            10 -> SlotDisplay.Composite(
                input.payload(
                    COMPOSITE,
                    CompositeSlotPayload.serializer(),
                ).contents,
            )

            else -> throw SerializationException("Unknown slot-display type $type")
        }
        input.endStructure(descriptor)
        return value
    }

    private fun CompositeEncoder.type(type: Int) {
        encodeIntElement(descriptor, TYPE, type)
    }

    private fun <T> CompositeEncoder.payload(
        type: Int,
        index: Int,
        kSerializer: KSerializer<T>,
        value: T,
    ) {
        type(type)
        encodeSerializableElement(descriptor, index, kSerializer, value)
    }

    private fun <T> CompositeDecoder.payload(
        index: Int,
        kSerializer: KSerializer<T>,
    ): T = decodeSerializableElement(descriptor, index, kSerializer)

    private const val TYPE: Int = 0
    private const val SINGLE: Int = 1
    private const val COMPONENT: Int = 2
    private const val ITEM: Int = 3
    private const val ITEM_STACK: Int = 4
    private const val TAG: Int = 5
    private const val PAIR: Int = 6
    private const val SMITHING_TRIM: Int = 7
    private const val COMPOSITE: Int = 8
}

@Serializable
private data class ShapelessRecipePayload(
    val ingredients: List<SlotDisplay>,
    val result: SlotDisplay,
    val craftingStation: SlotDisplay,
)

@Serializable
private data class ShapedRecipePayload(
    @VarInt
    val width: Int,
    @VarInt
    val height: Int,
    val ingredients: List<SlotDisplay>,
    val result: SlotDisplay,
    val craftingStation: SlotDisplay,
)

@Serializable
private data class FurnaceRecipePayload(
    val ingredient: SlotDisplay,
    val fuel: SlotDisplay,
    val result: SlotDisplay,
    val craftingStation: SlotDisplay,
    @VarInt
    val duration: Int,
    val experience: Float,
)

@Serializable
private data class StonecutterRecipePayload(
    val input: SlotDisplay,
    val result: SlotDisplay,
    val craftingStation: SlotDisplay,
)

@Serializable
private data class SmithingRecipePayload(
    val template: SlotDisplay,
    val base: SlotDisplay,
    val addition: SlotDisplay,
    val result: SlotDisplay,
    val craftingStation: SlotDisplay,
)

internal object RecipeDisplaySerializer : KSerializer<RecipeDisplay> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.RecipeDisplay",
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element<ShapelessRecipePayload>("shapeless", isOptional = true)
        element<ShapedRecipePayload>("shaped", isOptional = true)
        element<FurnaceRecipePayload>("furnace", isOptional = true)
        element<StonecutterRecipePayload>("stonecutter", isOptional = true)
        element<SmithingRecipePayload>("smithing", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: RecipeDisplay) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is RecipeDisplay.Shapeless -> output.payload(
                0,
                SHAPELESS,
                ShapelessRecipePayload.serializer(),
                ShapelessRecipePayload(
                    value.ingredients,
                    value.result,
                    value.craftingStation,
                ),
            )

            is RecipeDisplay.Shaped -> output.payload(
                1,
                SHAPED,
                ShapedRecipePayload.serializer(),
                ShapedRecipePayload(
                    value.width,
                    value.height,
                    value.ingredients,
                    value.result,
                    value.craftingStation,
                ),
            )

            is RecipeDisplay.Furnace -> output.payload(
                2,
                FURNACE,
                FurnaceRecipePayload.serializer(),
                FurnaceRecipePayload(
                    value.ingredient,
                    value.fuel,
                    value.result,
                    value.craftingStation,
                    value.duration,
                    value.experience,
                ),
            )

            is RecipeDisplay.Stonecutter -> output.payload(
                3,
                STONECUTTER,
                StonecutterRecipePayload.serializer(),
                StonecutterRecipePayload(
                    value.input,
                    value.result,
                    value.craftingStation,
                ),
            )

            is RecipeDisplay.Smithing -> output.payload(
                4,
                SMITHING,
                SmithingRecipePayload.serializer(),
                SmithingRecipePayload(
                    value.template,
                    value.base,
                    value.addition,
                    value.result,
                    value.craftingStation,
                ),
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): RecipeDisplay {
        val input = decoder.beginStructure(descriptor)
        val value = when (val type = input.decodeIntElement(descriptor, TYPE)) {
            0 -> input.payload(
                SHAPELESS,
                ShapelessRecipePayload.serializer(),
            ).let {
                RecipeDisplay.Shapeless(
                    it.ingredients,
                    it.result,
                    it.craftingStation,
                )
            }

            1 -> input.payload(SHAPED, ShapedRecipePayload.serializer()).let {
                RecipeDisplay.Shaped(
                    it.width,
                    it.height,
                    it.ingredients,
                    it.result,
                    it.craftingStation,
                )
            }

            2 -> input.payload(FURNACE, FurnaceRecipePayload.serializer()).let {
                RecipeDisplay.Furnace(
                    it.ingredient,
                    it.fuel,
                    it.result,
                    it.craftingStation,
                    it.duration,
                    it.experience,
                )
            }

            3 -> input.payload(
                STONECUTTER,
                StonecutterRecipePayload.serializer(),
            ).let {
                RecipeDisplay.Stonecutter(
                    it.input,
                    it.result,
                    it.craftingStation,
                )
            }

            4 -> input.payload(
                SMITHING,
                SmithingRecipePayload.serializer(),
            ).let {
                RecipeDisplay.Smithing(
                    it.template,
                    it.base,
                    it.addition,
                    it.result,
                    it.craftingStation,
                )
            }

            else -> throw SerializationException("Unknown recipe-display type $type")
        }
        input.endStructure(descriptor)
        return value
    }

    private fun <T> CompositeEncoder.payload(
        type: Int,
        index: Int,
        kSerializer: KSerializer<T>,
        value: T,
    ) {
        encodeIntElement(descriptor, TYPE, type)
        encodeSerializableElement(descriptor, index, kSerializer, value)
    }

    private fun <T> CompositeDecoder.payload(
        index: Int,
        kSerializer: KSerializer<T>,
    ): T = decodeSerializableElement(descriptor, index, kSerializer)

    private const val TYPE: Int = 0
    private const val SHAPELESS: Int = 1
    private const val SHAPED: Int = 2
    private const val FURNACE: Int = 3
    private const val STONECUTTER: Int = 4
    private const val SMITHING: Int = 5
}
