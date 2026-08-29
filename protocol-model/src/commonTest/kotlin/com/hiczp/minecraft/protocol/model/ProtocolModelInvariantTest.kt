package com.hiczp.minecraft.protocol.model

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.model.packet.BundleItemSelectedPacket
import com.hiczp.minecraft.protocol.model.packet.PlayerChatMessagePacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.*
import kotlin.uuid.Uuid

class ProtocolModelInvariantTest {
    @Test
    fun `identifiers expose one canonical parser`() {
        assertEquals(Identifier("minecraft:overworld"), Identifier.parse("overworld"))
        assertEquals(Identifier("example:moons/blue"), Identifier.parse("example:moons/blue"))
        assertEquals(
            Identifier("example:moons/blue"),
            Identifier.parse(Identifier("example:moons/blue").toString()),
        )
        assertFailsWith<IllegalArgumentException> { Identifier.parse(":overworld") }
        assertFailsWith<IllegalArgumentException> { Identifier.parse("minecraft:") }
        assertFailsWith<IllegalArgumentException> { Identifier.parse("minecraft:over:world") }
        assertFailsWith<IllegalArgumentException> { Identifier.parse("Minecraft:overworld") }
    }

    @Test
    fun `packed primitive values preserve boundaries and defensive copies`() {
        val packed = longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)
        val packedLongArray = PackedLongArray(packed)
        packed[0] = 1

