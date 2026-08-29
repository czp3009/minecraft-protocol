package com.hiczp.minecraft.world.io

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerListingTest {
    @Test
    fun mutableAndLivePlayersListTheSameDataFileUuids() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val firstPlayerUuid = "00000000-0000-0000-0000-000000000001"
        val secondPlayerUuid = "00000000-0000-0000-0000-000000000002"
        val thirdPlayerUuid = "00000000-0000-0000-0000-000000000003"
        val statisticsOnlyPlayerUuid = "00000000-0000-0000-0000-000000000004"
        val advancementsOnlyPlayerUuid = "00000000-0000-0000-0000-000000000005"
        val directoryPlayerUuid = "00000000-0000-0000-0000-000000000006"
        val playerDataDirectory = minecraftWorldPaths.playerDataDirectory
        fakeFileSystem.createDirectories(playerDataDirectory)
        fakeFileSystem.write(minecraftWorldPaths.playerData(thirdPlayerUuid)) {}
        fakeFileSystem.write(minecraftWorldPaths.previousPlayerData(secondPlayerUuid)) {}
        fakeFileSystem.write(minecraftWorldPaths.playerData(firstPlayerUuid)) {}
        fakeFileSystem.write(minecraftWorldPaths.previousPlayerData(firstPlayerUuid)) {}
        fakeFileSystem.write(playerDataDirectory / "ignored.dat_corrupted_1") {}
        fakeFileSystem.write(playerDataDirectory / "ignored.txt") {}
        fakeFileSystem.createDirectories(playerDataDirectory / "$directoryPlayerUuid.dat")
        fakeFileSystem.createDirectories(checkNotNull(minecraftWorldPaths.statistics(statisticsOnlyPlayerUuid).parent))
        fakeFileSystem.createDirectories(
            checkNotNull(minecraftWorldPaths.advancements(advancementsOnlyPlayerUuid).parent),
        )
        fakeFileSystem.write(minecraftWorldPaths.statistics(statisticsOnlyPlayerUuid)) {}
        fakeFileSystem.write(minecraftWorldPaths.advancements(advancementsOnlyPlayerUuid)) {}
        val expected = listOf(firstPlayerUuid, secondPlayerUuid, thirdPlayerUuid)

        val playerDataStore = PlayerDataStore(minecraftWorldPaths, NbtFileStore(fakeFileSystem))
        assertEquals(expected, playerDataStore.listUuids())

        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)
        assertEquals(expected, minecraftWorldAccess.players.listUuids())
        minecraftWorldAccess.close()
        assertFailsWith<IllegalStateException> { minecraftWorldAccess.players.listUuids() }

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertEquals(expected, liveMinecraftWorldAccess.players.listUuids())
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun missingPlayerDataDirectoryProducesAnEmptySnapshot() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        fakeFileSystem.createDirectories(minecraftWorldPaths.root)

        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)
        try {
            assertEquals(emptyList(), minecraftWorldAccess.players.listUuids())
        } finally {
            minecraftWorldAccess.close()
        }

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertEquals(emptyList(), liveMinecraftWorldAccess.players.listUuids())
    }
}
