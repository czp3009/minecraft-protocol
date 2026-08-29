package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.serialization.SerializationException
import kotlin.test.*
import kotlin.uuid.Uuid

class PlayerDataTest {
    private val nbtFormat = NbtFormat(
        NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
    )

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
