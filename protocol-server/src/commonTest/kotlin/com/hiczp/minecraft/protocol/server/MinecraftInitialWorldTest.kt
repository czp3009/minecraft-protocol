package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.requireRegistryPacket
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaRegistryData
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityVector3d
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.*
import kotlin.uuid.Uuid
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

class MinecraftInitialWorldTest {
    @Test
    fun flatChunkUsesDimensionHeightPalettesHeightmapsAndLight() {
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val surfaceVanillaBlockState = VanillaRegistryData.vanillaBlockStateRegistry.default(
            Identifier("grass_block"),
        )
        val airVanillaBlockState = VanillaRegistryData.vanillaBlockStateRegistry.default(MinecraftBlockIds.AIR)
        val biomeRawId = VanillaProtocolData.requireRegistryPacket(
            Identifier("worldgen/biome"),
        ).entries.indexOfFirst { it.id == MinecraftBiomeIds.PLAINS }
        val groundY = 64

        val minecraftChunkSnapshot = MinecraftChunkSnapshot.flat(
            minecraftDimensionLayout = minecraftDimensionLayout,
            chunkX = -2,
            chunkZ = 3,
            groundY = groundY,
            surfaceBlockStateRawId = surfaceVanillaBlockState.rawId,
            biomeRawId = biomeRawId,
            airBlockStateRawId = airVanillaBlockState.rawId,
        )

        assertEquals(minecraftDimensionLayout.sectionCount, minecraftChunkSnapshot.chunkData.sections.size)
        val groundOffset = groundY - minecraftDimensionLayout.minY
        val groundSection = groundOffset / 16
        val localGroundY = groundOffset % 16
        minecraftChunkSnapshot.chunkData.sections.forEachIndexed { index, chunkSection ->
            assertEquals(if (index == groundSection) 256 else 0, chunkSection.nonAirBlockCount)
            assertEquals(0, chunkSection.fluidCount)
            if (index == groundSection) {
                val palette = assertIs<PalettedContainer.Indirect>(
                    chunkSection.blockStates,
                )
                assertEquals(4, palette.bitsPerEntry)
                assertEquals(
                    listOf(airVanillaBlockState.rawId, surfaceVanillaBlockState.rawId),
                    palette.palette,
                )
                assertEquals(
                    1,
                    packedEntry(
                        palette.data.toLongArray(),
                        palette.bitsPerEntry,
                        localGroundY * 256,
                    ),
                )
                assertEquals(
                    0,
                    packedEntry(
                        palette.data.toLongArray(),
                        palette.bitsPerEntry,
                        (localGroundY + 1) * 256,
                    ),
                )
            } else {
                assertEquals(
                    PalettedContainer.Single(airVanillaBlockState.rawId),
                    chunkSection.blockStates,
                )
            }
            assertEquals(
                PalettedContainer.Single(biomeRawId),
                chunkSection.biomes,
            )
        }

        val heightmap = minecraftChunkSnapshot.chunkData.heightmaps.getValue(
            HeightmapType.WORLD_SURFACE,
        )
        val heightBits = Int.SIZE_BITS -
                minecraftDimensionLayout.height.countLeadingZeroBits()
        assertEquals(
            groundY - minecraftDimensionLayout.minY + 1,
            packedEntry(heightmap, heightBits, 0),
        )
        assertEquals(
            groundY - minecraftDimensionLayout.minY + 1,
            packedEntry(heightmap, heightBits, 255),
        )
        assertFalse(minecraftChunkSnapshot.lightUpdateData.skyYMask[0])
        assertTrue(minecraftChunkSnapshot.lightUpdateData.skyYMask[1])
        assertTrue(minecraftChunkSnapshot.lightUpdateData.skyYMask[minecraftDimensionLayout.sectionCount])
        assertFalse(
            minecraftChunkSnapshot.lightUpdateData.skyYMask[minecraftDimensionLayout.sectionCount + 1],
        )
        assertEquals(
            minecraftDimensionLayout.sectionCount,
            minecraftChunkSnapshot.lightUpdateData.skyUpdates.size,
        )
        assertTrue(
            minecraftChunkSnapshot.lightUpdateData.skyUpdates.all {
                it.bytes.size == 2_048 &&
                        it.bytes.toByteArray().all { byte -> byte == (-1).toByte() }
            },
        )
        assertTrue(minecraftChunkSnapshot.lightUpdateData.blockUpdates.isEmpty())

        val minecraftProtocolFormat = MinecraftProtocolFormat(
            MinecraftProtocolFormat.minecraftProtocolFormatConfiguration.copy(
                protocolRegistryContext = VanillaRegistryData.protocolRegistryContext
                    .withRegistrySize(
                        ProtocolRegistryContext.BIOME_REGISTRY,
                        VanillaProtocolData.requireRegistryPacket(
                            ProtocolRegistryContext.BIOME_REGISTRY,
                        ).entries.size,
                    )
                    .withChunkSectionCount(minecraftDimensionLayout.sectionCount),
            ),
        )
        val chunkDataAndUpdateLightPacket = minecraftChunkSnapshot.packet()
        val packetPayloadBytes = minecraftProtocolFormat.encodeToByteArray(
            chunkDataAndUpdateLightPacket,
        )
        val decodedChunkDataAndUpdateLightPacket =
            minecraftProtocolFormat.decodeFromByteArray<ChunkDataAndUpdateLightPacket>(
                packetPayloadBytes,
            )

        assertEquals(chunkDataAndUpdateLightPacket.chunkX, decodedChunkDataAndUpdateLightPacket.chunkX)
        assertEquals(chunkDataAndUpdateLightPacket.chunkZ, decodedChunkDataAndUpdateLightPacket.chunkZ)
        assertEquals(
            minecraftDimensionLayout.sectionCount,
            decodedChunkDataAndUpdateLightPacket.chunkData.sections.size,
        )
        assertContentEquals(
            packetPayloadBytes,
            minecraftProtocolFormat.encodeToByteArray(
                decodedChunkDataAndUpdateLightPacket,
            ),
        )
    }

