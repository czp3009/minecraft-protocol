package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtIntArray
import com.hiczp.minecraft.nbt.NbtList
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.data.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.test.*
import kotlin.uuid.Uuid

class SavedDataFileTest {
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

        val enderDragonFightTag = assertIs<NbtCompound>(
            nbtFormat.encodeToNbtTag(
                SavedDataFile.serializer(EnderDragonFightData.serializer()),
                enderDragonFight,
            ),
        )
        val respawnCrystals = assertIs<NbtList>(
            assertIs<NbtCompound>(enderDragonFightTag["data"])["respawn_crystals"],
        )
        assertEquals(1, respawnCrystals.size)
        assertIs<NbtIntArray>(respawnCrystals[0])
    }

    @Test
    fun uuidSetRejectsDuplicateOfficialValues() {
        val uuid = Uuid.fromLongs(1, 2)
        val duplicatedValues = NbtList(listOf(uuid.toNbtIntArray(), uuid.toNbtIntArray()))

        assertFailsWith<SerializationException> {
            nbtFormat.decodeFromNbtTag(NbtUuidSetSerializer, duplicatedValues)
        }
    }

    private fun <T> assertSavedDataRoundTrip(serializer: KSerializer<SavedDataFile<T>>, value: SavedDataFile<T>) {
        val root = assertIs<NbtCompound>(nbtFormat.encodeToNbtTag(serializer, value))
        assertEquals(value, nbtFormat.decodeFromNbtTag(serializer, root))
        assertTrue("DataVersion" in root.value)
        assertIs<NbtCompound>(root["data"])
    }
}
