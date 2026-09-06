package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtIntArray
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlin.test.*
import kotlin.uuid.Uuid

class ServerComputationTest {
    @Test
    fun furnaceComputationUsesTheSameSlotsTimersAndBlockStateThatAreSaved() {
        val air = BlockState(BlockId("minecraft:air"))
        val chunkContext = ChunkContext(
            DimensionId.Overworld, DimensionTypeLayout(0, 16, 16, true, false), air, BiomeId("minecraft:plains"),
        )
        val chunk = Chunk(ChunkPosition(0, 0), chunkContext)
        val position = BlockPosition(3, 4, 5)
        val lit = StateProperty("lit", mapOf("false" to false, "true" to true))
        chunk.setBlockState(
            position, BlockState(
                BlockId("minecraft:furnace"), StateProperties(mapOf("facing" to "north", "lit" to "true")),
            )
        )
        val blockEntity = BlockEntity(BlockEntityTypeId("minecraft:furnace"))
        val slots = ItemSlots(3)
        slots[0] = ItemStack(ItemId("minecraft:raw_iron"), 2)
        blockEntity.properties[ContainerProperties.Items] = slots
        blockEntity.properties[FurnaceProperties.LitTimeRemaining] = 1
        blockEntity.properties[FurnaceProperties.LitTotalTime] = 1600
        blockEntity.properties[FurnaceProperties.CookingTimeSpent] = 199
        blockEntity.properties[FurnaceProperties.CookingTotalTime] = 200
        chunk.blockEntities[position] = blockEntity

        // The application has selected a recipe and chooses the update order for this tick.
        val properties = chunk.blockEntities.getValue(position).properties
        properties[FurnaceProperties.CookingTimeSpent] = properties.require(FurnaceProperties.CookingTimeSpent) + 1
        if (properties.require(FurnaceProperties.CookingTimeSpent) == properties.require(FurnaceProperties.CookingTotalTime)) {
            properties.require(ContainerProperties.Items)[0]!!.count--
            properties.require(ContainerProperties.Items)[2] = ItemStack(ItemId("minecraft:iron_ingot"), 1)
            properties[FurnaceProperties.CookingTimeSpent] = 0
        }
        properties[FurnaceProperties.LitTimeRemaining] = properties.require(FurnaceProperties.LitTimeRemaining) - 1
        chunk.setBlockState(position, chunk.getBlockState(position).with(lit, false))
        assertSame(slots, properties["Items"]!!.get(PropertyTypes.ItemSlots))
        assertEquals(1, slots[0]?.count)
        assertEquals(ItemId("minecraft:iron_ingot"), slots[2]?.itemId)

        val encoder = ChunkNbtEncoder(
            ChunkNbtEncoderContext(
                chunkContext.dimensionTypeLayout.chunkLayout, NbtFormat, UserGameProperties.writeMappings, 400,
                ChunkNbtMetadata(MinecraftWorldFormat.WORLD_VERSION, 400),
            )
        )
        val readMappings = NbtPropertyReadMappings(
            UserGameProperties.readMappings.fields + (
                    NbtPropertyPath(NbtPropertyScope("block_entity", "minecraft:furnace"), "Items") to
                            NbtPropertyReaders.itemSlots(3)
                    )
        )
        val decoder = ChunkNbtDecoder(ChunkNbtDecoderContext(chunkContext, NbtFormat, readMappings, 400))
        val reloaded = decoder.decodeDocument(encoder.encodeDocument(chunk)).chunk
        val reloadedProperties = reloaded.blockEntities.getValue(position).properties
        assertEquals(slots, reloadedProperties.require(ContainerProperties.Items))
        assertEquals(0, reloadedProperties.require(FurnaceProperties.CookingTimeSpent))
        assertEquals(0, reloadedProperties.require(FurnaceProperties.LitTimeRemaining))
        assertEquals(false, reloaded.getBlockState(position).properties[lit])
    }

