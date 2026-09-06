package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import kotlin.test.*

class UserGamePropertiesTest {
    @Test
    fun semanticPropertyWritersRejectCyclesAcrossCallbacksButAllowSharedValuesAndRetokenizing() {
        val itemStack = ItemStack(ItemId.parse("stone"), 2)
        itemStack.properties[PropertyKey("example:nested", PropertyTypes.ItemStack)] = itemStack
        val value = PropertyValue(PropertyTypes.ItemStack, itemStack)
        assertFailsWith<IllegalArgumentException> { UserGameProperties.writeMappings.writeValue(value) }

        itemStack.properties.entries.clear()
        val slots = ItemSlots(mutableListOf(itemStack, itemStack))
        val output =
            UserGameProperties.writeMappings.writeValue(PropertyValue(PropertyTypes.ItemSlots, slots)) as NbtList
        assertEquals(2, output.size)
        assertEquals(NbtInt(2), output[1].compound()["count"])
    }

    @Test
    fun extendedGossipRemainsEditableInTheSameDynamicProperty() {
        val scope = NbtPropertyScope("entity", "minecraft:villager")
        val tag = NbtList(
            listOf(
                NbtCompound(
                    mapOf(
                        "Target" to NbtIntArray(intArrayOf(0, 0, 0, 1)), "Type" to NbtString("major_positive"),
                        "Value" to NbtInt(10), "example:source" to NbtString("gift"),
                    )
                )
            )
        )
        val properties = DataProperties()
        properties[UserEntityProperties.Gossips.name] =
            UserGameProperties.readMappings.read(scope, UserEntityProperties.Gossips.name, tag)
        val records = properties.require(PropertyKey(UserEntityProperties.Gossips.name, PropertyTypes.List))
        records.values.single().get(PropertyTypes.Properties)[PropertyKey("Value", PropertyTypes.Int)] = 15
        val output = UserGameProperties.writeMappings.write(
            scope,
            UserEntityProperties.Gossips.name,
            properties[UserEntityProperties.Gossips.name]!!
        ) as NbtList
        assertEquals(NbtInt(15), output[0].compound()["Value"])
        assertEquals(NbtString("gift"), output[0].compound()["example:source"])
    }

