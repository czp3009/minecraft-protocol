package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import kotlin.uuid.Uuid

/**
 * Example application content. None of these field bindings or game-specific types are published by the library.
 */
internal object UserGameProperties {
    val readMappings: NbtPropertyReadMappings = NbtPropertyReadMappings(buildMap {
        put(entityPath(EntityProperties.Attributes.name), NbtPropertyReaders.entityAttributes)
        put(entityPath(EntityProperties.ActiveEffects.name), NbtPropertyReaders.entityEffects)
        put(entityPath(EntityProperties.Equipment.name), NbtPropertyReaders.entityEquipment)
        put(entityPath(UserEntityProperties.Brain.name), reader(UserPropertyTypes.BrainState, ::readBrain))
        put(
            entityPath(UserEntityProperties.VillagerData.name),
            reader(UserPropertyTypes.VillagerData, ::readVillagerData)
        )
        put(
            entityPath(UserEntityProperties.Offers.name),
            reader(UserPropertyTypes.MerchantOffers, ::readMerchantOffers)
        )
        put(entityPath(UserEntityProperties.Gossips.name), NbtPropertyReader { tag, mappings ->
            if (tag is NbtList && tag.value.any { it is NbtCompound && it.value.keys.any { name -> name !in GOSSIP_FIELDS } }) {
                mappings.readValue(tag)
            } else {
                PropertyValue(UserPropertyTypes.Gossips, readGossips(tag, mappings))
            }
        })
        put(NbtPropertyPath(NbtPropertyScope("entity", "minecraft:item"), "Item"), NbtPropertyReaders.itemStack)
        put(
            NbtPropertyPath(NbtPropertyScope("block_entity", "minecraft:piston"), "blockState"),
            NbtPropertyReaders.blockState
        )
        FURNACE_TYPES.forEach { type ->
            FURNACE_TIMERS.forEach { name ->
                put(NbtPropertyPath(NbtPropertyScope("block_entity", type), name), reader(PropertyTypes.Int) { tag, _ ->
                    (tag as? NbtShort)?.value?.toInt()
                        ?: throw NbtPropertyFormatException("Furnace timer $name must be an NBT Short")
                })
            }
        }
    })

    val writeMappings: NbtPropertyWriteMappings = NbtPropertyWriteMappings(
        fields = buildMap {
            listOf(EntityProperties.HeadYaw.name, EntityProperties.SharedFlags.name).forEach { name ->
                put(entityPath(name), NbtPropertyWriter { _, _ -> null })
            }
            FURNACE_TYPES.forEach { type ->
                FURNACE_TIMERS.forEach { name ->
                    put(NbtPropertyPath(NbtPropertyScope("block_entity", type), name), NbtPropertyWriter { value, _ ->
                        NbtShort(value.get(PropertyTypes.Int).toShort())
                    })
                }
            }
        },
        types = NbtPropertyWriters.types + listOf(
            NbtPropertyValueWriter(UserPropertyTypes.BrainState, ::writeBrain),
            NbtPropertyValueWriter(UserPropertyTypes.VillagerData, ::writeVillagerData),
            NbtPropertyValueWriter(UserPropertyTypes.MerchantOffers, ::writeMerchantOffers),
            NbtPropertyValueWriter(UserPropertyTypes.Gossips, ::writeGossips),
        ),
    )

    private fun <T : Any> reader(
        propertyType: PropertyType<T>,
        read: (NbtTag, NbtPropertyReadMappings) -> T
    ): NbtPropertyReader =
        NbtPropertyReader { tag, mappings -> PropertyValue(propertyType, read(tag, mappings)) }
}

/** The same timer values used by a caller's computation, persisted as Shorts by AbstractFurnaceBlockEntity. */
internal object FurnaceProperties {
    val LitTimeRemaining: PropertyKey<Int> = PropertyKey("lit_time_remaining", PropertyTypes.Int)
    val LitTotalTime: PropertyKey<Int> = PropertyKey("lit_total_time", PropertyTypes.Int)
    val CookingTimeSpent: PropertyKey<Int> = PropertyKey("cooking_time_spent", PropertyTypes.Int)
    val CookingTotalTime: PropertyKey<Int> = PropertyKey("cooking_total_time", PropertyTypes.Int)
}

private fun entityPath(name: String): NbtPropertyPath = NbtPropertyPath(NbtPropertyScope("entity"), name)
private val FURNACE_TYPES = listOf("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker")
private val FURNACE_TIMERS = listOf("lit_time_remaining", "lit_total_time", "cooking_time_spent", "cooking_total_time")

internal object ContainerProperties {
    val Items = PropertyKey("Items", PropertyTypes.ItemSlots)
}

internal object UserPropertyTypes {
    val BrainState: PropertyType<BrainState> = PropertyType("brain")
    val VillagerData: PropertyType<VillagerData> = PropertyType("villager_data")
    val MerchantOffers: PropertyType<MerchantOffers> = PropertyType("merchant_offers")
    val Gossips: PropertyType<Gossips> = PropertyType("gossips")
}

internal object UserEntityProperties {
    val Brain: PropertyKey<BrainState> = PropertyKey("Brain", UserPropertyTypes.BrainState)
    val VillagerData: PropertyKey<VillagerData> = PropertyKey("VillagerData", UserPropertyTypes.VillagerData)
    val Offers: PropertyKey<MerchantOffers> = PropertyKey("Offers", UserPropertyTypes.MerchantOffers)
    val Gossips: PropertyKey<Gossips> = PropertyKey("Gossips", UserPropertyTypes.Gossips)
}

internal data class BrainState(
    var memories: MutableMap<String, MemorySlot>,
    var activeActivities: MutableSet<String>?,
    var coreActivities: MutableSet<String>?,
    var defaultActivity: String?,
    var lastScheduleUpdate: Long?,
    var properties: DataProperties = DataProperties(),
)

/** A null value is known empty. A null lifetime means no expiration; the model does not advance it. */
internal data class MemorySlot(
    var value: PropertyValue<*>?,
    var timeToLive: Long?,
    var properties: DataProperties = DataProperties(),
)

internal data class VillagerData(
    var type: String,
    var profession: String,
    var level: Int,
    var properties: DataProperties = DataProperties(),
)

internal data class MerchantOffers(
    var offers: MutableList<MerchantOffer>,
    var properties: DataProperties = DataProperties(),
)

internal data class MerchantOffer(
    var baseCostA: ItemCost,
    var costB: ItemCost?,
    var result: ItemStack,
    var uses: Int,
    var maxUses: Int,
    var rewardExp: Boolean,
    var specialPriceDiff: Int,
    var demand: Int,
    var priceMultiplier: Float,
    var xp: Int,
    var properties: DataProperties = DataProperties(),
)

/** Required component values are a predicate; they are not an ItemStack's default-component patch. */
internal data class ItemCost(
    var itemId: ItemId,
    var count: Int,
    var components: DataComponentMap,
    var properties: DataProperties = DataProperties(),
)

internal data class Gossips(var entries: MutableMap<Uuid, MutableMap<String, Int>> = linkedMapOf())
