package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlin.uuid.Uuid

internal data class NamedNetworkTypeSample<T>(
    val name: String,
    val wireName: String,
    val value: T,
)

internal fun particleRegistrySamples(): List<NamedNetworkTypeSample<ParticleOptions>> =
    ParticleType.entries.map { type ->
        NamedNetworkTypeSample(
            name = type.name.lowercase(),
            wireName = type.wireName,
            value = particleSample(type),
        )
    }

internal fun additionalParticleBranchSamples():
        List<NamedNetworkTypeSample<ParticleOptions>> =
    listOf(
        NamedNetworkTypeSample(
            name = "vibration-entity-source",
            wireName = ParticleType.VIBRATION.wireName,
            value = ParticleOptions.Vibration(
                PositionSource.Entity(entityId = 1, yOffset = 0.5f),
                arrivalInTicks = 1,
            ),
        ),
    )

internal fun commandParserRegistrySamples():
        List<NamedNetworkTypeSample<CommandParser>> =
    buildList {
        SimpleCommandParser.entries.forEach { type ->
            add(
                NamedNetworkTypeSample(
                    name = type.name.lowercase(),
                    wireName = type.wireName,
                    value = CommandParser.Simple(type),
                ),
            )
        }
        RegistryCommandParser.entries.forEach { type ->
            add(
                NamedNetworkTypeSample(
                    name = type.name.lowercase(),
                    wireName = type.wireName,
                    value = CommandParser.Registry(
                        type,
                        Identifier("minecraft:block"),
                    ),
                ),
            )
        }
        add(
            NamedNetworkTypeSample(
                "float",
                "brigadier:float",
                CommandParser.FloatRange(),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "double",
                "brigadier:double",
                CommandParser.DoubleRange(),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "integer",
                "brigadier:integer",
                CommandParser.IntegerRange(),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "long",
                "brigadier:long",
                CommandParser.LongRange(),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "string",
                "brigadier:string",
                CommandParser.StringValue(CommandStringBehavior.SINGLE_WORD),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "entity",
                "minecraft:entity",
                CommandParser.Entity(single = false, playersOnly = false),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "score_holder",
                "minecraft:score_holder",
                CommandParser.ScoreHolder(allowsMultiple = false),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "time",
                "minecraft:time",
                CommandParser.Time(0),
            ),
        )
    }

internal fun consumeEffectRegistrySamples():
        List<NamedNetworkTypeSample<ConsumeEffect>> =
    listOf(
        NamedNetworkTypeSample(
            "apply_effects",
            "minecraft:apply_effects",
            ConsumeEffect.ApplyStatusEffects(emptyList(), 1.0f),
        ),
        NamedNetworkTypeSample(
            "remove_effects",
            "minecraft:remove_effects",
            ConsumeEffect.RemoveStatusEffects(
                RegistryHolderSet.Direct(emptyList()),
            ),
        ),
        NamedNetworkTypeSample(
            "clear_all_effects",
            "minecraft:clear_all_effects",
            ConsumeEffect.ClearAllStatusEffects,
        ),
        NamedNetworkTypeSample(
            "teleport_randomly",
            "minecraft:teleport_randomly",
            ConsumeEffect.TeleportRandomly(1.0f),
        ),
        NamedNetworkTypeSample(
            "play_sound",
            "minecraft:play_sound",
            ConsumeEffect.PlaySound(SoundEventHolder.Reference(0)),
        ),
    )

internal fun numberFormatRegistrySamples():
        List<NamedNetworkTypeSample<NumberFormat>> =
    listOf(
        NamedNetworkTypeSample(
            "blank",
            "minecraft:blank",
            NumberFormat.Blank,
        ),
        NamedNetworkTypeSample(
            "styled",
            "minecraft:styled",
            NumberFormat.Styled(NbtCompound(emptyMap())),
        ),
        NamedNetworkTypeSample(
            "fixed",
            "minecraft:fixed",
            NumberFormat.Fixed(TextComponent.literal("value")),
        ),
    )

