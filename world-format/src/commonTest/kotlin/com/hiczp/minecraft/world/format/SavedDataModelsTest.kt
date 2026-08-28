package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.test.*
import kotlin.uuid.Uuid

class SavedDataModelsTest {
    private val nbtFormat = NbtFormat(
        NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
    )

    @Test
    fun dimensionSavedDataModelsRoundTripTheSelectedReleaseShapes() {
        val worldBorder = SavedDataFile(
            dataVersion = 4_903,
            data = WorldBorderData(
                centerX = 0.0,
                centerZ = 0.0,
                damagePerBlock = 0.2,
                safeZone = 5.0,
                warningBlocks = 5,
                warningTime = 300,
                size = 59_999_968.0,
                lerpTime = 0,
                lerpTarget = 59_999_968.0,
            ),
        )
        val chunkTickets = SavedDataFile(
            dataVersion = Int.MIN_VALUE,
            data = ChunkTicketsData(
                tickets = listOf(
                    ChunkTicketsData.Ticket(
                        chunkPosition = ChunkPosition(-2, 7),
                        type = "minecraft:forced",
                        level = 31,
                    ),
                ),
            ),
        )
        val heroUuid = Uuid.fromLongs(1, 2)
        val raids = SavedDataFile(
            dataVersion = 4_903,
            data = RaidsData(
                raids = listOf(
                    RaidsData.Raid(
                        id = 2,
                        started = true,
                        active = true,
                        ticksActive = 20,
                        raidOmenLevel = 1,
                        groupsSpawned = 2,
                        cooldownTicks = 300,
                        postRaidTicks = 0,
                        totalHealth = 40.0f,
                        groupCount = 5,
                        status = RaidsData.Status.ONGOING,
                        center = BlockPosition(16, 64, -16),
                        heroesOfTheVillage = linkedSetOf(heroUuid),
                    ),
                ),
                nextId = 3,
                tick = 40,
            ),
        )
        val enderDragonFight = SavedDataFile(
            dataVersion = 4_903,
            data = EnderDragonFightData(
                needsStateScanning = false,
                dragonKilled = true,
                previouslyKilled = true,
                respawnStage = EnderDragonFightData.RespawnStage.SUMMONING_DRAGON,
                respawnTime = 12,
                dragonUuid = Uuid.fromLongs(3, 4),
                exitPortalLocation = BlockPosition(0, 64, 0),
                gateways = listOf(3, 1, 2),
                respawnCrystals = listOf(Uuid.fromLongs(5, 6)),
            ),
        )

        assertSavedDataRoundTrip(SavedDataFile.serializer(WorldBorderData.serializer()), worldBorder)
        assertSavedDataRoundTrip(SavedDataFile.serializer(ChunkTicketsData.serializer()), chunkTickets)
        assertSavedDataRoundTrip(SavedDataFile.serializer(RaidsData.serializer()), raids)
        assertSavedDataRoundTrip(SavedDataFile.serializer(EnderDragonFightData.serializer()), enderDragonFight)

        val ticketsTag = assertIs<NbtCompound>(
            nbtFormat.encodeToNbtTag(SavedDataFile.serializer(ChunkTicketsData.serializer()), chunkTickets),
        )
        val ticket = assertIs<NbtCompound>(
            assertIs<NbtList>(
                assertIs<NbtCompound>(ticketsTag["data"])["tickets"],
            )[0],
        )
        assertIs<NbtIntArray>(ticket["chunk_pos"])
        assertFalse("ticks_left" in ticket.value)
        assertEquals(Int.MIN_VALUE, chunkTickets.dataVersion)
    }

