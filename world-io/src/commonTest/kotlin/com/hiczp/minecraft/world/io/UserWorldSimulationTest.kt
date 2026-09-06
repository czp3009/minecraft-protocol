package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtList
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.world.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.DataComponentPatch
import com.hiczp.minecraft.world.format.ItemStack
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/** Application code supplies the inventory rules, definition facts, packet projections and storage policy. */
class UserWorldSimulationTest {
    private companion object {
        val itemsKey = PropertyKey("Items", PropertyTypes.ItemSlots)
    }

    private val air = BlockState(BlockId("minecraft:air"))
    private val chest = BlockState(
        BlockId("minecraft:chest"), StateProperties(
            mapOf(
                "facing" to "north", "type" to "single", "waterlogged" to "false",
            )
        )
    )
    private val hopper = BlockState(
        BlockId("minecraft:hopper"), StateProperties(
            mapOf(
                "facing" to "east", "enabled" to "true",
            )
        )
    )
    private val plains = BiomeId("minecraft:plains")
    private val chunkContext = ChunkContext(
        DimensionId.Overworld, DimensionTypeLayout(0, 16, 16, true, false), air, plains,
    )
    private val chunkPosition = ChunkPosition(-1, 2)
    private val chestPosition = chunkPosition.block(ChunkBlockPosition(2, 4, 3))
    private val hopperPosition = chunkPosition.block(ChunkBlockPosition(1, 4, 3))
    private val diamond = ItemId("minecraft:diamond")
    private val damage = ComponentId("minecraft:damage")
    private val cooldown = PropertyKey("TransferCooldown", PropertyTypes.Int)
    private val energy = PropertyKey("example:energy", PropertyTypes.Long)
    private val packetCodecContext = PacketCodecContext(
        listOf(
            registry("worldgen/biome", plains.value),
            registry("block_entity_type", "minecraft:chest", "minecraft:hopper"),
            registry("item", diamond.value),
        ),
        listOf(air, chest, hopper).mapIndexed { index, blockState ->
            BlockStateIdMapping(index, Identifier(blockState.blockId.value), blockState.properties.toMap(), index == 0)
        },
    )
    private val nbtReadMappings = NbtPropertyReadMappings(
        mapOf(
            inventoryPath("minecraft:chest") to NbtPropertyReaders.itemSlots(27),
            inventoryPath("minecraft:hopper") to NbtPropertyReaders.itemSlots(5),
        )
    )
    private val chunkNbtDecoder = ChunkNbtDecoder(ChunkNbtDecoderContext(chunkContext, NbtFormat, nbtReadMappings, 100))
    private val chunkPacketEncoder = ChunkPacketEncoder(
        ChunkPacketEncoderContext(
            chunkContext.dimensionTypeLayout.chunkLayout, chunkContext.dimensionTypeLayout.hasSkyLight, air, plains,
            packetCodecContext,
            // These container Block Entities have no inventory in the full Chunk update tag.
            ChunkPacketWriteMappings({ null }),
            ChunkPacketRequiredDataProvider.RequirePresent,
        )
    )
    private val chunkPacketDecoder = ChunkPacketDecoder(
        ChunkPacketDecoderContext(
            chunkContext, packetCodecContext, ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
            { ChunkPacketMissingData("minecraft:full", 0, true) },
        )
    )
    private val itemStackPacketEncoder = ItemStackPacketEncoder(
        ItemStackPacketEncoderContext(
            packetCodecContext,
            ItemStackPacketWriteMappings { componentId, propertyValue, _ ->
                require(componentId == damage)
                DataComponent.Damage(propertyValue.get(PropertyTypes.Int))
            },
        )
    )
    private val itemStackPacketDecoder = ItemStackPacketDecoder(
        ItemStackPacketDecoderContext(
            packetCodecContext,
            ItemStackPacketReadMappings(
                { dataComponent, _ ->
                    PropertyValue(
                        PropertyTypes.Int,
                        assertIs<DataComponent.Damage>(dataComponent).value
                    )
                },
                { DataProperties() },
            ),
        )
    )
    private val minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
        MinecraftPacketPayloadFormatConfiguration(packetCodecContext = packetCodecContext),
    )

    @Test
    fun chestAndHopperComputationSurvivesMcaStorageAndClientSynchronization() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val store = store(fakeFileSystem)
        try {
            val initialChunk = initialChunk()
            store.writeChunk(initialChunk, encoder(lastUpdateTime = 100), Compression.ZLIB)
            assertTrue(fakeFileSystem.metadata("/world/region/r.-1.0.mca".toPath()).size!! > 8192)

            val chunk = assertNotNull(store.readChunk(chunkPosition, chunkNbtDecoder)).chunk
            val chestEntity = chunk.blockEntities.getValue(chestPosition)
            val hopperEntity = chunk.blockEntities.getValue(hopperPosition)
            val inventory = Inventory(chestEntity.properties)
            val input = Inventory(hopperEntity.properties)
            assertSame(inventory.slots, chestEntity.properties["Items"]!!.get(PropertyTypes.ItemSlots))
            assertEquals(27, inventory.slots.size)
            assertNull(inventory.slots[26])

            // A player puts in eight items, takes three, then an adjacent hopper supplies one.
            assertNull(inventory.insert(0, ItemStack(diamond, 8)))
            val taken = assertNotNull(inventory.take(0, 3))
            assertEquals(3, taken.count)
            assertEquals(5, inventory.slots[0]?.count)
            assertTrue(transferOne(input, inventory, 0, 0))
            hopperEntity.properties[cooldown] = 8
            assertEquals(6, inventory.slots[0]?.count)
            assertEquals(1, input.slots[0]?.count)

            // A full destination leaves the source unchanged; the library does not impose transfer rules.
            inventory.slots[1] = ItemStack(diamond, 64)
            assertFalse(transferOne(input, inventory, 0, 1))
            assertEquals(1, input.slots[0]?.count)
            chestEntity.properties[energy] = 12L
            inventory.slots[2] = ItemStack(
                diamond, 1, DataComponentPatch(
                    linkedMapOf(
                        damage to ComponentPatchEntry.SetValue(PropertyValue(PropertyTypes.Int, 7)),
                        ComponentId("minecraft:custom_name") to ComponentPatchEntry.Removed,
                    )
                )
            )

            store.writeChunk(chunk, encoder(lastUpdateTime = 108), Compression.ZLIB)
            val saved = assertNotNull(store.readChunkNbtDocument(chunkPosition)).root
            val savedChest = assertIs<NbtList>(saved["block_entities"]).value.map { assertIs<NbtCompound>(it) }
                .single { it["id"] == NbtString("minecraft:chest") }
            val savedItems = assertIs<NbtList>(savedChest["Items"])
            assertEquals(3, savedItems.size)
            assertEquals(NbtInt(6), assertIs<NbtCompound>(savedItems[0])["count"])
            val loaded = assertNotNull(store.readChunk(chunkPosition, chunkNbtDecoder))
            assertEquals(108, loaded.chunkNbtMetadata.lastUpdateTime)
            assertEquals(6, Inventory(loaded.chunk.blockEntities.getValue(chestPosition).properties).slots[0]?.count)
            assertEquals(8, loaded.chunk.blockEntities.getValue(hopperPosition).properties.require(cooldown))
            assertEquals(12L, loaded.chunk.blockEntities.getValue(chestPosition).properties.require(energy))

            // Counts are runtime results absent from saved Sections. The application computes them before sending.
            val terrain = assertNotNull(loaded.chunk.sections[0]?.terrain)
            assertNull(terrain.statistics.nonEmptyBlockCount)
            terrain.statistics.nonEmptyBlockCount = terrain.blockStates.count { it != air }
            terrain.statistics.fluidCount = 0
            val receivedChunkPacket = assertIs<ClientboundLevelChunkWithLightPacket>(
                transmit(chunkPacketEncoder.encode(loaded.chunk)),
            )
            val clientChunk = chunkPacketDecoder.decode(receivedChunkPacket)
            assertEquals(chest, clientChunk.getBlockState(chestPosition))
            assertEquals(hopper, clientChunk.getBlockState(hopperPosition))
            assertEquals(2, clientChunk.sections[0]?.terrain?.statistics?.nonEmptyBlockCount)
            val clientChest = clientChunk.blockEntities.getValue(chestPosition)
            assertNull(clientChest.properties[itemsKey])
            assertNull(clientChest.properties[energy])

            // An opened single chest menu contains 27 chest slots followed by 36 player inventory slots.
            val serverInventory = Inventory(loaded.chunk.blockEntities.getValue(chestPosition).properties)
            val playerInventory = ItemSlots(36)
            val contents = ClientboundContainerSetContentPacket(
                4, 1, (serverInventory.slots.items + playerInventory.items).map(itemStackPacketEncoder::encode),
                itemStackPacketEncoder.encode(null),
            )
            val receivedContents = assertIs<ClientboundContainerSetContentPacket>(transmit(contents))
            val clientMenu = ItemSlots(receivedContents.items.mapTo(mutableListOf(), itemStackPacketDecoder::decode))
            assertEquals(63, clientMenu.size)
            assertEquals(6, clientMenu[0]?.count)
            assertEquals(serverInventory.slots[2], clientMenu[2])
            assertNull(itemStackPacketDecoder.decode(receivedContents.carriedItem))

            assertEquals(6, serverInventory.take(0, 64)?.count)
            val receivedSlot = assertIs<ClientboundContainerSetSlotPacket>(
                transmit(
                    ClientboundContainerSetSlotPacket(4, 2, 0, itemStackPacketEncoder.encode(serverInventory.slots[0])),
                )
            )
            clientMenu[receivedSlot.slot.toInt()] = itemStackPacketDecoder.decode(receivedSlot.itemStack)
            assertNull(clientMenu[0])
            assertEquals(64, clientMenu[1]?.count)
            assertNull(clientChest.properties[itemsKey])

            // A client can save its current Chunk projection; absent server inventory is not invented on disk.
            store.writeChunk(clientChunk, encoder(lastUpdateTime = 109), Compression.ZLIB)
            val clientSaved = assertNotNull(store.readChunk(chunkPosition, chunkNbtDecoder)).chunk
            assertEquals(chest, clientSaved.getBlockState(chestPosition))
            assertNull(clientSaved.blockEntities.getValue(chestPosition).properties[itemsKey])
        } finally {
            store.close()
            fakeFileSystem.checkNoOpenFiles()
        }
    }

    @Test
    fun replacingPropertiesAndRemovingBlockEntitiesNeedsNoLifecycleProtocol() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val store = store(fakeFileSystem)
        try {
            val chunk = initialChunk()
            val entity = chunk.blockEntities.getValue(chestPosition)
            val view = Inventory(entity.properties)
            val replacement = ItemSlots(27)
            entity.properties["Items"] = PropertyValue(PropertyTypes.ItemSlots, replacement)
            assertSame(replacement, view.slots)
            assertNull(view.insert(4, ItemStack(diamond, 3)))
            assertEquals(3, replacement[4]?.count)

            chunk.blockEntities.remove(chestPosition)
            chunk.setBlockState(chestPosition, air)
            view.slots[4]!!.count = 9
            store.writeChunk(chunk, encoder(lastUpdateTime = 120), Compression.ZLIB)
            val loaded = assertNotNull(store.readChunk(chunkPosition, chunkNbtDecoder)).chunk
            assertFalse(chestPosition in loaded.blockEntities)
            assertEquals(air, loaded.getBlockState(chestPosition))
            assertEquals(9, view.slots[4]?.count)

            val terrain = assertNotNull(chunk.sections[0]?.terrain)
            terrain.statistics.nonEmptyBlockCount = 1
            terrain.statistics.fluidCount = 0
            val clientChunk = chunkPacketDecoder.decode(
                assertIs<ClientboundLevelChunkWithLightPacket>(
                    transmit(chunkPacketEncoder.encode(chunk)),
                )
            )
            assertFalse(chestPosition in clientChunk.blockEntities)
            assertEquals(air, clientChunk.getBlockState(chestPosition))
        } finally {
            store.close()
            fakeFileSystem.checkNoOpenFiles()
        }
    }

    private fun initialChunk(): Chunk = Chunk(chunkPosition, chunkContext).apply {
        setBlockState(chestPosition, chest)
        setBlockState(hopperPosition, hopper)
        blockEntities[chestPosition] = BlockEntity(BlockEntityTypeId("minecraft:chest")).apply {
            properties[itemsKey] = ItemSlots(27)
        }
        blockEntities[hopperPosition] = BlockEntity(BlockEntityTypeId("minecraft:hopper")).apply {
            properties[itemsKey] = ItemSlots(5).apply { this[0] = ItemStack(diamond, 2) }
            properties[cooldown] = 0
        }
    }

    private fun encoder(lastUpdateTime: Long) = ChunkNbtEncoder(
        ChunkNbtEncoderContext(
            chunkContext.dimensionTypeLayout.chunkLayout,
            NbtFormat,
            NbtPropertyWriteMappings(types = NbtPropertyWriters.types),
            100,
            ChunkNbtMetadata(MinecraftWorldFormat.WORLD_VERSION, lastUpdateTime),
        )
    )

    private fun store(fakeFileSystem: FakeFileSystem) = CoordinatedRegionStore(
        "/world/region".toPath(), fakeFileSystem,
        regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
    )

    private fun transmit(packet: ClientboundPacket): Packet {
        val encoded = MinecraftPacketRegistry.encodePayload(
            packet, ConnectionState.PLAY, PacketDirection.CLIENTBOUND, minecraftPacketPayloadFormat,
        )
        return MinecraftPacketRegistry.decodePayload(
            ConnectionState.PLAY, PacketDirection.CLIENTBOUND, encoded.packetKey.id, encoded.payload,
            minecraftPacketPayloadFormat,
        )
    }

    private fun registry(name: String, vararg values: String) = RegistryIdMap(
        Identifier(name), values.mapIndexed { index, value -> RegistryIdMapping(Identifier(value), index) },
    )

    private fun inventoryPath(type: String) = NbtPropertyPath(NbtPropertyScope("block_entity", type), "Items")

    /** A user's view retains only the property reference. This is intentionally ordinary application code. */
    private class Inventory(val properties: DataProperties) {
        val slots: ItemSlots get() = properties.require(itemsKey)

        fun insert(slot: Int, itemStack: ItemStack, maxStackSize: Int = 64): ItemStack? {
            val current = slots[slot]
            if (current != null && (current.itemId != itemStack.itemId || current.components != itemStack.components ||
                        current.properties != itemStack.properties)
            ) return itemStack
            val moved = minOf(itemStack.count, maxStackSize - (current?.count ?: 0))
            if (moved <= 0) return itemStack
            if (current == null) slots[slot] = itemStack.copy(count = moved) else current.count += moved
            return itemStack.copy(count = itemStack.count - moved).takeIf { it.count > 0 }
        }

        fun take(slot: Int, count: Int): ItemStack? {
            val itemStack = slots[slot] ?: return null
            val removed = minOf(count, itemStack.count)
            val result = itemStack.copy(count = removed)
            itemStack.count -= removed
            if (itemStack.count == 0) slots[slot] = null
            return result
        }
    }

    private fun transferOne(source: Inventory, target: Inventory, sourceSlot: Int, targetSlot: Int): Boolean {
        val taken = source.take(sourceSlot, 1) ?: return false
        val remainder = target.insert(targetSlot, taken) ?: return true
        check(source.insert(sourceSlot, remainder) == null)
        return false
    }
}
