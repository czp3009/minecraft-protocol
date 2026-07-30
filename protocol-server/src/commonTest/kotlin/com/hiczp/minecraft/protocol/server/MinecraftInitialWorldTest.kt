package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.data.VanillaStaticData
import com.hiczp.minecraft.protocol.data.requireRegistry
import com.hiczp.minecraft.protocol.model.packet.BundleDelimiterPacket
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.packet.SetEntityMetadataPacket
import com.hiczp.minecraft.protocol.model.packet.SpawnEntityPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftFormat
import kotlin.test.*

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

        val format = MinecraftFormat(
            MinecraftFormat.Default.configuration.copy(
                chunkSectionCount = dimension.sectionCount,
                blockStateRegistrySize = VanillaStaticData.blockStates.size,
                biomeRegistrySize = VanillaProtocolData.requireRegistry(
                    Identifier("worldgen/biome"),
                ).entries.size,
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
    fun entitySnapshotResolvesVanillaTypeAndBundlesMetadata() {
        val entity = MinecraftEntitySnapshot(
            entityId = 17,
            uuid = Uuid(1, 2),
            type = Identifier("pig"),
            position = Vector3d(1.5, 65.0, -2.5),
            metadata = EntityMetadata(emptyList()),
        )

        val packets = entity.packets()

        assertEquals(4, packets.size)
        val spawn = assertIs<SpawnEntityPacket>(packets[1])
        assertEquals(entity.entityId, spawn.entityId)
        assertEquals(entity.typeId, spawn.typeId)
        assertIs<SetEntityMetadataPacket>(packets[2])
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
        assertFailsWith<IllegalArgumentException> {
            create(airId = VanillaStaticData.blockStates.size)
        }
        assertFailsWith<IllegalArgumentException> { create(surfaceId = -1) }
        assertFailsWith<IllegalArgumentException> {
            create(surfaceId = VanillaStaticData.blockStates.size)
        }
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
    fun entitySnapshotRejectsInvalidWireFacingState() {
        fun entity(
            id: Int = 1,
            type: Identifier = Identifier("pig"),
            position: Vector3d = Vector3d(0.0, 64.0, 0.0),
            velocity: Vector3d = Vector3d(0.0, 0.0, 0.0),
            pitch: Float = 0.0f,
            yaw: Float = 0.0f,
            headYaw: Float = yaw,
        ) = MinecraftEntitySnapshot(
            entityId = id,
            uuid = Uuid(0, 1),
            type = type,
            position = position,
            velocity = velocity,
            pitch = pitch,
            yaw = yaw,
            headYaw = headYaw,
        )

        assertFailsWith<IllegalArgumentException> { entity(id = 0) }
        assertFailsWith<IllegalArgumentException> {
            entity(position = Vector3d(Double.NaN, 0.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            entity(velocity = Vector3d(0.0, Double.POSITIVE_INFINITY, 0.0))
        }
        assertFailsWith<IllegalArgumentException> { entity(pitch = Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            entity(yaw = Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            entity(headYaw = Float.NEGATIVE_INFINITY)
        }
        assertFailsWith<IllegalStateException> {
            entity(type = Identifier("test:absent")).typeId
        }

        val packets = entity().packets()
        assertEquals(3, packets.size)
        assertIs<BundleDelimiterPacket>(packets.first())
        assertIs<SpawnEntityPacket>(packets[1])
        assertIs<BundleDelimiterPacket>(packets.last())
    }

    @Test
    fun initialWorldRejectsInconsistentOrDuplicateProjectionState() {
        val dimension = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val surface = VanillaStaticData.blockStates
            .default(Identifier("grass_block"))
            .id
        val biome = VanillaProtocolData.requireRegistry(
            Identifier("worldgen/biome"),
        ).entries.indexOfFirst { it.id == Identifier("plains") }
        val chunk = MinecraftChunkSnapshot.flat(
            dimension = dimension,
            chunkX = 0,
            chunkZ = 0,
            groundY = 64,
            surfaceBlockStateId = surface,
            biomeId = biome,
        )
        val entity = MinecraftEntitySnapshot(
            entityId = 1,
            uuid = Uuid(0, 1),
            type = Identifier("pig"),
            position = Vector3d(0.0, 65.0, 0.0),
        )

        fun world(
            id: Identifier = dimension.id,
            type: MinecraftDimensionLayout = dimension,
            position: Vector3d = Vector3d(0.5, 65.0, 0.5),
            yaw: Float = 0.0f,
            pitch: Float = 0.0f,
            viewDistance: Int = 8,
            simulationDistance: Int = 8,
            teleportId: Int = 1,
            chunks: List<MinecraftChunkSnapshot> = listOf(chunk),
            entities: List<MinecraftEntitySnapshot> = listOf(entity),
        ) = MinecraftInitialWorld(
            dimension = id,
            dimensionType = type,
            spawnPosition = position,
            spawnYaw = yaw,
            spawnPitch = pitch,
            viewDistance = viewDistance,
            simulationDistance = simulationDistance,
            teleportId = teleportId,
            chunks = chunks,
            entities = entities,
        )

        assertEquals(
            Identifier("test:custom_world"),
            world(id = Identifier("test:custom_world")).dimension,
        )
        assertFailsWith<IllegalArgumentException> {
            world(position = Vector3d(Double.NaN, 0.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            world(
                position = Vector3d(
                    BlockPosition.MAX_XZ + 1.0,
                    0.0,
                    0.0,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            world(
                position = Vector3d(
                    0.0,
                    BlockPosition.MIN_Y - 1.0,
                    0.0,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> { world(yaw = Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            world(pitch = Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> { world(viewDistance = 1) }
        assertFailsWith<IllegalArgumentException> { world(viewDistance = 33) }
        assertEquals(2, world(viewDistance = 2).viewDistance)
        assertEquals(32, world(viewDistance = 32).viewDistance)
        assertFailsWith<IllegalArgumentException> {
            world(simulationDistance = -1)
        }
        assertFailsWith<IllegalArgumentException> { world(teleportId = -1) }
        assertFailsWith<IllegalArgumentException> {
            world(chunks = listOf(chunk, chunk.copy()))
        }
        assertFailsWith<IllegalArgumentException> {
            world(entities = listOf(entity, entity.copy()))
        }
        assertFailsWith<IllegalArgumentException> {
            world(
                chunks = listOf(
                    chunk.copy(
                        chunkData = chunk.chunkData.copy(
                            sections = chunk.chunkData.sections.dropLast(1),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftInitialWorld.flatVanilla(
                MinecraftServerConfiguration(compressionThreshold = null),
                chunkRadius = -1,
            )
        }
    }

    @Test
    fun initialWorldMustMatchTheNegotiatedPlayContext() {
        val configuration = MinecraftServerConfiguration(
            compressionThreshold = null,
        )
        val overworld = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val nether = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("the_nether"),
        )
        val profile = GameProfile(Uuid(1, 2), "Probe", emptyList())
        val login = configuration.playLogin(profile)

        fun world(
            dimension: Identifier = login.spawnInfo.dimension,
            dimensionType: MinecraftDimensionLayout = overworld,
            entities: List<MinecraftEntitySnapshot> = emptyList(),
        ) = MinecraftInitialWorld(
            dimension = dimension,
            dimensionType = dimensionType,
            spawnPosition = Vector3d(0.5, 65.0, 0.5),
            viewDistance = 8,
            simulationDistance = 8,
            chunks = emptyList(),
            entities = entities,
        )

        validateInitialWorld(world(), login, configuration)

        assertFailsWith<IllegalArgumentException> {
            validateInitialWorld(
                world(dimension = Identifier("the_nether")),
                login,
                configuration,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateInitialWorld(
                world(dimensionType = nether),
                login,
                configuration,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateInitialWorld(
                world(dimensionType = overworld.copy(height = 16)),
                login,
                configuration,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateInitialWorld(
                world(
                    entities = listOf(
                        MinecraftEntitySnapshot(
                            entityId = login.playerId,
                            uuid = Uuid(3, 4),
                            type = Identifier("pig"),
                            position = Vector3d(0.0, 65.0, 0.0),
                        ),
                    ),
                ),
                login,
                configuration,
            )
        }

        val customDimension = Identifier("test:custom_world")
        val customLogin = login.copy(
            levels = setOf(customDimension),
            spawnInfo = login.spawnInfo.copy(dimension = customDimension),
        )
        validateInitialWorld(
            world(dimension = customDimension),
            customLogin,
            configuration,
        )
    }

    @Test
    fun vanillaWorldConfigurationKeepsDifficultyAndEveryGameModeAbility() {
        val expected = mapOf(
            com.hiczp.minecraft.protocol.model.type.GameMode.SURVIVAL to
                    PlayerAbilities(false, false, false, false, 0.05f, 0.1f),
            com.hiczp.minecraft.protocol.model.type.GameMode.CREATIVE to
                    PlayerAbilities(true, false, true, true, 0.05f, 0.1f),
            com.hiczp.minecraft.protocol.model.type.GameMode.ADVENTURE to
                    PlayerAbilities(false, false, false, false, 0.05f, 0.1f),
            com.hiczp.minecraft.protocol.model.type.GameMode.SPECTATOR to
                    PlayerAbilities(true, true, true, false, 0.05f, 0.1f),
        )
        expected.forEach { (gameMode, abilities) ->
            assertEquals(abilities, vanillaPlayerAbilities(gameMode))
        }

        val configuration = MinecraftServerConfiguration(
            compressionThreshold = null,
            gameMode =
                com.hiczp.minecraft.protocol.model.type.GameMode.SPECTATOR,
            difficulty = Difficulty.HARD,
            difficultyLocked = true,
        )
        val world = MinecraftInitialWorld.flatVanilla(
            configuration,
            chunkRadius = 0,
        )

        assertEquals(Difficulty.HARD, world.difficulty)
        assertTrue(world.difficultyLocked)
        assertEquals(
            expected.getValue(
                com.hiczp.minecraft.protocol.model.type.GameMode.SPECTATOR,
            ),
            world.playerAbilities,
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