internal fun slotDisplayRegistrySamples():
        List<NamedNetworkTypeSample<SlotDisplay>> {
    val empty = SlotDisplay.Empty
    return listOf(
        NamedNetworkTypeSample("empty", "minecraft:empty", empty),
        NamedNetworkTypeSample("any_fuel", "minecraft:any_fuel", SlotDisplay.AnyFuel),
        NamedNetworkTypeSample(
            "with_any_potion",
            "minecraft:with_any_potion",
            SlotDisplay.WithAnyPotion(empty),
        ),
        NamedNetworkTypeSample(
            "only_with_component",
            "minecraft:only_with_component",
            SlotDisplay.OnlyWithComponent(
                empty,
                DataComponentType.CUSTOM_DATA,
            ),
        ),
        NamedNetworkTypeSample(
            "item",
            "minecraft:item",
            SlotDisplay.Item(1),
        ),
        NamedNetworkTypeSample(
            "item_stack",
            "minecraft:item_stack",
            SlotDisplay.ItemStackValue(ItemStackTemplate(1)),
        ),
        NamedNetworkTypeSample(
            "tag",
            "minecraft:tag",
            SlotDisplay.Tag(Identifier("minecraft:planks")),
        ),
        NamedNetworkTypeSample(
            "dyed",
            "minecraft:dyed",
            SlotDisplay.Dyed(empty, empty),
        ),
        NamedNetworkTypeSample(
            "smithing_trim",
            "minecraft:smithing_trim",
            SlotDisplay.SmithingTrim(
                empty,
                empty,
                TrimPatternHolder.Reference(0),
            ),
        ),
        NamedNetworkTypeSample(
            "with_remainder",
            "minecraft:with_remainder",
            SlotDisplay.WithRemainder(empty, empty),
        ),
        NamedNetworkTypeSample(
            "composite",
            "minecraft:composite",
            SlotDisplay.Composite(emptyList()),
        ),
    )
}

internal fun recipeDisplayRegistrySamples():
        List<NamedNetworkTypeSample<RecipeDisplay>> {
    val empty = SlotDisplay.Empty
    return listOf(
        NamedNetworkTypeSample(
            "crafting_shapeless",
            "minecraft:crafting_shapeless",
            RecipeDisplay.Shapeless(emptyList(), empty, empty),
        ),
        NamedNetworkTypeSample(
            "crafting_shaped",
            "minecraft:crafting_shaped",
            RecipeDisplay.Shaped(0, 0, emptyList(), empty, empty),
        ),
        NamedNetworkTypeSample(
            "furnace",
            "minecraft:furnace",
            RecipeDisplay.Furnace(
                empty,
                empty,
                empty,
                empty,
                duration = 1,
                experience = 1.0f,
            ),
        ),
        NamedNetworkTypeSample(
            "stonecutter",
            "minecraft:stonecutter",
            RecipeDisplay.Stonecutter(empty, empty, empty),
        ),
        NamedNetworkTypeSample(
            "smithing",
            "minecraft:smithing",
            RecipeDisplay.Smithing(empty, empty, empty, empty, empty),
        ),
    )
}