    @Test
    fun resolvesFlatChunkPalettesFromAModdedRegistryContext() {
        val airBlockId = MinecraftBlockIds.AIR
        val surfaceBlockId = Identifier("example:surface")
        val biomeId = Identifier("example:biome")
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(
                StaticRegistrySchema.BLOCK_REGISTRY to listOf(airBlockId, surfaceBlockId),
                ProtocolRegistryContext.BIOME_REGISTRY to listOf(biomeId),
            ),
            blocks = listOf(
                StaticBlockSchema(
                    airBlockId,
                    listOf(StaticBlockState(emptyMap(), true)),
                ),
                StaticBlockSchema(
                    surfaceBlockId,
                    listOf(StaticBlockState(emptyMap(), true)),
                ),
            ),
        )
        val protocolRegistryContext = staticRegistrySchema.resolve(
            RemoteRegistrySnapshot(
                listOf(
                    RemoteRegistry(
                        StaticRegistrySchema.BLOCK_REGISTRY,
                        listOf(
                            RemoteRegistryEntry(surfaceBlockId, rawId = 0),
                            RemoteRegistryEntry(airBlockId, rawId = 1),
                        ),
                    ),
                    RemoteRegistry(
                        ProtocolRegistryContext.BIOME_REGISTRY,
                        listOf(RemoteRegistryEntry(biomeId, rawId = 7)),
                    ),
                ),
            ),
        )
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )

        val minecraftChunkSnapshot = MinecraftChunkSnapshot.flat(
            protocolRegistryContext = protocolRegistryContext,
            minecraftDimensionLayout = minecraftDimensionLayout,
            chunkX = 0,
            chunkZ = 0,
            groundY = 64,
            surfaceBlockId = surfaceBlockId,
            biomeId = biomeId,
            airBlockId = airBlockId,
        )
        val groundSection = (64 - minecraftDimensionLayout.minY) / 16
        val palettedContainer = assertIs<PalettedContainer.Indirect>(
            minecraftChunkSnapshot.chunkData.sections[groundSection].blockStates,
        )

        assertEquals(listOf(1, 0), palettedContainer.palette)
        assertEquals(
            PalettedContainer.Single(7),
            minecraftChunkSnapshot.chunkData.sections[groundSection].biomes,
        )
    }

    @Test
    fun entitySnapshotResolvesVanillaTypeAndBundlesMetadata() {
        val minecraftEntitySnapshot = MinecraftEntitySnapshot(
            entityId = 17,
            uuid = Uuid.fromLongs(1, 2),
            type = Identifier("pig"),
            position = Vector3d(1.5, 65.0, -2.5),
            entityMetadata = EntityMetadata(emptyList()),
        )

        val clientboundPackets = minecraftEntitySnapshot.packets(VanillaProtocolData.completeProtocolRegistryContext)

        assertEquals(3, clientboundPackets.size)
        val spawnEntityPacket = assertIs<SpawnEntityPacket>(clientboundPackets[1])
        assertEquals(minecraftEntitySnapshot.entityId, spawnEntityPacket.entityId)
        assertEquals(
            minecraftEntitySnapshot.typeId(VanillaProtocolData.completeProtocolRegistryContext),
            spawnEntityPacket.typeId,
        )

        val remappedProtocolRegistryContext = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    ProtocolRegistryContext.ENTITY_TYPE_REGISTRY,
                    listOf(ProtocolRegistryEntry(Identifier("pig"), 42)),
                ),
            ),
            blockStates = emptyList(),
        )
        assertEquals(42, minecraftEntitySnapshot.typeId(remappedProtocolRegistryContext))
        assertEquals(
            42,
            assertIs<SpawnEntityPacket>(minecraftEntitySnapshot.packets(remappedProtocolRegistryContext)[1]).typeId,
        )
    }

    @Test
    fun entitySnapshotProducesTheCompletePairingBundleInOfficialOrder() {
        val uuid = Uuid.fromLongs(1, 2)
        val type = Identifier("pig")
        val position = Vector3d(1.5, 65.0, -2.5)
        val velocity = Vector3d(0.125, -0.25, 0.5)
        val entityMetadata = EntityMetadata(listOf(EntityMetadataEntry(0, EntityDataValue.ByteValue(1))))
        val attributes = listOf(AttributeSnapshot(3, 20.0, emptyList()))
        val equipment = listOf(EquipmentUpdate(EquipmentSlot.MAINHAND, ItemStack.of(4)))
        val passengerEntityIds = listOf(18)
        val vehiclePassengerRelation = MinecraftEntityPassengersSnapshot(16, listOf(17))
        val minecraftEntitySnapshot = MinecraftEntitySnapshot(
            17,
            uuid,
            type,
            position,
            velocity,
            10.0f,
            20.0f,
            30.0f,
            41,
            entityMetadata,
            attributes,
            equipment,
            passengerEntityIds,
            vehiclePassengerRelation,
            19,
        )

        val packets = minecraftEntitySnapshot.packets(VanillaProtocolData.completeProtocolRegistryContext)
        val clientboundBundlePacket =
            minecraftEntitySnapshot.bundle(VanillaProtocolData.completeProtocolRegistryContext)

        assertEquals(9, packets.size)
        assertEquals(packets.drop(1).dropLast(1), clientboundBundlePacket.subPackets)
        assertIs<BundleDelimiterPacket>(packets[0])
        val spawnEntityPacket = assertIs<SpawnEntityPacket>(packets[1])
        assertEquals(17, spawnEntityPacket.entityId)
        assertEquals(uuid, spawnEntityPacket.entityUuid)
        assertEquals(
            minecraftEntitySnapshot.typeId(VanillaProtocolData.completeProtocolRegistryContext),
            spawnEntityPacket.typeId
        )
        assertEquals(position.x, spawnEntityPacket.x)
        assertEquals(position.y, spawnEntityPacket.y)
        assertEquals(position.z, spawnEntityPacket.z)
        assertEquals(velocity, spawnEntityPacket.velocity)
        assertEquals(Angle.fromDegrees(10.0f), spawnEntityPacket.pitch)
        assertEquals(Angle.fromDegrees(20.0f), spawnEntityPacket.yaw)
        assertEquals(Angle.fromDegrees(30.0f), spawnEntityPacket.headYaw)
        assertEquals(41, spawnEntityPacket.data)
        assertIs<SetEntityMetadataPacket>(packets[2])
        assertIs<UpdateAttributesPacket>(packets[3])
        assertIs<SetEquipmentPacket>(packets[4])
        val ownPassengers = assertIs<SetPassengersPacket>(packets[5])
        assertEquals(17, ownPassengers.vehicleEntityId)
        assertEquals(listOf(18), ownPassengers.passengerEntityIds)
        val vehiclePassengers = assertIs<SetPassengersPacket>(packets[6])
        assertEquals(16, vehiclePassengers.vehicleEntityId)
        assertEquals(listOf(17), vehiclePassengers.passengerEntityIds)
        assertIs<LinkEntitiesPacket>(packets[7])
        assertIs<BundleDelimiterPacket>(packets[8])
    }

    @Test
    fun severalEntitiesProduceOneBundleWithConsecutivePairingSequences() {
        val pig = MinecraftEntitySnapshot(
            entityId = 17,
            uuid = Uuid.fromLongs(1, 2),
            type = Identifier("pig"),
            position = Vector3d(1.5, 65.0, -2.5),
            entityMetadata = EntityMetadata(listOf(EntityMetadataEntry(0, EntityDataValue.ByteValue(1)))),
        )
        val cow = MinecraftEntitySnapshot(
            entityId = 18,
            uuid = Uuid.fromLongs(3, 4),
            type = Identifier("cow"),
            position = Vector3d(2.5, 65.0, -1.5),
            equipment = listOf(EquipmentUpdate(EquipmentSlot.MAINHAND, ItemStack.of(4))),
        )

        val clientboundBundlePacket = listOf(pig, cow).bundle(VanillaProtocolData.completeProtocolRegistryContext)

        assertEquals(4, clientboundBundlePacket.size)
        assertEquals(
            listOf(17, 18),
            clientboundBundlePacket.subPackets.filterIsInstance<SpawnEntityPacket>().map { it.entityId })
        assertIs<SetEntityMetadataPacket>(clientboundBundlePacket.subPackets[1])
        assertIs<SetEquipmentPacket>(clientboundBundlePacket.subPackets[3])

        val entities = listOf(
            Entity(
                type = "minecraft:pig",
                uuid = pig.uuid,
                data = NbtCompound(emptyMap()),
                position = EntityVector3d(1.5, 65.0, -2.5),
            ),
            Entity(
                type = "minecraft:cow",
                uuid = cow.uuid,
                data = NbtCompound(emptyMap()),
                position = EntityVector3d(2.5, 65.0, -1.5),
            ),
        )
        val entityIds = mapOf(pig.uuid to 17, cow.uuid to 18)
        val projectedBundle = entities.toMinecraftEntityBundle(
            VanillaProtocolData.completeProtocolRegistryContext,
        ) { entity ->
            entity.toMinecraftEntitySnapshot(entityIds.getValue(entity.uuid))
        }

        assertEquals(listOf(17, 18), projectedBundle.subPackets.map { clientboundPacket ->
            assertIs<SpawnEntityPacket>(clientboundPacket).entityId
        })
    }

    @Test
    fun semanticEntityConversionDetachesRuntimeState() {
        val entity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.fromLongs(1, 2),
            data = NbtCompound(emptyMap()),
            position = EntityVector3d(1.5, 65.0, -2.5),
        )
        val metadataEntries = mutableListOf(EntityMetadataEntry(0, EntityDataValue.ByteValue(1)))
        val modifiers = mutableListOf(AttributeModifier(Identifier("test"), 1.0, AttributeModifierOperation.ADD_VALUE))
        val attributes = mutableListOf(AttributeSnapshot(3, 20.0, modifiers))
        val equipment = mutableListOf(EquipmentUpdate(EquipmentSlot.MAINHAND, ItemStack.of(4)))
        val passengerEntityIds = mutableListOf(18)
        val vehiclePassengerIds = mutableListOf(17)

        val minecraftEntitySnapshot = entity.toMinecraftEntitySnapshot(
            entityId = 17,
            entityMetadata = EntityMetadata(metadataEntries),
            attributes = attributes,
            equipment = equipment,
            passengerEntityIds = passengerEntityIds,
            vehiclePassengerRelation = MinecraftEntityPassengersSnapshot(16, vehiclePassengerIds),
        )
        entity.position = EntityVector3d.ZERO
        metadataEntries.clear()
        modifiers.clear()
        attributes.clear()
        equipment.clear()
        passengerEntityIds.clear()
        vehiclePassengerIds.clear()

        val packets = minecraftEntitySnapshot.packets(typeId = 1)
        val spawnEntityPacket = assertIs<SpawnEntityPacket>(packets[1])
        assertEquals(1.5, spawnEntityPacket.x)
        assertEquals(65.0, spawnEntityPacket.y)
        assertEquals(-2.5, spawnEntityPacket.z)
        assertEquals(1, assertIs<SetEntityMetadataPacket>(packets[2]).metadata.entries.size)
        assertEquals(1, assertIs<UpdateAttributesPacket>(packets[3]).attributes.single().modifiers.size)
        assertEquals(1, assertIs<SetEquipmentPacket>(packets[4]).updates.entries.size)
        assertEquals(listOf(18), assertIs<SetPassengersPacket>(packets[5]).passengerEntityIds)
        assertEquals(listOf(17), assertIs<SetPassengersPacket>(packets[6]).passengerEntityIds)
    }

    @Test
    fun flatChunkEnforcesWireBoundsWithoutCrossCheckingCallerRoles() {
        val overworldMinecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val netherMinecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("the_nether"),
        )
        val defaultAirBlockStateRawId =
            VanillaRegistryData.vanillaBlockStateRegistry.default(MinecraftBlockIds.AIR).rawId
        val defaultSurfaceBlockStateRawId = VanillaRegistryData.vanillaBlockStateRegistry
            .default(Identifier("grass_block"))
            .rawId
        val defaultBiomeRawId = VanillaProtocolData.requireRegistryPacket(
            Identifier("worldgen/biome"),
        ).entries.indexOfFirst { it.id == MinecraftBiomeIds.PLAINS }

        fun create(
            minecraftDimensionLayout: MinecraftDimensionLayout = overworldMinecraftDimensionLayout,
            groundY: Int = 64,
            surfaceBlockStateRawId: Int = defaultSurfaceBlockStateRawId,
            biomeRawId: Int = defaultBiomeRawId,
            airBlockStateRawId: Int = defaultAirBlockStateRawId,
            fullBrightSky: Boolean = minecraftDimensionLayout.hasSkyLight,
        ) = MinecraftChunkSnapshot.flat(
            minecraftDimensionLayout = minecraftDimensionLayout,
            chunkX = 0,
            chunkZ = 0,
            groundY = groundY,
            surfaceBlockStateRawId = surfaceBlockStateRawId,
            biomeRawId = biomeRawId,
            airBlockStateRawId = airBlockStateRawId,
            fullBrightSky = fullBrightSky,
        )

        assertFailsWith<IllegalArgumentException> {
            create(groundY = overworldMinecraftDimensionLayout.minY - 1)
        }
        assertFailsWith<IllegalArgumentException> {
            create(
                groundY = overworldMinecraftDimensionLayout.minY + overworldMinecraftDimensionLayout.height,
            )
        }
        assertFailsWith<IllegalArgumentException> { create(airBlockStateRawId = -1) }
        assertEquals(
            overworldMinecraftDimensionLayout.sectionCount,
            create(airBlockStateRawId = VanillaRegistryData.vanillaBlockStateRegistry.size)
                .chunkData.sections.size,
        )
        assertFailsWith<IllegalArgumentException> { create(surfaceBlockStateRawId = -1) }
        assertEquals(
            overworldMinecraftDimensionLayout.sectionCount,
            create(surfaceBlockStateRawId = VanillaRegistryData.vanillaBlockStateRegistry.size)
                .chunkData.sections.size,
        )
        val allAirChunk = create(surfaceBlockStateRawId = defaultAirBlockStateRawId)
        assertEquals(0, allAirChunk.chunkData.sections.sumOf(ChunkSection::nonAirBlockCount))
        assertFailsWith<IllegalArgumentException> { create(biomeRawId = -1) }
        val fullBrightNetherChunk =
            create(minecraftDimensionLayout = netherMinecraftDimensionLayout, fullBrightSky = true)
        assertEquals(netherMinecraftDimensionLayout.sectionCount, fullBrightNetherChunk.lightUpdateData.skyUpdates.size)

        listOf(
            netherMinecraftDimensionLayout.minY,
            netherMinecraftDimensionLayout.minY + netherMinecraftDimensionLayout.height - 1,
        ).forEach { groundY ->
            val minecraftChunkSnapshot = create(
                minecraftDimensionLayout = netherMinecraftDimensionLayout,
                groundY = groundY,
                fullBrightSky = false,
            )
            assertTrue(minecraftChunkSnapshot.lightUpdateData.skyYMask.words.isEmpty())
            assertTrue(minecraftChunkSnapshot.lightUpdateData.skyUpdates.isEmpty())
        }
    }

    @Test
    fun bootstrapKeepsDefaultSpawnPlayerPositionAndChunkCenterIndependent() {
        val defaultSpawnPosition = Vector3d(32.0, 70.0, -48.0)
        val playerPosition = Vector3d(-17.5, 80.0, 25.5)
        val centerChunk = ChunkPosition(9, -4)
        val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
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
        val defaultSpawn = assertIs<SetDefaultSpawnPositionPacket>(packets[1]).respawnData
        assertEquals(BlockPosition(32, 70, -48), defaultSpawn.globalPosition.position)
        assertEquals(10.0f, defaultSpawn.yaw)
        assertEquals(20.0f, defaultSpawn.pitch)
        assertIs<ClientboundPlayerAbilitiesPacket>(packets[2])
        assertIs<SetRenderDistancePacket>(packets[3])
        assertIs<SetSimulationDistancePacket>(packets[4])
        val synchronizePlayerPositionPacket = assertIs<SynchronizePlayerPositionPacket>(packets[5])
        assertEquals(playerPosition, synchronizePlayerPositionPacket.change.position)
        assertEquals(30.0f, synchronizePlayerPositionPacket.change.yaw)
        assertEquals(40.0f, synchronizePlayerPositionPacket.change.pitch)
        assertIs<GameEventPacket>(packets[6])
        val setCenterChunkPacket = assertIs<SetCenterChunkPacket>(packets[7])
        assertEquals(centerChunk.x, setCenterChunkPacket.chunkX)
        assertEquals(centerChunk.z, setCenterChunkPacket.chunkZ)

        val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
            minecraftInitialWorldBootstrap = minecraftInitialWorldBootstrap,
            chunkRadius = 0,
        )
        assertEquals(
            listOf(centerChunk.x to centerChunk.z),
            minecraftInitialWorld.chunks.map { it.chunkX to it.chunkZ })
    }

    @Test
    fun vanillaInitialWorldFactoriesNeedNoOptions() {
        val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla()
        val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(chunkRadius = 0)

        assertEquals(Difficulty.EASY, minecraftInitialWorldBootstrap.difficulty)
        assertEquals(
            MinecraftInitialWorldBootstrap.vanillaPlayerAbilities(PlayerGameMode.SURVIVAL),
            minecraftInitialWorld.minecraftInitialWorldBootstrap.playerAbilities,
        )
        assertEquals(1, minecraftInitialWorld.chunks.size)
    }

    @Test
    fun vanillaWorldConfigurationKeepsDifficultyAndEveryGameModeAbility() {
        val survivalAbilities = PlayerAbilities(
            invulnerable = false,
            flying = false,
            canFly = false,
            instantBuild = false,
            flyingSpeed = 0.05f,
            walkingSpeed = 0.1f,
        )
        val expected = mapOf(
            PlayerGameMode.SURVIVAL to survivalAbilities,
            PlayerGameMode.CREATIVE to survivalAbilities.copy(
                invulnerable = true,
                canFly = true,
                instantBuild = true,
            ),
            PlayerGameMode.ADVENTURE to survivalAbilities,
            PlayerGameMode.SPECTATOR to survivalAbilities.copy(
                invulnerable = true,
                flying = true,
                canFly = true,
            ),
        )
        expected.forEach { (gameMode, playerAbilities) ->
            assertEquals(playerAbilities, MinecraftInitialWorldBootstrap.vanillaPlayerAbilities(gameMode))
        }

        val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
            gameMode = PlayerGameMode.SPECTATOR,
            difficulty = Difficulty.HARD,
            difficultyLocked = true,
        )
        val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
            minecraftInitialWorldBootstrap = minecraftInitialWorldBootstrap,
            chunkRadius = 0,
        )

        assertEquals(Difficulty.HARD, minecraftInitialWorld.minecraftInitialWorldBootstrap.difficulty)
        assertTrue(minecraftInitialWorld.minecraftInitialWorldBootstrap.difficultyLocked)
        assertEquals(
            expected.getValue(PlayerGameMode.SPECTATOR),
            minecraftInitialWorld.minecraftInitialWorldBootstrap.playerAbilities,
        )
    }

    private fun packedEntry(
        values: LongArray,
        bitsPerEntry: Int,
        index: Int,
    ): Int {
        val entriesPerLong = Long.SIZE_BITS / bitsPerEntry
        val mask = (1L shl bitsPerEntry) - 1
        return (
                values[index / entriesPerLong] ushr
                        (index % entriesPerLong * bitsPerEntry) and mask
                ).toInt()
    }
}
