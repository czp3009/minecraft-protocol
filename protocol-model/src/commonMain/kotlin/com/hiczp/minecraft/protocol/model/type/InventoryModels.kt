@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.VarIntElements
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
enum class ContainerInput {
    PICKUP,
    QUICK_MOVE,
    SWAP,
    CLONE,
    THROW,
    QUICK_CRAFT,
    PICKUP_ALL,
}

@Serializable
data class HashedComponent(
    @VarInt
    val typeId: Int,
    val hash: Int,
)

@Serializable
data class HashedComponentPatch(
    @MaxCollectionSize(256)
    val added: List<HashedComponent> = emptyList(),
    @MaxCollectionSize(256)
    @VarIntElements
    val removedTypeIds: Set<Int> = emptySet(),
)

@Serializable(with = HashedStackSerializer::class)
sealed interface HashedStack {
    data object Empty : HashedStack

    @Serializable
    data class Present(
        @VarInt
        val itemRegistryId: Int,
        @VarInt
        val count: Int,
        val components: HashedComponentPatch =
            HashedComponentPatch(),
    ) : HashedStack

    companion object {
        val EMPTY: HashedStack = Empty
    }
}

@Serializable
data class ChangedHashedSlot(
    val slot: Short,
    val value: HashedStack,
)

@Serializable
data class EquipmentUpdate(
    val slot: EquipmentSlot,
    val item: ItemStack,
)

@Serializable(with = EquipmentUpdatesSerializer::class)
data class EquipmentUpdates(
    val entries: List<EquipmentUpdate>,
) {
    init {
        require(entries.isNotEmpty()) {
            "An equipment update must contain at least one slot"
        }
    }
}

@Serializable
data class MerchantItemCost(
    @VarInt
    val itemRegistryId: Int,
    @VarInt
    val count: Int,
    val exactComponents: List<DataComponent> = emptyList(),
)

@Serializable
data class MerchantOffer(
    val firstCost: MerchantItemCost,
    val result: ItemStack,
    val secondCost: MerchantItemCost? = null,
    val outOfStock: Boolean,
    val uses: Int,
    val maximumUses: Int,
    val experience: Int,
    val specialPriceDifference: Int,
    val priceMultiplier: Float,
    val demand: Int,
) {
    init {
        require(result is ItemStack.Present) {
            "A merchant result item stack cannot be empty"
        }
    }
}

internal object HashedStackSerializer : KSerializer<HashedStack> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.HashedStack",
    ) {
        element<Boolean>("present")
        element(
            "value",
            HashedStack.Present.serializer().descriptor,
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: HashedStack) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            HashedStack.Empty ->
                output.encodeBooleanElement(descriptor, PRESENT, false)

            is HashedStack.Present -> {
                output.encodeBooleanElement(descriptor, PRESENT, true)
                output.encodeSerializableElement(
                    descriptor,
                    VALUE,
                    HashedStack.Present.serializer(),
                    value,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): HashedStack {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "HashedStack requires ordered decoding",
            )
        }
        val result = if (
            input.decodeBooleanElement(descriptor, PRESENT)
        ) {
            input.decodeSerializableElement(
                descriptor,
                VALUE,
                HashedStack.Present.serializer(),
            )
        } else {
            HashedStack.Empty
        }
        input.endStructure(descriptor)
        return result
    }

    private const val PRESENT: Int = 0
    private const val VALUE: Int = 1
}

internal object EquipmentUpdatesSerializer :
    KSerializer<EquipmentUpdates> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.EquipmentUpdates",
    ) {
        element<Byte>("slotAndContinuation")
        element("item", ItemStack.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: EquipmentUpdates) {
        val output = encoder.beginStructure(descriptor)
        value.entries.forEachIndexed { index, entry ->
            val continuation = index != value.entries.lastIndex
            val slotAndContinuation = entry.slot.ordinal or
                    if (continuation) CONTINUE_MASK else 0
            output.encodeByteElement(
                descriptor,
                SLOT_AND_CONTINUATION,
                slotAndContinuation.toByte(),
            )
            output.encodeSerializableElement(
                descriptor,
                ITEM,
                ItemStack.serializer(),
                entry.item,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): EquipmentUpdates {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "EquipmentUpdates requires ordered decoding",
            )
        }
        val entries = mutableListOf<EquipmentUpdate>()
        do {
            val slotAndContinuation = input.decodeByteElement(
                descriptor,
                SLOT_AND_CONTINUATION,
            ).toInt() and 0xFF
            val slotId = slotAndContinuation and SLOT_MASK
            val slot = EquipmentSlot.entries.getOrNull(slotId)
                ?: throw SerializationException(
                    "Unknown equipment slot ID $slotId",
                )
            entries += EquipmentUpdate(
                slot,
                input.decodeSerializableElement(
                    descriptor,
                    ITEM,
                    ItemStack.serializer(),
                ),
            )
        } while (slotAndContinuation and CONTINUE_MASK != 0)
        input.endStructure(descriptor)
        return EquipmentUpdates(entries)
    }

    private const val SLOT_AND_CONTINUATION: Int = 0
    private const val ITEM: Int = 1
    private const val CONTINUE_MASK: Int = 0x80
    private const val SLOT_MASK: Int = 0x7F
}