internal fun entityDataValueSamples():
        List<NamedNetworkTypeSample<EntityDataValue>> {
    val position = BlockPosition(1, 2, 3)
    val component = TextComponent.literal("value")
    return buildList {
        add(NamedNetworkTypeSample("byte", "byte", EntityDataValue.ByteValue(1)))
        add(NamedNetworkTypeSample("int", "int", EntityDataValue.IntValue(1)))
        add(NamedNetworkTypeSample("long", "long", EntityDataValue.LongValue(1)))
        add(NamedNetworkTypeSample("float", "float", EntityDataValue.FloatValue(1.0f)))
        add(
            NamedNetworkTypeSample(
                "string",
                "string",
                EntityDataValue.StringValue("value"),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "component",
                "component",
                EntityDataValue.ComponentValue(component),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_component-null",
                "optional_component",
                EntityDataValue.OptionalComponent(null),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_component-value",
                "optional_component",
                EntityDataValue.OptionalComponent(component),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "item_stack",
                "item_stack",
                EntityDataValue.ItemStackValue(ItemStack.Empty),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "boolean",
                "boolean",
                EntityDataValue.BooleanValue(true),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "rotations",
                "rotations",
                EntityDataValue.Rotations(Vector3f(1.0f, 2.0f, 3.0f)),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "block_position",
                "block_position",
                EntityDataValue.BlockPositionValue(position),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_block_position-null",
                "optional_block_position",
                EntityDataValue.OptionalBlockPosition(null),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_block_position-value",
                "optional_block_position",
                EntityDataValue.OptionalBlockPosition(position),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "direction",
                "direction",
                EntityDataValue.Direction(BlockFace.NORTH),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_uuid-null",
                "optional_uuid",
                EntityDataValue.OptionalLivingEntityReference(null),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_uuid-value",
                "optional_uuid",
                EntityDataValue.OptionalLivingEntityReference(Uuid.fromLongs(1, 2)),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "block_state",
                "block_state",
                EntityDataValue.BlockState(1),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_block_state-null",
                "optional_block_state",
                EntityDataValue.OptionalBlockState(null),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_block_state-value",
                "optional_block_state",
                EntityDataValue.OptionalBlockState(1),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "particle",
                "particle",
                EntityDataValue.Particle(
                    ParticleOptions.Simple(ParticleType.ANGRY_VILLAGER),
                ),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "particles",
                "particles",
                EntityDataValue.Particles(
                    listOf(ParticleOptions.Simple(ParticleType.ANGRY_VILLAGER)),
                ),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "villager",
                "villager",
                EntityDataValue.Villager(VillagerData(0, 0, 1)),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_unsigned_int-null",
                "optional_unsigned_int",
                EntityDataValue.OptionalUnsignedInt(null),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_unsigned_int-value",
                "optional_unsigned_int",
                EntityDataValue.OptionalUnsignedInt(1),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "pose",
                "pose",
                EntityDataValue.Pose(EntityPose.STANDING),
            ),
        )
        EntityVariantRegistry.entries.forEach { registry ->
            add(
                NamedNetworkTypeSample(
                    "registry_variant-${registry.name.lowercase()}",
                    "registry_variant",
                    EntityDataValue.RegistryVariant(registry, 0),
                ),
            )
        }
        add(
            NamedNetworkTypeSample(
                "optional_global_position-null",
                "optional_global_position",
                EntityDataValue.OptionalGlobalPosition(null),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "optional_global_position-value",
                "optional_global_position",
                EntityDataValue.OptionalGlobalPosition(
                    GlobalPosition(Identifier("minecraft:overworld"), position),
                ),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "painting_variant-reference",
                "painting_variant",
                EntityDataValue.PaintingVariant(
                    PaintingVariantHolder.Reference(0),
                ),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "painting_variant-direct",
                "painting_variant",
                EntityDataValue.PaintingVariant(
                    PaintingVariantHolder.Direct(
                        PaintingVariantValue(
                            width = 1,
                            height = 1,
                            assetId = Identifier("minecraft:test"),
                        ),
                    ),
                ),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "sniffer",
                "sniffer",
                EntityDataValue.Sniffer(SnifferState.IDLING),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "armadillo",
                "armadillo",
                EntityDataValue.Armadillo(ArmadilloState.IDLE),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "copper_golem",
                "copper_golem",
                EntityDataValue.CopperGolem(CopperGolemState.IDLE),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "copper_weather",
                "copper_weather",
                EntityDataValue.CopperWeather(CopperWeatherState.UNAFFECTED),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "vector",
                "vector",
                EntityDataValue.Vector(Vector3f(1.0f, 2.0f, 3.0f)),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "quaternion",
                "quaternion",
                EntityDataValue.Quaternion(
                    Quaternionf(0.0f, 0.0f, 0.0f, 1.0f),
                ),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "profile",
                "profile",
                EntityDataValue.Profile(
                    ResolvableProfile(
                        identity = ProfileIdentity.Partial(PartialGameProfile()),
                        skin = PlayerSkinPatch(),
                    ),
                ),
            ),
        )
        add(
            NamedNetworkTypeSample(
                "arm",
                "arm",
                EntityDataValue.Arm(HumanoidArm.LEFT),
            ),
        )
    }
}

