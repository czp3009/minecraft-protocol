package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ItemStackSerializationTest {
    @Test
    fun `data component protocol ids are ordinal and reversible`() {
        DataComponentType.entries.forEachIndexed { protocolId, type ->
            assertEquals(type, DataComponentType.fromProtocolId(protocolId))
        }
        assertEquals(null, DataComponentType.fromProtocolId(-1))
        assertEquals(
            null,
            DataComponentType.fromProtocolId(DataComponentType.entries.size),
        )
    }

    @Test
    fun `empty and component-free item stacks match vanilla`() {
        assertStackBytes(ItemStack.Empty, "00")
        assertStackBytes(
            ItemStack.of(itemId = 300),
            "01ac020000",
        )
    }

    @Test
    fun `component patch writes additions before removals`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(DataComponent.MaxDamage(300)),
                ),
            ),
            "0101010002ac02",
        )
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    removed = setOf(DataComponentType.MAX_DAMAGE),
                ),
            ),
            "0101000102",
        )
    }

    @Test
    fun `patch uses the last operation for duplicate component types`() {
        val stack = ItemStack.of(
            itemId = 1,
            components = DataComponentPatch(
                added = listOf(
                    DataComponent.MaxDamage(1),
                    DataComponent.MaxDamage(300),
                ),
            ),
        )
        assertContentEquals(
            "0101010002ac02".hexBytes(),
            MinecraftFormat.encodeToByteArray(ItemStack.serializer(), stack),
        )

        val removalWins = ItemStack.of(
            itemId = 1,
            components = DataComponentPatch(
                added = listOf(DataComponent.MaxDamage(300)),
                removed = setOf(DataComponentType.MAX_DAMAGE),
            ),
        )
        assertContentEquals(
            "0101000102".hexBytes(),
            MinecraftFormat.encodeToByteArray(
                ItemStack.serializer(),
                removalWins,
            ),
        )
    }

    @Test
    fun `component values use their native Minecraft shapes`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.CustomData(
                            NbtCompound(mapOf("x" to NbtInt(1))),
                        ),
                    ),
                ),
            ),
            "01010100000a030001780000000100",
        )
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.Lore(
                            listOf(TextComponent(NbtString("x"))),
                        ),
                    ),
                ),
            ),
            "010101000b0108000178",
        )
    }

    @Test
    fun `holder sets preserve direct and named vanilla branches`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.DamageResistant(
                            RegistryHolderSet.Direct(listOf(1, 300)),
                        ),
                    ),
                ),
            ),
            "010101001b0301ac02",
        )
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.DamageResistant(
                            RegistryHolderSet.Named(
                                Identifier("minecraft:x"),
                            ),
                        ),
                    ),
                ),
            ),
            "010101001b000b6d696e6563726166743a78",
        )
    }

    @Test
    fun `adventure predicates retain all optional branch markers`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.CanPlaceOn(
                            AdventureModePredicate(
                                listOf(
                                    BlockPredicate(
                                        blocks = RegistryHolderSet.Direct(
                                            listOf(2),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            "010101000e0101020200000000",
        )
    }

    @Test
    fun `consumable dispatch and sound holders match vanilla`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.Consumable(
                            consumeSeconds = 1.0f,
                            animation = ItemUseAnimation.EAT,
                            sound = SoundEventHolder.Reference(300),
                            hasConsumeParticles = true,
                            onConsumeEffects = listOf(
                                ConsumeEffect.PlaySound(
                                    SoundEventHolder.Direct(
                                        Identifier("minecraft:x"),
                                        fixedRange = 1.0f,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            "01010100183f80000001ad0201010400" +
                    "0b6d696e6563726166743a78013f800000",
        )
    }

    @Test
    fun `container values use nullable templates rather than item stacks`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.Container(
                            listOf(
                                null,
                                ItemStackTemplate(itemId = 1),
                            ),
                        ),
                    ),
                ),
            ),
            "010101004b02000101010000",
        )
    }

    @Test
    fun `firework colors remain fixed ints`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.FireworkExplosionValue(
                            FireworkExplosion(
                                shape = FireworkExplosionShape.STAR,
                                colors = listOf(0x11223344),
                                fadeColors = emptyList(),
                                hasTrail = true,
                                hasTwinkle = false,
                            ),
                        ),
                    ),
                ),
            ),
            "0101010044020111223344000100",
        )
    }

    @Test
    fun `profile partial branch includes the 26_2 skin patch`() {
        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.Profile(
                            ProfileIdentity.Partial(
                                PartialGameProfile(),
                            ),
                        ),
                    ),
                ),
            ),
            "01010100460000000000000000",
        )
    }

    @Test
    fun `animal variant codecs retain vanilla fallback policies`() {
        val salmonMalformed = MinecraftFormat.decodeFromByteArray(
            ItemStack.serializer(),
            "01010100577f".hexBytes(),
        )
        assertEquals(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.SalmonSize(SalmonVariant.LARGE),
                    ),
                ),
            ),
            salmonMalformed,
        )
        assertContentEquals(
            "010101005702".hexBytes(),
            MinecraftFormat.encodeToByteArray(
                ItemStack.serializer(),
                salmonMalformed,
            ),
        )

        val horseMalformed = MinecraftFormat.decodeFromByteArray(
            ItemStack.serializer(),
            "0101010066ffffffff0f".hexBytes(),
        )
        assertEquals(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.HorseVariantValue(
                            HorseVariant.DARK_BROWN,
                        ),
                    ),
                ),
            ),
            horseMalformed,
        )

        assertStackBytes(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.RabbitVariantValue(
                            RabbitVariant.EVIL,
                        ),
                    ),
                ),
            ),
            "010101005d63",
        )
    }

    @Test
    fun `zero-fallback enums canonicalize malformed ids`() {
        val malformed = "010101000c7f".hexBytes()
        val decoded = MinecraftFormat.decodeFromByteArray(
            ItemStack.serializer(),
            malformed,
        )
        assertEquals(
            ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(
                        DataComponent.Rarity(
                            DataComponent.RarityValue.COMMON,
                        ),
                    ),
                ),
            ),
            decoded,
        )
        assertContentEquals(
            "010101000c00".hexBytes(),
            MinecraftFormat.encodeToByteArray(ItemStack.serializer(), decoded),
        )
    }

    @Test
    fun `non-network and unknown component ids are rejected`() {
        assertFailsWith<SerializationException> {
            MinecraftFormat.decodeFromByteArray(
                ItemStack.serializer(),
                "0101010016".hexBytes(),
            )
        }
        assertFailsWith<SerializationException> {
            MinecraftFormat.decodeFromByteArray(
                ItemStack.serializer(),
                "010101006f".hexBytes(),
            )
        }
    }

    @Test
    fun `lore enforces the official maximum`() {
        val stack = ItemStack.of(
            itemId = 1,
            components = DataComponentPatch(
                added = listOf(
                    DataComponent.Lore(
                        List(257) { TextComponent(NbtString("x")) },
                    ),
                ),
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.encodeToByteArray(ItemStack.serializer(), stack)
        }
    }

    private fun assertStackBytes(stack: ItemStack, expectedHex: String) {
        val expected = expectedHex.hexBytes()
        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(ItemStack.serializer(), stack),
        )
        assertEquals(
            stack,
            MinecraftFormat.decodeFromByteArray(ItemStack.serializer(), expected),
        )
    }
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