    @Test
    fun furnaceComputationAndDynamicEditsShareTypedInventoryAndTimerValues() {
        val scope = NbtPropertyScope("block_entity", "minecraft:furnace")
        val mappings = UserGameProperties.readMappings.copy(
            fields = UserGameProperties.readMappings.fields +
                    (NbtPropertyPath(scope, "Items") to NbtPropertyReaders.itemSlots(3))
        )
        val tag = NbtCompound(
            mapOf(
                "id" to NbtString("minecraft:furnace"), "x" to NbtInt(0), "y" to NbtInt(0), "z" to NbtInt(0),
                "lit_time_remaining" to NbtShort(5), "Items" to NbtList(
                    listOf(
                        NbtCompound(
                            mapOf(
                                "Slot" to NbtByte(0),
                                "id" to NbtString("minecraft:iron_ore"),
                                "count" to NbtInt(2),
                                "components" to NbtCompound(
                                    mapOf(
                                        "!minecraft:custom_name" to NbtCompound(emptyMap()),
                                        "example:energy" to NbtInt(7),
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val (position, blockEntity) = decodeBlockEntity(tag, mappings)
        val properties = blockEntity.properties
        val inventory = properties.require(ContainerProperties.Items)
        assertEquals(3, inventory.items.size)
        assertNull(inventory.items[2])
        val input = assertNotNull(inventory.items[0])
        assertEquals(ComponentPatchEntry.Removed, input.components.entries[ComponentId.parse("custom_name")])
        properties[FurnaceProperties.LitTimeRemaining] = properties.require(FurnaceProperties.LitTimeRemaining) - 1
        input.count -= 1
        inventory.items[2] = ItemStack(ItemId.parse("iron_ingot"), 1)
        val output = encodeBlockEntity(position, blockEntity, UserGameProperties.writeMappings)
        assertEquals(NbtShort(4), output["lit_time_remaining"])
        val items = output.requiredTag<NbtList>("Items")
        assertEquals(2, items.size)
        assertEquals(NbtInt(1), items[0].compound()["count"])
        assertEquals(NbtByte(2), items[1].compound()["Slot"])
        input.components.entries.remove(ComponentId.parse("custom_name"))
        val item = writeItemStack(input, UserGameProperties.writeMappings)
        assertNull(item.requiredTag<NbtCompound>("components")["!minecraft:custom_name"])
    }

    @Test
    fun attributeModifiersEffectsAndBrainRetainTheirDistinctPersistenceSemantics() {
        val mappings = UserGameProperties.readMappings
        val properties = mappings.readProperties(
            NbtCompound(
                mapOf(
                    "attributes" to NbtList(
                        listOf(
                            NbtCompound(
                                mapOf(
                                    "id" to NbtString("minecraft:max_health"), "base" to NbtDouble(20.0),
                                    "modifiers" to NbtList(
                                        listOf(
                                            NbtCompound(
                                                mapOf(
                                                    "id" to NbtString("example:bonus"), "amount" to NbtDouble(2.0),
                                                    "operation" to NbtString("add_value")
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    ),
                    "active_effects" to NbtList(
                        listOf(
                            NbtCompound(
                                mapOf(
                                    "id" to NbtString("minecraft:speed"), "amplifier" to NbtByte(-1),
                                    "duration" to NbtInt(-1), "show_particles" to NbtByte(0)
                                )
                            )
                        )
                    ),
                    "Brain" to NbtCompound(
                        mapOf(
                            "memories" to NbtCompound(
                                mapOf(
                                    "example:target" to NbtCompound(
                                        mapOf(
                                            "value" to NbtInt(7), "ttl" to NbtLong(12),
                                        )
                                    )
                                )
                            )
                        )
                    ),
                )
            ), NbtPropertyScope("entity", "minecraft:villager"), emptySet()
        )
        val attributes = properties.require(EntityProperties.Attributes)
        assertNull(attributes.entries[AttributeId.parse("movement_speed")])
        val health = attributes.entries.getValue(AttributeId.parse("max_health"))
        health.baseValue = 30.0
        health.modifiers[ModifierId.parse("example:temporary")] =
            AttributeModifier(5.0, AttributeOperation.ADD_VALUE, false)
        val effects = properties.require(EntityProperties.ActiveEffects)
        val effect = effects.entries.getValue(MobEffectId.parse("speed"))
        assertEquals(255, effect.amplifier)
        assertEquals(-1, effect.duration)
        assertFalse(effect.showIcon)
        val brain = properties.require(UserEntityProperties.Brain)
        val memory = brain.memories.getValue("example:target")
        memory.timeToLive = 11
        assertNull(brain.activeActivities)
        val output = UserGameProperties.writeMappings.writeProperties(
            properties,
            NbtPropertyScope("entity", "minecraft:villager"),
            emptySet()
        )
        val storedAttribute = output.getValue("attributes").list()[0].compound()
        assertEquals(NbtDouble(30.0), storedAttribute["base"])
        assertEquals(1, storedAttribute.requiredTag<NbtList>("modifiers").size)
        assertEquals(2, health.modifiers.size)
        assertEquals(
            NbtLong(11), output.getValue("Brain").compound().requiredTag<NbtCompound>("memories")
                .requiredTag<NbtCompound>("example:target")["ttl"]
        )
    }

    @Test
    fun merchantCostsRemainPredicatesAndItemComponentsRemainPatches() {
        val offer = NbtCompound(
            mapOf(
                "buy" to NbtCompound(
                    mapOf(
                        "id" to NbtString("minecraft:emerald"), "count" to NbtInt(5),
                        "components" to NbtCompound(mapOf("example:quality" to NbtString("high")))
                    )
                ),
                "sell" to NbtCompound(mapOf("id" to NbtString("minecraft:bread")))
            )
        )
        val offers =
            readMerchantOffers(NbtCompound(mapOf("Recipes" to NbtList(listOf(offer)))), UserGameProperties.readMappings)
        val merchantOffer = offers.offers.single()
        assertEquals(4, merchantOffer.maxUses)
        assertEquals(1, merchantOffer.result.count)
        assertEquals(1, merchantOffer.baseCostA.components.entries.size)
        merchantOffer.uses++
        merchantOffer.specialPriceDiff = -2
        val output =
            writeMerchantOffers(offers, UserGameProperties.writeMappings).requiredTag<NbtList>("Recipes")[0].compound()
        assertEquals(NbtInt(1), output["uses"])
        assertEquals(NbtInt(-2), output["specialPrice"])
        assertEquals(offer.requiredTag<NbtCompound>("buy"), output["buy"])
    }
}