internal fun debugSubscriptionDataSamples():
        List<NamedNetworkTypeSample<DebugSubscriptionData>> {
    val position = BlockPosition(0, 0, 0)
    val boundingBox = DebugBoundingBox(position, position)
    val pathNode = DebugPathNode(
        x = 0,
        y = 0,
        z = 0,
        walkedDistance = 0.0f,
        costMalus = 0.0f,
        closed = false,
        type = DebugPathType.OPEN,
        totalCost = 0.0f,
    )
    return listOf(
        NamedNetworkTypeSample(
            name = "bee",
            wireName = DebugSubscriptionType.BEE.wireName,
            value = DebugSubscriptionData.Bee(
                hivePosition = null,
                flowerPosition = null,
                travelTicks = 0,
                blacklistedHives = emptyList(),
            ),
        ),
        NamedNetworkTypeSample(
            name = "villager_brain",
            wireName = DebugSubscriptionType.VILLAGER_BRAIN.wireName,
            value = DebugSubscriptionData.VillagerBrain(
                name = "",
                profession = "",
                experience = 0,
                health = 1.0f,
                maximumHealth = 1.0f,
                inventory = "",
                wantsGolem = false,
                angerLevel = 0,
                activities = emptyList(),
                behaviors = emptyList(),
                memories = emptyList(),
                gossips = emptyList(),
                pointsOfInterest = emptySet(),
                potentialPointsOfInterest = emptySet(),
            ),
        ),
        NamedNetworkTypeSample(
            name = "breeze",
            wireName = DebugSubscriptionType.BREEZE.wireName,
            value = DebugSubscriptionData.Breeze(
                attackTargetEntityId = null,
                jumpTarget = null,
            ),
        ),
        NamedNetworkTypeSample(
            name = "goal_selector",
            wireName = DebugSubscriptionType.GOAL_SELECTOR.wireName,
            value = DebugSubscriptionData.GoalSelector(emptyList()),
        ),
        NamedNetworkTypeSample(
            name = "entity_path",
            wireName = DebugSubscriptionType.ENTITY_PATH.wireName,
            value = DebugSubscriptionData.EntityPath(
                reached = false,
                nextNodeIndex = 0,
                target = position,
                nodes = listOf(pathNode),
                targetNodes = setOf(pathNode),
                openSet = emptyList(),
                closedSet = emptyList(),
                maximumNodeDistance = 0.0f,
            ),
        ),
        NamedNetworkTypeSample(
            name = "entity_block_intersection",
            wireName = DebugSubscriptionType.ENTITY_BLOCK_INTERSECTION.wireName,
            value = DebugSubscriptionData.EntityBlockIntersection(
                DebugEntityBlockIntersection.IN_AIR,
            ),
        ),
        NamedNetworkTypeSample(
            name = "bee_hive",
            wireName = DebugSubscriptionType.BEE_HIVE.wireName,
            value = DebugSubscriptionData.BeeHive(
                blockTypeId = 0,
                occupantCount = 0,
                honeyLevel = 0,
                sedated = false,
            ),
        ),
        NamedNetworkTypeSample(
            name = "point_of_interest",
            wireName = DebugSubscriptionType.POINT_OF_INTEREST.wireName,
            value = DebugSubscriptionData.PointOfInterest(
                position = position,
                pointOfInterestTypeId = 0,
                freeTicketCount = 0,
            ),
        ),
        NamedNetworkTypeSample(
            name = "redstone_wire_orientation",
            wireName = DebugSubscriptionType.REDSTONE_WIRE_ORIENTATION.wireName,
            value = DebugSubscriptionData.RedstoneWireOrientation(0),
        ),
        NamedNetworkTypeSample(
            name = "village_section",
            wireName = DebugSubscriptionType.VILLAGE_SECTION.wireName,
            value = DebugSubscriptionData.VillageSection,
        ),
        NamedNetworkTypeSample(
            name = "raid",
            wireName = DebugSubscriptionType.RAID.wireName,
            value = DebugSubscriptionData.Raid(emptyList()),
        ),
        NamedNetworkTypeSample(
            name = "structures",
            wireName = DebugSubscriptionType.STRUCTURE.wireName,
            value = DebugSubscriptionData.Structures(
                listOf(DebugStructure(boundingBox, emptyList())),
            ),
        ),
        NamedNetworkTypeSample(
            name = "game_event_listener",
            wireName = DebugSubscriptionType.GAME_EVENT_LISTENER.wireName,
            value = DebugSubscriptionData.GameEventListener(listenerRadius = 1),
        ),
        NamedNetworkTypeSample(
            name = "neighbor_update",
            wireName = DebugSubscriptionType.NEIGHBOR_UPDATE.wireName,
            value = DebugSubscriptionData.NeighborUpdate(position),
        ),
        NamedNetworkTypeSample(
            name = "game_event",
            wireName = DebugSubscriptionType.GAME_EVENT.wireName,
            value = DebugSubscriptionData.GameEvent(
                eventTypeId = 0,
                position = Vector3d(0.0, 0.0, 0.0),
            ),
        ),
    )
}