        assertEquals(PackedLongArray(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)), packedLongArray)
        val exported = packedLongArray.toLongArray()
        exported[1] = 1
        assertContentEquals(
            longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE),
            packedLongArray.toLongArray(),
        )

        val sectionPositions = listOf(
            SectionPosition(0, 0, 0),
            SectionPosition(
                SectionPosition.MIN_XZ,
                SectionPosition.MIN_Y,
                SectionPosition.MIN_XZ,
            ),
            SectionPosition(
                SectionPosition.MAX_XZ,
                SectionPosition.MAX_Y,
                SectionPosition.MAX_XZ,
            ),
            SectionPosition(-1, -1, -1),
        )
        sectionPositions.forEach {
            assertEquals(it, SectionPosition.fromPacked(it.packed()))
        }

        val sectionBlockChange = SectionBlockChange(
            blockStateId = Int.MAX_VALUE ushr 12,
            localX = 15,
            localY = 15,
            localZ = 15,
        )
        assertEquals(sectionBlockChange, SectionBlockChange.fromPacked(sectionBlockChange.packed()))
        val blockEntityInfo = BlockEntityInfo.fromLocalCoordinates(15, -64, 3, 4, null)
        assertEquals(15, blockEntityInfo.localX)
        assertEquals(3, blockEntityInfo.localZ)
        assertEquals((-64).toShort(), blockEntityInfo.y)

        assertFailsWith<IllegalArgumentException> {
            SectionPosition(SectionPosition.MAX_XZ + 1, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SectionPosition(0, SectionPosition.MIN_Y - 1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SectionBlockChange(-1, 0, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SectionBlockChange(0, 16, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SectionBlockChange(0, 0, -1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SectionBlockChange(0, 0, 0, 16)
        }
        assertFailsWith<IllegalArgumentException> {
            BlockEntityInfo.fromLocalCoordinates(16, 0, 0, 0, null)
        }
    }

    @Test
    fun `chunk palette values reject every impossible intrinsic state`() {
        assertFailsWith<IllegalArgumentException> {
            BlockEntityInfo(0, 0, -1, null)
        }
        assertFailsWith<IllegalArgumentException> {
            chunkSection(nonAir = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            chunkSection(nonAir = ChunkSection.BLOCK_COUNT + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            chunkSection(fluid = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            chunkSection(fluid = ChunkSection.BLOCK_COUNT + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            PalettedContainer.Single(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            PalettedContainer.Indirect(
                bitsPerEntry = 0,
                palette = emptyList(),
                data = PackedLongArray(longArrayOf()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PalettedContainer.Indirect(
                bitsPerEntry = 32,
                palette = emptyList(),
                data = PackedLongArray(longArrayOf()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PalettedContainer.Indirect(
                bitsPerEntry = 1,
                palette = listOf(0, 1, 2),
                data = PackedLongArray(longArrayOf()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PalettedContainer.Indirect(
                bitsPerEntry = 1,
                palette = listOf(-1),
                data = PackedLongArray(longArrayOf()),
            )
        }
    }

    @Test
    fun `chat and metadata values enforce sentinel and fixed-size contracts`() {
        assertFailsWith<IllegalArgumentException> {
            PackedMessageSignature.Full(ByteString(ByteArray(255)))
        }
        assertFailsWith<IllegalArgumentException> {
            PackedMessageSignature.Cached(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            LastSeenMessagesUpdate(0, ByteString(ByteArray(2)), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            VillagerData(-1, 0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            VillagerData(0, -1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            EntityDataValue.BlockState(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            EntityDataValue.OptionalBlockState(0)
        }
        assertFailsWith<IllegalArgumentException> {
            EntityDataValue.OptionalUnsignedInt(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            EntityDataValue.RegistryVariant(EntityVariantRegistry.CAT, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            EntityMetadataEntry(255, EntityDataValue.ByteValue(0))
        }
        assertFailsWith<IllegalArgumentException> {
            EntityMetadata(
                List(256) {
                    EntityMetadataEntry(0, EntityDataValue.ByteValue(0))
                },
            )
        }
    }

    @Test
    fun `recipe models validate dimensions ids and expose canonical flags`() {
        assertFailsWith<IllegalArgumentException> {
            SlotDisplay.Item(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            RecipeDisplay.Shaped(
                width = -1,
                height = 0,
                ingredients = emptyList(),
                result = SlotDisplay.Empty,
                craftingStation = SlotDisplay.Empty,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RecipeDisplay.Shaped(
                width = 2,
                height = 2,
                ingredients = listOf(SlotDisplay.Empty),
                result = SlotDisplay.Empty,
                craftingStation = SlotDisplay.Empty,
            )
        }

        val display = RecipeDisplay.Shapeless(
            ingredients = emptyList(),
            result = SlotDisplay.Empty,
            craftingStation = SlotDisplay.Empty,
        )

        fun entry(
            id: Int = 0,
            group: Int? = null,
            categoryId: Int = 0,
        ) = RecipeDisplayEntry(
            id = id,
            display = display,
            group = group,
            categoryId = categoryId,
            craftingRequirements = null,
        )

        assertFailsWith<IllegalArgumentException> { entry(id = -1) }
        assertFailsWith<IllegalArgumentException> { entry(group = -1) }
        assertFailsWith<IllegalArgumentException> { entry(categoryId = -1) }
        assertFailsWith<IllegalArgumentException> {
            RecipePropertySet(listOf(0, -1))
        }

        for (notification in listOf(false, true)) {
            for (highlight in listOf(false, true)) {
                val recipeBookEntry = RecipeBookEntry.of(
                    entry(),
                    notification = notification,
                    highlight = highlight,
                )
                assertEquals(notification, recipeBookEntry.notification)
                assertEquals(highlight, recipeBookEntry.highlight)
            }
        }
    }

    @Test
    fun `particle variants cannot be paired with the wrong discriminator`() {
        assertFailsWith<IllegalArgumentException> {
            ParticleOptions.Simple(ParticleType.BLOCK)
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleOptions.Block(ParticleType.FLAME, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleOptions.Block(ParticleType.BLOCK, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleOptions.Geyser(ParticleType.FLAME, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleOptions.GeyserBase(ParticleType.FLAME, 0, 0.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleOptions.Spell(ParticleType.FLAME, 0, 0.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleOptions.Color(ParticleType.FLAME, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            WeightedExplosionParticle(
                ExplosionParticle(
                    particle = ParticleOptions.Simple(ParticleType.FLAME),
                    scaling = 1.0f,
                    speed = 1.0f,
                ),
                weight = -1,
            )
        }
    }

    @Test
    fun `inventory predicate and registry models reject ambiguous values`() {
        assertFailsWith<IllegalArgumentException> {
            EquipmentUpdates(emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            MerchantOffer(
                firstCost = MerchantItemCost(0, 1),
                result = ItemStack.Empty,
                outOfStock = false,
                uses = 0,
                maximumUses = 1,
                experience = 0,
                specialPriceDifference = 0,
                priceMultiplier = 0.0f,
                demand = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RegistryHolderSet.Direct(listOf(-1))
        }
        assertFailsWith<IllegalArgumentException> {
            SoundEventHolder.Reference(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            DataComponentPredicateType.Concrete(-1)
        }

        val duplicateType = DataComponentPredicateType.AnyValue(
            DataComponentType.CUSTOM_DATA,
        )
        assertFailsWith<IllegalArgumentException> {
            DataComponentMatchers(
                partial = listOf(
                    PartialDataComponentMatcher(duplicateType, NbtInt(1)),
                    PartialDataComponentMatcher(duplicateType, NbtInt(2)),
                ),
            )
        }
    }

    @Test
    fun `item component constructors reject values that cannot reach the wire`() {
        assertFailsWith<IllegalArgumentException> {
            DataComponent.Enchantments(mapOf(0 to -1))
        }
        assertFailsWith<IllegalArgumentException> {
            DataComponent.Enchantments(mapOf(0 to 256))
        }
        assertFailsWith<IllegalArgumentException> {
            DataComponent.StoredEnchantments(mapOf(0 to 256))
        }
        assertFailsWith<IllegalArgumentException> {
            DataComponent.IntangibleProjectile(
                NbtCompound(mapOf("unexpected" to NbtInt(1))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DataComponent.WrittenBookContent(
                title = WrittenBookTitle("title"),
                author = "author",
                generation = 4,
                pages = emptyList(),
                resolved = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ItemStackTemplate(itemId = 0, count = 0)
        }
    }

    @Test
    fun `display and debug values enforce bounded wire-width fields`() {
        assertFailsWith<IllegalArgumentException> {
            LightDataLayer(ByteString(ByteArray(LightDataLayer.DATA_LAYER_BYTES + 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            MapColorPatch(-1, 0, 1, 1, ByteString(byteArrayOf()))
        }
        assertFailsWith<IllegalArgumentException> {
            MapColorPatch(0, 256, 1, 1, ByteString(byteArrayOf()))
        }
        assertFailsWith<IllegalArgumentException> {
            MapColorPatch(0, 0, 0, 1, ByteString(byteArrayOf()))
        }
        assertFailsWith<IllegalArgumentException> {
            MapColorPatch(0, 0, 1, 256, ByteString(byteArrayOf()))
        }
        assertFailsWith<IllegalArgumentException> {
            DebugSubscriptionData.RedstoneWireOrientation(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            DebugSubscriptionData.RedstoneWireOrientation(48)
        }
    }

    @Test
    fun `registry holders sentinels and fixed fields reject invalid values`() {
        listOf(
            { PaintingVariantHolder.Reference(-1) },
            { InstrumentHolder.Reference(-1) },
            { JukeboxSongHolder.Reference(-1) },
            { TrimMaterialHolder.Reference(-1) },
            { TrimPatternHolder.Reference(-1) },
            { ChatTypeHolder.Reference(-1) },
            { DialogHolder.Reference(-1) },
            {
                CommonPlayerSpawnInfo(
                    dimensionTypeId = -1,
                    dimension = Identifier("overworld"),
                    seed = 0,
                    gameMode = GameMode.SURVIVAL,
                    previousGameMode = null,
                    isDebug = false,
                    isFlat = false,
                    lastDeathLocation = null,
                    portalCooldown = 0,
                    seaLevel = 63,
                )
            },
            { MapDecoration(-1, 0, 0, 0, null) },
            {
                MobEffectDetails(
                    amplifier = -1,
                    duration = 1,
                    ambient = false,
                    showParticles = true,
                    showIcon = true,
                )
            },
            {
                MobEffectDetails(
                    amplifier = 256,
                    duration = 1,
                    ambient = false,
                    showParticles = true,
                    showIcon = true,
                )
            },
            { BundleItemSelectedPacket(slotId = 0, selectedItemIndex = -2) },
            {
                PlayerChatMessagePacket(
                    globalIndex = 0,
                    sender = Uuid.fromLongs(0, 0),
                    index = 0,
                    signature = ByteString(ByteArray(255)),
                    body = PackedSignedMessageBody("", 0, 0, emptyList()),
                    unsignedContent = null,
                    filterMask = FilterMask.PassThrough,
                    chatType = BoundChatType(
                        ChatTypeHolder.Reference(0),
                        TextComponent.literal("sender"),
                        null,
                    ),
                )
            },
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { invalid() }
        }

        assertEquals(
            -1,
            BundleItemSelectedPacket(0, -1).selectedItemIndex,
        )
        assertEquals(
            255,
            MobEffectDetails(
                amplifier = 255,
                duration = 1,
                ambient = false,
                showParticles = true,
                showIcon = true,
            ).amplifier,
        )
    }

    @Test
    fun `bit set exposes unsigned bit addressing without mutable storage`() {
        val words = longArrayOf(1L, Long.MIN_VALUE)
        val bitSet = BitSet(words)
        words[0] = 0

        assertTrue(bitSet[0])
        assertTrue(bitSet[127])
        assertFalse(bitSet[1])
        assertFalse(bitSet[128])
        assertFailsWith<IllegalArgumentException> { bitSet[-1] }
    }

    private fun chunkSection(
        nonAir: Int = 0,
        fluid: Int = 0,
    ): ChunkSection = ChunkSection(
        nonAirBlockCount = nonAir,
        fluidCount = fluid,
        blockStates = PalettedContainer.Single(0),
        biomes = PalettedContainer.Single(0),
    )
}
