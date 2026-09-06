package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionContext
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormat
import com.hiczp.minecraft.protocol.world.ChunkPacketDecoder
import com.hiczp.minecraft.protocol.world.ChunkPacketDecoderContext
import com.hiczp.minecraft.protocol.world.ChunkPacketMissingData
import com.hiczp.minecraft.protocol.world.ChunkPacketReadMappings
import com.hiczp.minecraft.world.format.*
import kotlin.test.*

class MinecraftInitialWorldTest {
    @Test
    fun flatChunksUseDomainDataAndTheSharedPacketCodec() {
        val initial = testInitialWorld(chunkRadius = 0)
        val chunk = initial.chunks.single()
        val encoder = initial.chunkPacketEncoder
        val packet = encoder.encode(chunk)
        val format = MinecraftPacketPayloadFormat()
        val received = format.decodeFromByteArray(
            ClientboundLevelChunkWithLightPacket.serializer(),
            format.encodeToByteArray(ClientboundLevelChunkWithLightPacket.serializer(), packet),
        )
        assertEquals(packet, received)
        val decoder = ChunkPacketDecoder(
            ChunkPacketDecoderContext(
                chunk.chunkContext, encoder.chunkPacketEncoderContext.packetCodecContext,
                ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings()),
                { ChunkPacketMissingData("minecraft:full", 0, true) },
            )
        )
        val decoded = decoder.decode(received)
        assertEquals(chunk.sections.keys, decoded.sections.keys)
        assertEquals(BlockId("minecraft:grass_block"), decoded.getBlockState(ChunkBlockPosition(0, 64, 0)).blockId)
        assertEquals(BlockId("minecraft:air"), decoded.getBlockState(ChunkBlockPosition(0, 65, 0)).blockId)
        assertEquals(256, decoded.sections.values.sumOf { it.terrain?.statistics?.nonEmptyBlockCount ?: 0 })
        assertTrue(decoded.sections.values.all { it.lighting.blockLight?.all { light -> light == 0 } == true })
        assertTrue(decoded.sections.values.all { it.lighting.skyLight?.all { light -> light == 15 } == true })
        chunk.setBlockState(ChunkBlockPosition(1, 65, 2), chunk.getBlockState(ChunkBlockPosition(0, 64, 0)))
        val changed = decoder.decode(encoder.encode(chunk))
        assertEquals(BlockId("minecraft:grass_block"), changed.getBlockState(ChunkBlockPosition(1, 65, 2)).blockId)
        assertEquals(256, changed.sections.values.sumOf { it.terrain?.statistics?.nonEmptyBlockCount ?: 0 })
    }

    @Test
    fun customMappingsChooseSurfaceAndBiomeWithoutEnteringTheDomainValues() {
        val surface = Identifier("example:surface")
        val biome = Identifier("example:biome")
        val registry = PacketCodecContext(
            listOf(RegistryIdMap(PacketCodecContext.BIOME_REGISTRY, listOf(RegistryIdMapping(biome, 42)))),
            listOf(
                BlockStateIdMapping(0, MinecraftBlockIds.AIR, emptyMap(), true),
                BlockStateIdMapping(5001, surface, mapOf("mode" to "active"), true),
            ),
        )
        val dimension = MinecraftDimensionContext(
            DimensionId.Overworld,
            MinecraftDimensionLayout(
                Identifier("example:short"), 0,
                DimensionTypeLayout(0, 32, 32, false, false)
            ),
            registry,
        )
        val initial = testInitialWorld(
            dimension, groundY = 10, chunkRadius = 0, surfaceBlockId = surface, biomeId = biome,
        )
        val chunk = initial.chunks.single()
        assertEquals(BlockId(surface.value), chunk.getBlockState(ChunkBlockPosition(3, 10, 5)).blockId)
        assertEquals("active", chunk.getBlockState(ChunkBlockPosition(3, 10, 5)).properties["mode"])
        assertEquals(BiomeId(biome.value), chunk.getBiome(ChunkBlockPosition(3, 10, 5)))
        val packet = initial.chunkPacketEncoder.encode(chunk)
        val sections = MinecraftChunkSectionPayloadFormat(MinecraftChunkSectionPayloadFormatConfiguration(registry, 2))
            .decode(packet.chunkData.buffer)
        assertEquals(listOf(0, 5001), assertIs<PalettedContainer.Indirect>(sections[0].states).palette)
        assertEquals(PalettedContainer.Single(42), sections[0].biomes)
    }

    @Test
    fun entityBatchBuildsOneBundleFromTheCurrentReferences() {
        val registry = PacketCodecContext(
            listOf(
                RegistryIdMap(
                    PacketCodecContext.ENTITY_TYPE_REGISTRY,
                    listOf(RegistryIdMapping(Identifier("pig"), 6))
                )
            ), emptyList()
        )
        val pig = testEntity(1, "pig", EntityVector3d(0.0, 65.0, 0.0))
        val second = testEntity(2, "pig", EntityVector3d(2.0, 65.0, 0.0))
        val entities = mutableListOf(pig)
        val ids = mutableMapOf(pig.uuid to 10)
        val batch = testEntityBatch(registry, entities, ids)
        pig.properties[EntityProperties.SharedFlags] = 1
        entities.add(second)
        ids[second.uuid] = 20
        val bundle = batch.toBundle()
        assertEquals(listOf(10, 20), bundle.subPackets.filterIsInstance<ClientboundAddEntityPacket>().map { it.id })
        assertEquals(
            1, assertIs<EntityDataValue.ByteValue>(
                assertIs<ClientboundSetEntityDataPacket>(bundle.subPackets[1]).packedItems.entries.single().value,
            ).value.toInt()
        )
    }

    @Test
    fun bootstrapKeepsDefaultSpawnPlayerPositionAndChunkCenterIndependent() {
        val defaultSpawnPosition = Vector3d(32.0, 70.0, -48.0)
        val playerPosition = Vector3d(-17.5, 80.0, 25.5)
        val centerChunk = ChunkPosition(9, -4)
        val minecraftInitialWorldBootstrap = testWorldBootstrap(
            defaultSpawnPosition = defaultSpawnPosition,
            defaultSpawnYaw = 10.0f,
            defaultSpawnPitch = 20.0f,
            playerPosition = playerPosition,
            playerYaw = 30.0f,
            playerPitch = 40.0f,
            centerChunk = centerChunk,
        )

        val packets = minecraftInitialWorldBootstrap.packets()

        assertEquals(8, packets.size)
        assertIs<ClientboundChangeDifficultyPacket>(packets[0])
        val defaultSpawn = assertIs<ClientboundSetDefaultSpawnPositionPacket>(packets[1]).respawnData
        assertEquals(BlockPosition(32, 70, -48), defaultSpawn.globalPosition.position)
        assertEquals(10.0f, defaultSpawn.yaw)
        assertEquals(20.0f, defaultSpawn.pitch)
        assertIs<ClientboundPlayerAbilitiesPacket>(packets[2])
        assertIs<ClientboundSetChunkCacheRadiusPacket>(packets[3])
        assertIs<ClientboundSetSimulationDistancePacket>(packets[4])
        val clientboundPlayerPositionPacket = assertIs<ClientboundPlayerPositionPacket>(packets[5])
        assertEquals(playerPosition, clientboundPlayerPositionPacket.change.position)
        assertEquals(30.0f, clientboundPlayerPositionPacket.change.yaw)
        assertEquals(40.0f, clientboundPlayerPositionPacket.change.pitch)
        assertIs<ClientboundGameEventPacket>(packets[6])
        val clientboundSetChunkCacheCenterPacket = assertIs<ClientboundSetChunkCacheCenterPacket>(packets[7])
        assertEquals(centerChunk.x, clientboundSetChunkCacheCenterPacket.x)
        assertEquals(centerChunk.z, clientboundSetChunkCacheCenterPacket.z)

        val minecraftInitialWorld = testInitialWorld(
            minecraftInitialWorldBootstrap = minecraftInitialWorldBootstrap,
            chunkRadius = 0,
        )
        assertEquals(
            listOf(centerChunk.x to centerChunk.z),
            minecraftInitialWorld.chunks.map { it.chunkPosition.x to it.chunkPosition.z })
    }

    @Test
    fun bootstrapSendsCallerSuppliedAbilitiesAndDifficulty() {
        val abilities = PlayerAbilities(true, false, false, true, 0.07f, 0.13f)
        val bootstrap = MinecraftInitialWorldBootstrap(
            difficulty = Difficulty.HARD,
            difficultyLocked = true,
            defaultSpawn = RespawnData(GlobalPosition(Identifier("overworld"), BlockPosition(1, 70, 3)), 0f, 0f),
            playerAbilities = abilities,
            viewDistance = 2,
            simulationDistance = 1,
            playerPosition = PositionMoveRotation(Vector3d(1.5, 70.0, 3.5), Vector3d(0.0, 0.0, 0.0), 0f, 0f),
        )
        val packets = bootstrap.packets()
        assertEquals(ClientboundChangeDifficultyPacket(Difficulty.HARD, true), packets[0])
        assertSame(abilities, assertIs<ClientboundPlayerAbilitiesPacket>(packets[2]).abilities)
        assertEquals(2, assertIs<ClientboundSetChunkCacheRadiusPacket>(packets[3]).radius)
        assertEquals(1, assertIs<ClientboundSetSimulationDistancePacket>(packets[4]).simulationDistance)
    }
}
