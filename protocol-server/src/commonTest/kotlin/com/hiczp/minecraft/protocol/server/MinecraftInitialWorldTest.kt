package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.requireRegistry
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaStaticData
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityVector3d
import kotlin.test.*
import kotlin.uuid.Uuid
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

class MinecraftInitialWorldTest {
    @Test
    fun flatChunkUsesDimensionHeightPalettesHeightmapsAndLight() {
        val dimension = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val surface = VanillaStaticData.blockStates.default(
            Identifier("grass_block"),
        )
        val air = VanillaStaticData.blockStates.default(Identifier("air"))
        val biomeId = VanillaProtocolData.requireRegistry(
            Identifier("worldgen/biome"),
        ).entries.indexOfFirst { it.id == Identifier("plains") }
        val groundY = 64

        val chunk = MinecraftChunkSnapshot.flat(
            dimension = dimension,
            chunkX = -2,
            chunkZ = 3,
            groundY = groundY,
            surfaceBlockStateId = surface.id,
            biomeId = biomeId,
            airBlockStateId = air.id,
        )

        assertEquals(dimension.sectionCount, chunk.chunkData.sections.size)
        val groundOffset = groundY - dimension.minY
        val groundSection = groundOffset / 16
        val localGroundY = groundOffset % 16
        chunk.chunkData.sections.forEachIndexed { index, section ->
            assertEquals(if (index == groundSection) 256 else 0, section.nonAirBlockCount)
            assertEquals(0, section.fluidCount)
            if (index == groundSection) {
                val palette = assertIs<PalettedContainer.Indirect>(
                    section.blockStates,
                )
                assertEquals(4, palette.bitsPerEntry)
                assertEquals(listOf(air.id, surface.id), palette.palette)
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
                    PalettedContainer.Single(air.id),
                    section.blockStates,
                )
            }
            assertEquals(
                PalettedContainer.Single(biomeId),
                section.biomes,
            )
        }

        val heightmap = chunk.chunkData.heightmaps.getValue(
            HeightmapType.WORLD_SURFACE,
        )
        val heightBits = Int.SIZE_BITS -
                dimension.height.countLeadingZeroBits()
        assertEquals(
            groundY - dimension.minY + 1,
            packedEntry(heightmap, heightBits, 0),
        )
        assertEquals(
            groundY - dimension.minY + 1,
            packedEntry(heightmap, heightBits, 255),
        )
        assertFalse(chunk.lightData.skyYMask[0])
        assertTrue(chunk.lightData.skyYMask[1])
        assertTrue(chunk.lightData.skyYMask[dimension.sectionCount])
        assertFalse(
            chunk.lightData.skyYMask[dimension.sectionCount + 1],
        )
        assertEquals(
            dimension.sectionCount,
            chunk.lightData.skyUpdates.size,
        )
        assertTrue(
            chunk.lightData.skyUpdates.all {
                it.bytes.size == 2_048 &&
                        it.bytes.toByteArray().all { byte -> byte == (-1).toByte() }
            },
        )
        assertTrue(chunk.lightData.blockUpdates.isEmpty())

        val format = MinecraftProtocolFormat(
            MinecraftProtocolFormat.configuration.copy(
                registries = VanillaStaticData.registryContext
                    .withRegistrySize(
                        ProtocolRegistryContext.BIOME_REGISTRY,
                        VanillaProtocolData.requireRegistry(
                            ProtocolRegistryContext.BIOME_REGISTRY,
                        ).entries.size,
                    )
                    .withChunkSectionCount(dimension.sectionCount),
            ),
        )
        val packet = chunk.packet()
        val bytes = format.encodeToByteArray(
            ChunkDataAndUpdateLightPacket.serializer(),
            packet,
        )
        val decoded = format.decodeFromByteArray(
            ChunkDataAndUpdateLightPacket.serializer(),
            bytes,
        )