    @Test
    fun villagerCodeCanClaimPoiRememberItAndMoveBetweenEntityChunks() {
        val chunkPosition = ChunkPosition(0, 0)
        val entityChunkContext = EntityChunkContext(DimensionId.Overworld)
        val entityChunk = EntityChunk(chunkPosition, entityChunkContext)
        val neighbor = EntityChunk(ChunkPosition(1, 0), entityChunkContext)
        val villager = Entity(
            EntityTypeId("minecraft:villager"), Uuid.fromLongs(1, 2), EntityVector3d(15.75, 4.0, 3.0),
            EntityVector3d(0.5, 0.0, 0.0), EntityRotation.ZERO, mutableListOf(),
        )
        val brain = BrainState(linkedMapOf(), null, null, null, null)
        villager.properties[UserEntityProperties.Brain] = brain
        entityChunk.rootEntities.add(villager)
        val poiChunkContext = PoiChunkContext(DimensionId.Overworld, ChunkLayout.fromBlockBounds(0, 16))
        val poiChunk = PoiChunk(chunkPosition, poiChunkContext)
        val jobSite = BlockPosition(10, 4, 3)
        val poiType = PoiType(setOf(BlockState(BlockId("minecraft:composter"))), maxTickets = 1, validRange = 1)
        val poiRecord = PoiRecord(PoiTypeId("minecraft:farmer"), poiType.maxTickets)
        poiChunk.sections[0] = PoiSection(true).apply { records[jobSite] = poiRecord }

        // Selection, claiming, navigation and scheduling belong to this application's simulation.
        val available = poiChunk.sections.values.flatMap { it.records.entries }.first { it.value.freeTickets > 0 }
        available.value.freeTickets--
        brain.memories["minecraft:job_site"] = MemorySlot(
            PropertyValue(
                PropertyTypes.Nbt, NbtCompound(
                    mapOf(
                        "dimension" to NbtString(entityChunkContext.dimensionId.toString()),
                        "pos" to NbtIntArray(intArrayOf(available.key.x, available.key.y, available.key.z)),
                    )
                )
            ),
            null,
        )
        villager.position = EntityVector3d(
            villager.position.x + villager.deltaMovement.x,
            villager.position.y + villager.deltaMovement.y,
            villager.position.z + villager.deltaMovement.z,
        )
        assertEquals(neighbor.chunkPosition, villager.chunkPosition)
        assertSame(villager, entityChunk.rootEntities.single())
        entityChunk.rootEntities.remove(villager)
        neighbor.rootEntities.add(villager)
        assertTrue(entityChunk.rootEntities.isEmpty())
        assertEquals(0, poiRecord.freeTickets)
        assertEquals(1, poiType.maxTickets)

        val entityEncoder = EntityChunkNbtEncoder(
            EntityChunkNbtEncoderContext(
                NbtFormat, UserGameProperties.writeMappings,
                EntityChunkNbtMetadata(MinecraftWorldFormat.WORLD_VERSION),
            )
        )
        val entityDecoder = EntityChunkNbtDecoder(
            EntityChunkNbtDecoderContext(
                entityChunkContext, NbtFormat, UserGameProperties.readMappings,
            )
        )
        val reloaded = entityDecoder.decodeDocument(entityEncoder.encodeDocument(neighbor)).entityChunk
        val reloadedVillager = reloaded.rootEntities.single()
        assertEquals(villager.uuid, reloadedVillager.uuid)
        assertEquals(villager.position, reloadedVillager.position)
        val memory =
            reloadedVillager.properties.require(UserEntityProperties.Brain).memories.getValue("minecraft:job_site")
        assertNull(memory.timeToLive)
        val memoryTag = UserGameProperties.writeMappings.writeValue(assertNotNull(memory.value))
        assertEquals(brain.memories.getValue("minecraft:job_site").value!!.get(PropertyTypes.Nbt), memoryTag)

        val poiEncoder = PoiChunkNbtEncoder(
            PoiChunkNbtEncoderContext(
                poiChunkContext.chunkLayout,
                NbtFormat,
                NbtPropertyWriteMappings(),
                PoiChunkNbtMetadata(MinecraftWorldFormat.WORLD_VERSION),
            )
        )
        val poiDecoder = PoiChunkNbtDecoder(
            PoiChunkNbtDecoderContext(
                poiChunkContext, chunkPosition, NbtFormat, NbtPropertyReadMappings(),
            )
        )
        val reloadedPoi = poiDecoder.decodeDocument(poiEncoder.encodeDocument(poiChunk)).poiChunk
        assertEquals(0, reloadedPoi.sections.getValue(0).records.getValue(jobSite).freeTickets)
    }
}