private fun particleSample(type: ParticleType): ParticleOptions = when (type) {
    ParticleType.BLOCK,
    ParticleType.BLOCK_MARKER,
    ParticleType.FALLING_DUST,
    ParticleType.DUST_PILLAR,
    ParticleType.BLOCK_CRUMBLE,
        -> ParticleOptions.Block(type, 1)

    ParticleType.GEYSER,
    ParticleType.GEYSER_PLUME,
        -> ParticleOptions.Geyser(type, 1)

    ParticleType.GEYSER_BASE,
    ParticleType.GEYSER_POOF,
        -> ParticleOptions.GeyserBase(type, 1, 1.0f)

    ParticleType.DRAGON_BREATH -> ParticleOptions.Power(1.0f)
    ParticleType.DUST -> ParticleOptions.Dust(0x112233, 1.0f)
    ParticleType.DUST_COLOR_TRANSITION ->
        ParticleOptions.DustTransition(0x112233, 0x445566, 1.0f)

    ParticleType.EFFECT,
    ParticleType.INSTANT_EFFECT,
        -> ParticleOptions.Spell(type, 0x112233, 1.0f)

    ParticleType.ENTITY_EFFECT,
    ParticleType.TINTED_LEAVES,
    ParticleType.FLASH,
        -> ParticleOptions.Color(type, 0x112233)

    ParticleType.SCULK_CHARGE -> ParticleOptions.SculkCharge(1.0f)
    ParticleType.ITEM -> ParticleOptions.Item(ItemStackTemplate(1))
    ParticleType.VIBRATION ->
        ParticleOptions.Vibration(
            PositionSource.Block(BlockPosition(0, 0, 0)),
            1,
        )

    ParticleType.TRAIL ->
        ParticleOptions.Trail(Vector3d(1.0, 2.0, 3.0), 0x112233, 1)

    ParticleType.SHRIEK -> ParticleOptions.Shriek(1)
    else -> ParticleOptions.Simple(type)
}