        assertEquals(packet.chunkX, decoded.chunkX)
        assertEquals(packet.chunkZ, decoded.chunkZ)
        assertEquals(
            dimension.sectionCount,
            decoded.chunkData.sections.size,
        )
        assertContentEquals(
            bytes,
            format.encodeToByteArray(
                ChunkDataAndUpdateLightPacket.serializer(),
                decoded,
            ),
        )
    }

    @Test
    fun resolvesFlatChunkPalettesFromAModdedRegistryContext() {
        val air = Identifier("air")
        val surface = Identifier("example:surface")
        val biome = Identifier("example:biome")
        val schema = StaticRegistrySchema(
            registries = mapOf(
                StaticRegistrySchema.BLOCK_REGISTRY to listOf(air, surface),
                ProtocolRegistryContext.BIOME_REGISTRY to listOf(biome),
            ),
            blocks = listOf(
                StaticBlockSchema(
                    air,
                    listOf(StaticBlockState(emptyMap(), true)),
                ),
                StaticBlockSchema(
                    surface,
                    listOf(StaticBlockState(emptyMap(), true)),
                ),
            ),
        )
        val context = schema.resolve(
            RemoteRegistrySnapshot(
                listOf(
                    RemoteRegistry(
                        StaticRegistrySchema.BLOCK_REGISTRY,
                        listOf(
                            RemoteRegistryEntry(surface, rawId = 0),
                            RemoteRegistryEntry(air, rawId = 1),
                        ),
                    ),
                    RemoteRegistry(
                        ProtocolRegistryContext.BIOME_REGISTRY,
                        listOf(RemoteRegistryEntry(biome, rawId = 7)),
                    ),
                ),
            ),
        )
        val dimension = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )

        val chunk = MinecraftChunkSnapshot.flat(
            registries = context,
            dimension = dimension,
            chunkX = 0,
            chunkZ = 0,
            groundY = 64,
            surfaceBlock = surface,
            biome = biome,
            airBlock = air,
        )
        val groundSection = (64 - dimension.minY) / 16
        val palette = assertIs<PalettedContainer.Indirect>(
            chunk.chunkData.sections[groundSection].blockStates,
        )

        assertEquals(listOf(1, 0), palette.palette)
        assertEquals(
            PalettedContainer.Single(7),
            chunk.chunkData.sections[groundSection].biomes,
        )
    }

    @Test
    fun entitySnapshotResolvesVanillaTypeAndBundlesMetadata() {
        val entity = MinecraftEntitySnapshot(
            entityId = 17,
            uuid = Uuid.fromLongs(1, 2),
            type = Identifier("pig"),
            position = Vector3d(1.5, 65.0, -2.5),
            metadata = EntityMetadata(emptyList()),
        )

        val packets = entity.packets(VanillaProtocolData.registryContext)

        assertEquals(3, packets.size)
        val spawn = assertIs<SpawnEntityPacket>(packets[1])
        assertEquals(entity.entityId, spawn.entityId)
        assertEquals(
            entity.typeId(VanillaProtocolData.registryContext),
            spawn.typeId,
        )

        val remapped = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    ProtocolRegistryContext.ENTITY_TYPE_REGISTRY,
                    listOf(ProtocolRegistryEntry(Identifier("pig"), 42)),
                ),
            ),
            blockStates = emptyList(),
        )
        assertEquals(42, entity.typeId(remapped))
        assertEquals(
            42,
            assertIs<SpawnEntityPacket>(entity.packets(remapped)[1]).typeId,
        )
    }

    @Test
    fun entitySnapshotProducesTheCompletePairingBundleInOfficialOrder() {
        val uuid = Uuid.fromLongs(1, 2)
        val type = Identifier("pig")
        val position = Vector3d(1.5, 65.0, -2.5)
        val velocity = Vector3d(0.125, -0.25, 0.5)
        val metadata = EntityMetadata(listOf(EntityMetadataEntry(0, EntityDataValue.ByteValue(1))))
        val attributes = listOf(AttributeSnapshot(3, 20.0, emptyList()))
        val equipment = listOf(EquipmentUpdate(EquipmentSlot.MAINHAND, ItemStack.of(4)))
        val passengerEntityIds = listOf(18)
        val vehiclePassengerRelation = MinecraftEntityPassengersSnapshot(16, listOf(17))
        val entity = MinecraftEntitySnapshot(
            17,
            uuid,
            type,
            position,
            velocity,
            10.0f,
            20.0f,
            30.0f,
            41,
            metadata,
            attributes,
            equipment,
            passengerEntityIds,
            vehiclePassengerRelation,
            19,
        )

        val packets = entity.packets(VanillaProtocolData.registryContext)
        val bundle = entity.bundle(VanillaProtocolData.registryContext)

        assertEquals(9, packets.size)
        assertEquals(packets.drop(1).dropLast(1), bundle.subPackets)
        assertIs<BundleDelimiterPacket>(packets[0])
        val spawn = assertIs<SpawnEntityPacket>(packets[1])
        assertEquals(17, spawn.entityId)
        assertEquals(uuid, spawn.entityUuid)
        assertEquals(entity.typeId(VanillaProtocolData.registryContext), spawn.typeId)
        assertEquals(position.x, spawn.x)
        assertEquals(position.y, spawn.y)
        assertEquals(position.z, spawn.z)
        assertEquals(velocity, spawn.velocity)
        assertEquals(Angle.fromDegrees(10.0f), spawn.pitch)
        assertEquals(Angle.fromDegrees(20.0f), spawn.yaw)
        assertEquals(Angle.fromDegrees(30.0f), spawn.headYaw)
        assertEquals(41, spawn.data)
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
            metadata = EntityMetadata(listOf(EntityMetadataEntry(0, EntityDataValue.ByteValue(1)))),
        )
        val cow = MinecraftEntitySnapshot(
            entityId = 18,
            uuid = Uuid.fromLongs(3, 4),
            type = Identifier("cow"),
            position = Vector3d(2.5, 65.0, -1.5),
            equipment = listOf(EquipmentUpdate(EquipmentSlot.MAINHAND, ItemStack.of(4))),
        )

        val bundle = listOf(pig, cow).bundle(VanillaProtocolData.registryContext)

        assertEquals(4, bundle.size)
        assertEquals(listOf(17, 18), bundle.subPackets.filterIsInstance<SpawnEntityPacket>().map { it.entityId })
        assertIs<SetEntityMetadataPacket>(bundle.subPackets[1])
        assertIs<SetEquipmentPacket>(bundle.subPackets[3])

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
        val projectedBundle = entities.toMinecraftEntityBundle(VanillaProtocolData.registryContext) { entity ->
            entity.toMinecraftEntitySnapshot(entityIds.getValue(entity.uuid))
        }

        assertEquals(listOf(17, 18), projectedBundle.subPackets.map { packet ->
            assertIs<SpawnEntityPacket>(packet).entityId
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

        val snapshot = entity.toMinecraftEntitySnapshot(
            entityId = 17,
            metadata = EntityMetadata(metadataEntries),
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

        val packets = snapshot.packets(typeId = 1)
        val spawn = assertIs<SpawnEntityPacket>(packets[1])
        assertEquals(1.5, spawn.x)
        assertEquals(65.0, spawn.y)
        assertEquals(-2.5, spawn.z)
        assertEquals(1, assertIs<SetEntityMetadataPacket>(packets[2]).metadata.entries.size)
        assertEquals(1, assertIs<UpdateAttributesPacket>(packets[3]).attributes.single().modifiers.size)
        assertEquals(1, assertIs<SetEquipmentPacket>(packets[4]).updates.entries.size)
        assertEquals(listOf(18), assertIs<SetPassengersPacket>(packets[5]).passengerEntityIds)
        assertEquals(listOf(17), assertIs<SetPassengersPacket>(packets[6]).passengerEntityIds)
    }

    @Test
    fun flatChunkRejectsInputsThatCannotDescribeTheDimension() {
        val overworld = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val nether = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("the_nether"),
        )
        val air = VanillaStaticData.blockStates.default(Identifier("air")).id
        val surface = VanillaStaticData.blockStates
            .default(Identifier("grass_block"))
            .id
        val biome = VanillaProtocolData.requireRegistry(
            Identifier("worldgen/biome"),
        ).entries.indexOfFirst { it.id == Identifier("plains") }

        fun create(
            dimension: MinecraftDimensionLayout = overworld,
            groundY: Int = 64,
            surfaceId: Int = surface,
            biomeId: Int = biome,
            airId: Int = air,
            fullBrightSky: Boolean = dimension.hasSkyLight,
        ) = MinecraftChunkSnapshot.flat(
            dimension = dimension,
            chunkX = 0,
            chunkZ = 0,
            groundY = groundY,
            surfaceBlockStateId = surfaceId,
            biomeId = biomeId,
            airBlockStateId = airId,
            fullBrightSky = fullBrightSky,
        )

        assertFailsWith<IllegalArgumentException> {
            create(groundY = overworld.minY - 1)
        }
        assertFailsWith<IllegalArgumentException> {
            create(groundY = overworld.minY + overworld.height)
        }
        assertFailsWith<IllegalArgumentException> { create(airId = -1) }
        assertEquals(
            overworld.sectionCount,
            create(airId = VanillaStaticData.blockStates.size)
                .chunkData.sections.size,
        )
        assertFailsWith<IllegalArgumentException> { create(surfaceId = -1) }
        assertEquals(
            overworld.sectionCount,
            create(surfaceId = VanillaStaticData.blockStates.size)
                .chunkData.sections.size,
        )
        assertFailsWith<IllegalArgumentException> {
            create(surfaceId = air)
        }
        assertFailsWith<IllegalArgumentException> { create(biomeId = -1) }
        assertFailsWith<IllegalArgumentException> {
            create(dimension = nether, fullBrightSky = true)
        }

        listOf(nether.minY, nether.minY + nether.height - 1).forEach { groundY ->
            val chunk = create(
                dimension = nether,
                groundY = groundY,
                fullBrightSky = false,
            )
            assertTrue(chunk.lightData.skyYMask.words.isEmpty())
            assertTrue(chunk.lightData.skyUpdates.isEmpty())
        }
    }

    @Test
    fun bootstrapKeepsDefaultSpawnPlayerPositionAndChunkCenterIndependent() {
        val options = MinecraftServerNegotiationOptions(compressionThreshold = null)
        val defaultSpawnPosition = Vector3d(32.0, 70.0, -48.0)
        val playerPosition = Vector3d(-17.5, 80.0, 25.5)
        val centerChunk = ChunkPosition(9, -4)
        val bootstrap = MinecraftInitialWorldBootstrap.vanilla(
            options = options,
            defaultSpawnPosition = defaultSpawnPosition,
            defaultSpawnYaw = 10.0f,
            defaultSpawnPitch = 20.0f,
            playerPosition = playerPosition,
            playerYaw = 30.0f,
            playerPitch = 40.0f,
            centerChunk = centerChunk,
        )

        val packets = bootstrap.packets()

        assertEquals(8, packets.size)
        assertIs<ClientboundChangeDifficultyPacket>(packets[0])
        val defaultSpawn = assertIs<SetDefaultSpawnPositionPacket>(packets[1]).respawnData
        assertEquals(BlockPosition(32, 70, -48), defaultSpawn.globalPosition.position)
        assertEquals(10.0f, defaultSpawn.yaw)
        assertEquals(20.0f, defaultSpawn.pitch)
        assertIs<ClientboundPlayerAbilitiesPacket>(packets[2])
        assertIs<SetRenderDistancePacket>(packets[3])
        assertIs<SetSimulationDistancePacket>(packets[4])
        val position = assertIs<SynchronizePlayerPositionPacket>(packets[5])
        assertEquals(playerPosition, position.change.position)
        assertEquals(30.0f, position.change.yaw)
        assertEquals(40.0f, position.change.pitch)
        assertIs<GameEventPacket>(packets[6])
        val center = assertIs<SetCenterChunkPacket>(packets[7])
        assertEquals(centerChunk.x, center.chunkX)
        assertEquals(centerChunk.z, center.chunkZ)

        val world = MinecraftInitialWorld.flatVanilla(
            options = options,
            bootstrap = bootstrap,
            chunkRadius = 0,
        )
        assertEquals(listOf(centerChunk.x to centerChunk.z), world.chunks.map { it.chunkX to it.chunkZ })
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
        expected.forEach { (gameMode, abilities) ->
            assertEquals(abilities, MinecraftInitialWorldBootstrap.vanillaPlayerAbilities(gameMode))
        }

        val options = MinecraftServerNegotiationOptions(
            compressionThreshold = null,
            gameMode = PlayerGameMode.SPECTATOR,
            difficulty = Difficulty.HARD,
            difficultyLocked = true,
        )
        val world = MinecraftInitialWorld.flatVanilla(
            options,
            chunkRadius = 0,
        )

        assertEquals(Difficulty.HARD, world.bootstrap.difficulty)
        assertTrue(world.bootstrap.difficultyLocked)
        assertEquals(
            expected.getValue(PlayerGameMode.SPECTATOR),
            world.bootstrap.playerAbilities,
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