    @Test
    fun playerDataStreamsOneStrongRootWhileKeepingRegistryPayloadsRaw() {
        val playerData = samplePlayerData().copy(
            customName = NbtString("Player"),
            tags = listOf("example"),
            customData = NbtCompound(mapOf("modded" to NbtInt(1))),
            passengers = listOf(NbtCompound(mapOf("id" to NbtString("minecraft:pig")))),
            currentExplosionImpactPosition = EntityVector3d(1.0, 2.0, 3.0),
            activeEffects = listOf(NbtCompound(mapOf("id" to NbtString("minecraft:speed")))),
            sleepingPosition = BlockPosition(1, 64, 2),
            lastHurtByPlayer = Uuid.fromLongs(7, 8),
            lastHurtByPlayerMemoryTime = 20,
            lastHurtByMob = Uuid.fromLongs(9, 10),
            ticksSinceLastHurtByMob = 5,
            equipment = NbtCompound(mapOf("mainhand" to NbtCompound(emptyMap()))),
            locatorBarIcon = NbtCompound(mapOf("color" to NbtInt(1))),
            lastDeathLocation = PlayerData.GlobalPosition("minecraft:overworld", BlockPosition(3, 60, 4)),
            enteredNetherPosition = EntityVector3d(8.0, 70.0, 9.0),
            rootVehicle = PlayerData.RootVehicle(
                attachedEntityUuid = Uuid.fromLongs(11, 12),
                entity = NbtCompound(mapOf("id" to NbtString("minecraft:boat"))),
            ),
            respawn = PlayerData.Respawn(
                dimension = "minecraft:overworld",
                blockPosition = BlockPosition(0, 64, 0),
                yaw = 90.0f,
                pitch = 0.0f,
                forced = true,
            ),
            raidOmenPosition = BlockPosition(5, 64, 6),
            enderPearls = listOf(NbtCompound(mapOf("ender_pearl_dimension" to NbtString("minecraft:overworld")))),
            shoulderEntityLeft = NbtCompound(mapOf("id" to NbtString("minecraft:parrot"))),
        )

        val root = assertIs<NbtCompound>(nbtFormat.encodeToNbtTag(PlayerData.serializer(), playerData))
        val decoded = nbtFormat.decodeFromNbtTag(PlayerData.serializer(), root)

        assertEquals(playerData, decoded)
        assertIs<NbtIntArray>(root["UUID"])
        assertIs<NbtList>(root["Pos"])
        assertIs<NbtList>(root["Inventory"])
        assertTrue("RootVehicle" in root.value)
        assertFalse("ShoulderEntityRight" in root.value)
    }

    @Test
    fun strongStandaloneModelsRemainStrictAtTheirOwnedRoot() {
        val encoded = assertIs<NbtCompound>(nbtFormat.encodeToNbtTag(PlayerData.serializer(), samplePlayerData()))

        assertFailsWith<SerializationException> {
            nbtFormat.decodeFromNbtTag(PlayerData.serializer(), NbtCompound(encoded.value + ("future" to NbtByte(1))))
        }
        assertFailsWith<SerializationException> {
            nbtFormat.decodeFromNbtTag(PlayerData.serializer(), NbtCompound(encoded.value - "Dimension"))
        }
    }

    private fun <T> assertSavedDataRoundTrip(serializer: KSerializer<SavedDataFile<T>>, value: SavedDataFile<T>) {
        val root = assertIs<NbtCompound>(nbtFormat.encodeToNbtTag(serializer, value))
        assertEquals(value, nbtFormat.decodeFromNbtTag(serializer, root))
        assertTrue("DataVersion" in root.value)
        assertIs<NbtCompound>(root["data"])
    }
}

private fun samplePlayerData(): PlayerData = PlayerData(
    position = EntityVector3d(128.5, 65.0, 313.5),
    motion = EntityVector3d(0.0, -0.0784000015258789, 0.0),
    entityRotation = EntityRotation.ZERO,
    fallDistance = 0.0,
    remainingFireTicks = (-20).toShort(),
    airSupply = 300,
    onGround = true,
    invulnerable = false,
    portalCooldown = 0,
    uuid = Uuid.fromLongs(-6_908_996_187_363_368_808L, -7_670_267_171_494_356_415L),
    health = 20.0f,
    hurtTime = 0,
    deathTime = 0,
    absorptionAmount = 0.0f,
    currentImpulseContextResetGraceTime = 0,
    attributes = listOf(PlayerData.Attribute("minecraft:movement_speed", 0.1)),
    fallFlying = false,
    brain = NbtCompound(mapOf("memories" to NbtCompound(emptyMap()))),
    dataVersion = 4_903,
    inventory = emptyList(),
    selectedItemSlot = 0,
    sleepTimer = 0,
    experienceProgress = 0.0f,
    experienceLevel = 0,
    totalExperience = 0,
    enchantmentSeed = 1,
    score = 0,
    foodLevel = 20,
    foodTickTimer = 0,
    foodSaturationLevel = 5.0f,
    foodExhaustionLevel = 0.0f,
    abilities = PlayerData.Abilities(
        invulnerable = false,
        flying = false,
        mayFly = false,
        instabuild = false,
        mayBuild = true,
        flyingSpeed = 0.05f,
        walkingSpeed = 0.1f,
    ),
    enderItems = emptyList(),
    wardenSpawnTracker = PlayerData.WardenSpawnTracker(
        ticksSinceLastWarning = 23,
        warningLevel = 0,
        cooldownTicks = 0,
    ),
    playerGameType = 0,
    seenCredits = false,
    recipeBook = PlayerData.RecipeBook(
        recipes = listOf("minecraft:crafting_table"),
        toBeDisplayed = listOf("minecraft:crafting_table"),
    ),
    dimension = "minecraft:overworld",
    spawnExtraParticlesOnFall = false,
)
