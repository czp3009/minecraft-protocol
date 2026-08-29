package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.world.format.Compression
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class StandaloneFileStoreEdgeTest {
    @Test
    fun nbtReadsRejectTrailingBytesWithoutPolicyLimits() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val path = "/world/value.dat".toPath()
        val normal = NbtFileStore(fakeFileSystem)
        normal.writeDocument(path, edgeDocument(1), Compression.NONE)
        val original = fakeFileSystem.readFileBytes(path)
        fakeFileSystem.writeRaw(
            path,
            original.copyOf(original.size + 1).also { it[it.lastIndex] = 1 },
        )
        assertFailsWith<NbtDecodingException> {
            normal.readDocument(path, Compression.NONE)
        }

        fakeFileSystem.writeRaw(path, original)
        assertEquals(edgeDocument(1), normal.readDocument(path, Compression.NONE))
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun directNbtStreamingFailureLeavesTheOfficialTruncatedBoundary() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val path = "/world/data/value.dat".toPath()
        val normal = NbtFileStore(fakeFileSystem)
        normal.writeDocument(path, edgeDocument(1))
        val oldBytes = fakeFileSystem.readFileBytes(path)
        val failure = WorldIOException("synthetic streaming failure")

        assertSame(failure, assertFails { normal.write(path) { throw failure } })

        val failedBytes = fakeFileSystem.readFileBytes(path)
        assertFalse(oldBytes.contentEquals(failedBytes))
        assertFails { normal.readDocument(path) }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun everyTemporaryNbtIoFailureCleansUpWithoutReplacingPrimary() = runTest {
        TemporaryNbtFailure.entries.forEach { failurePoint ->
            val base = FakeFileSystem()
            val minecraftWorldPaths = MinecraftWorldPaths(
                "/world-${failurePoint.name}".toPath(),
            )
            val first = edgeDocument(1)
            LevelDataStore(minecraftWorldPaths, NbtFileStore(base)).writeDocument(first)
            val temporaryHandleFailingFileSystem = TemporaryHandleFailingFileSystem(
                base,
                failurePoint,
            )

            val failure = assertFails {
                LevelDataStore(minecraftWorldPaths, NbtFileStore(temporaryHandleFailingFileSystem))
                    .writeDocument(edgeDocument(2))
            }
            assertIs<IOException>(failure)

            assertEquals(first, NbtFileStore(base).readDocument(minecraftWorldPaths.levelData))
            assertFalse(base.exists(minecraftWorldPaths.previousLevelData))
            assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
            base.checkNoOpenFiles()
        }
    }

    @Test
    fun levelPrimaryWinsAndMissingPrimaryPromotesPreviousWithoutCorruption() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
        val primary = edgeDocument(1)
        val previous = edgeDocument(2)
        nbtFileStore.writeDocument(minecraftWorldPaths.levelData, primary)
        nbtFileStore.writeDocument(minecraftWorldPaths.previousLevelData, previous)

        assertEquals(primary, levelDataStore.readDocument())
        assertEquals(previous, nbtFileStore.readDocument(minecraftWorldPaths.previousLevelData))

        fakeFileSystem.delete(minecraftWorldPaths.levelData)
        assertEquals(previous, levelDataStore.readDocument())
        assertEquals(previous, nbtFileStore.readDocument(minecraftWorldPaths.levelData))
        assertFalse(fakeFileSystem.exists(minecraftWorldPaths.previousLevelData))
        assertTrue(
            fakeFileSystem.list(minecraftWorldPaths.root).none {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
    }

    @Test
    fun levelReportsFallbackFailureAndReturnsFallbackWhenPromotionFails() = runTest {
        val empty = FakeFileSystem()
        val emptyPaths = MinecraftWorldPaths("/empty".toPath())
        val missing = assertFailsWith<WorldIOException> {
            LevelDataStore(emptyPaths, NbtFileStore(empty)).readDocument()
        }
        assertContains(checkNotNull(missing.message), emptyPaths.previousLevelData.toString())
        assertEquals(1, missing.suppressedExceptions.size)
        assertContains(
            checkNotNull(missing.suppressedExceptions.single().message),
            emptyPaths.levelData.toString(),
        )

        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(base)
        base.writeRaw(minecraftWorldPaths.levelData, byteArrayOf(1, 2, 3))
        nbtFileStore.writeDocument(minecraftWorldPaths.previousLevelData, edgeDocument(7))
        val promotionFailingFileSystem = PromotionFailingFileSystem(
            base,
            minecraftWorldPaths.levelData,
            minecraftWorldPaths.previousLevelData,
        )

        assertEquals(
            edgeDocument(7),
            LevelDataStore(minecraftWorldPaths, NbtFileStore(promotionFailingFileSystem)).readDocument(),
        )
        assertEquals(10, promotionFailingFileSystem.attempts)
        assertFalse(base.exists(minecraftWorldPaths.levelData))
        assertTrue(base.exists(minecraftWorldPaths.previousLevelData))
        assertTrue(
            base.list(minecraftWorldPaths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
    }

    @Test
    fun levelCorruptedMoveFailureReturnsFallbackAndKeepsBothOriginalFiles() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(base)
        val corrupt = byteArrayOf(1, 2, 3)
        base.writeRaw(minecraftWorldPaths.levelData, corrupt)
        nbtFileStore.writeDocument(minecraftWorldPaths.previousLevelData, edgeDocument(4))
        val corruptedMoveFailingFileSystem = CorruptedMoveFailingFileSystem(base, minecraftWorldPaths.levelData)

        assertEquals(
            edgeDocument(4),
            LevelDataStore(minecraftWorldPaths, NbtFileStore(corruptedMoveFailingFileSystem)).readDocument(),
        )
        assertEquals(10, corruptedMoveFailingFileSystem.attempts)
        assertContentEquals(corrupt, base.readFileBytes(minecraftWorldPaths.levelData))
        assertTrue(base.exists(minecraftWorldPaths.previousLevelData))
        assertTrue(
            base.list(minecraftWorldPaths.root).none {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
    }

    @Test
    fun levelRecoveryRetriesTransientDisplacementAndPromotionFailures() = runTest {
        val displacementBase = FakeFileSystem()
        val displacementPaths = MinecraftWorldPaths("/displacement".toPath())
        val displacementCorrupt = byteArrayOf(1, 2, 3)
        val displacementPrevious = edgeDocument(11)
        displacementBase.writeRaw(
            displacementPaths.levelData,
            displacementCorrupt,
        )
        NbtFileStore(displacementBase).writeDocument(
            displacementPaths.previousLevelData,
            displacementPrevious,
        )
        val displacement = SelectedMoveThrowingFileSystem(
            displacementBase,
            displacementPaths.levelData,
            IOException("synthetic transient displacement failure"),
            failures = 2,
        ) { target -> target.name.startsWith("level.dat_corrupted_") }

        assertEquals(
            displacementPrevious,
            LevelDataStore(
                displacementPaths,
                NbtFileStore(displacement),
            ).readDocument(),
        )
        assertEquals(3, displacement.attempts)
        assertEquals(
            displacementPrevious,
            NbtFileStore(displacementBase).readDocument(
                displacementPaths.levelData,
            ),
        )
        assertFalse(
            displacementBase.exists(displacementPaths.previousLevelData),
        )
        assertContentEquals(
            displacementCorrupt,
            displacementBase.list(displacementPaths.root).single {
                it.name.startsWith("level.dat_corrupted_")
            }.let(displacementBase::readFileBytes),
        )

        val promotionBase = FakeFileSystem()
        val promotionPaths = MinecraftWorldPaths("/promotion".toPath())
        val promotionCorrupt = byteArrayOf(4, 5, 6)
        val promotionPrevious = edgeDocument(12)
        promotionBase.writeRaw(promotionPaths.levelData, promotionCorrupt)
        NbtFileStore(promotionBase).writeDocument(
            promotionPaths.previousLevelData,
            promotionPrevious,
        )
        val promotion = SelectedMoveThrowingFileSystem(
            promotionBase,
            promotionPaths.previousLevelData,
            IOException("synthetic transient promotion failure"),
            failures = 2,
        ) { target -> target == promotionPaths.levelData }

        assertEquals(
            promotionPrevious,
            LevelDataStore(
                promotionPaths,
                NbtFileStore(promotion),
            ).readDocument(),
        )
        assertEquals(3, promotion.attempts)
        assertEquals(
            promotionPrevious,
            NbtFileStore(promotionBase).readDocument(promotionPaths.levelData),
        )
        assertFalse(promotionBase.exists(promotionPaths.previousLevelData))
        assertContentEquals(
            promotionCorrupt,
            promotionBase.list(promotionPaths.root).single {
                it.name.startsWith("level.dat_corrupted_")
            }.let(promotionBase::readFileBytes),
        )
    }

    @Test
    fun cancellationDoesNotTriggerLevelFallback() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        base.writeRaw(minecraftWorldPaths.levelData, byteArrayOf(1))
        NbtFileStore(base).writeDocument(
            minecraftWorldPaths.previousLevelData,
            edgeDocument(1),
        )
        val cancelling = CancellationSourceFileSystem(
            base,
            minecraftWorldPaths.levelData,
        )

        assertFailsWith<CancellationException> {
            LevelDataStore(minecraftWorldPaths, NbtFileStore(cancelling)).readDocument()
        }
        assertTrue(base.exists(minecraftWorldPaths.previousLevelData))
        assertContentEquals(byteArrayOf(1), base.readFileBytes(minecraftWorldPaths.levelData))
    }

    @Test
    fun cancellationDuringLevelFallbackIsNotConsumed() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        base.writeRaw(minecraftWorldPaths.levelData, byteArrayOf(1))
        NbtFileStore(base).writeDocument(
            minecraftWorldPaths.previousLevelData,
            edgeDocument(1),
        )
        val cancelling = CancellationSourceFileSystem(
            base,
            minecraftWorldPaths.previousLevelData,
        )

        assertFailsWith<CancellationException> {
            LevelDataStore(minecraftWorldPaths, NbtFileStore(cancelling)).readDocument()
        }
        assertTrue(base.exists(minecraftWorldPaths.previousLevelData))
        assertContentEquals(byteArrayOf(1), base.readFileBytes(minecraftWorldPaths.levelData))
    }

    @Test
    fun cancellationDuringEitherLevelPromotionMoveIsNotConsumed() = runTest {
        val corruptedMoveBase = FakeFileSystem()
        val corruptedMovePaths = MinecraftWorldPaths("/corrupted".toPath())
        corruptedMoveBase.writeRaw(
            corruptedMovePaths.levelData,
            byteArrayOf(1, 2, 3),
        )
        NbtFileStore(corruptedMoveBase).writeDocument(
            corruptedMovePaths.previousLevelData,
            edgeDocument(1),
        )
        val corruptedMoveCancellation = CancellationException(
            "corrupted move cancelled",
        )
        val corruptedMoveCancelling = SelectedMoveThrowingFileSystem(
            corruptedMoveBase,
            corruptedMovePaths.levelData,
            corruptedMoveCancellation,
        ) { target -> target.name.startsWith("level.dat_corrupted_") }

        assertSame(
            corruptedMoveCancellation,
            assertFailsWith<CancellationException> {
                LevelDataStore(
                    corruptedMovePaths,
                    NbtFileStore(corruptedMoveCancelling),
                ).readDocument()
            },
        )
        assertEquals(1, corruptedMoveCancelling.attempts)
        assertTrue(corruptedMoveBase.exists(corruptedMovePaths.levelData))
        assertTrue(
            corruptedMoveBase.exists(
                corruptedMovePaths.previousLevelData,
            ),
        )
        assertTrue(
            corruptedMoveBase.list(corruptedMovePaths.root).none {
                it.name.startsWith("level.dat_corrupted_")
            },
        )

        val previousMoveBase = FakeFileSystem()
        val previousMovePaths = MinecraftWorldPaths("/previous".toPath())
        previousMoveBase.writeRaw(
            previousMovePaths.levelData,
            byteArrayOf(1, 2, 3),
        )
        NbtFileStore(previousMoveBase).writeDocument(
            previousMovePaths.previousLevelData,
            edgeDocument(2),
        )
        val previousMoveCancellation = CancellationException(
            "previous move cancelled",
        )
        val previousMoveCancelling = SelectedMoveThrowingFileSystem(
            previousMoveBase,
            previousMovePaths.previousLevelData,
            previousMoveCancellation,
        ) { target -> target == previousMovePaths.levelData }

        assertSame(
            previousMoveCancellation,
            assertFailsWith<CancellationException> {
                LevelDataStore(
                    previousMovePaths,
                    NbtFileStore(previousMoveCancelling),
                ).readDocument()
            },
        )
        assertEquals(1, previousMoveCancelling.attempts)
        assertFalse(previousMoveBase.exists(previousMovePaths.levelData))
        assertTrue(
            previousMoveBase.exists(previousMovePaths.previousLevelData),
        )
        assertTrue(
            previousMoveBase.list(previousMovePaths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
    }

    @Test
    fun unexpectedLevelFallbackAndPromotionFailuresPropagateUnchanged() = runTest {
        val fallbackBase = FakeFileSystem()
        val fallbackPaths = MinecraftWorldPaths("/level-fallback".toPath())
        fallbackBase.writeRaw(fallbackPaths.levelData, byteArrayOf(1))
        NbtFileStore(fallbackBase).writeDocument(
            fallbackPaths.previousLevelData,
            edgeDocument(31),
        )
        val fallbackFailure = IllegalStateException(
            "synthetic level fallback failure",
        )
        val fallbackFailing = SourceThrowingFileSystem(
            fallbackBase,
            fallbackPaths.previousLevelData,
            fallbackFailure,
        )

        assertSame(
            fallbackFailure,
            assertFailsWith<IllegalStateException> {
                LevelDataStore(
                    fallbackPaths,
                    NbtFileStore(fallbackFailing),
                ).readDocument()
            },
        )
        assertContentEquals(
            byteArrayOf(1),
            fallbackBase.readFileBytes(fallbackPaths.levelData),
        )
        assertTrue(fallbackBase.exists(fallbackPaths.previousLevelData))

        val promotionBase = FakeFileSystem()
        val promotionPaths = MinecraftWorldPaths("/level-promotion".toPath())
        promotionBase.writeRaw(promotionPaths.levelData, byteArrayOf(2))
        NbtFileStore(promotionBase).writeDocument(
            promotionPaths.previousLevelData,
            edgeDocument(32),
        )
        val promotionFailure = IllegalStateException(
            "synthetic level promotion failure",
        )
        val promotionFailing = SelectedMoveThrowingFileSystem(
            promotionBase,
            promotionPaths.levelData,
            promotionFailure,
        ) { target -> target.name.startsWith("level.dat_corrupted_") }

        assertSame(
            promotionFailure,
            assertFailsWith<IllegalStateException> {
                LevelDataStore(
                    promotionPaths,
                    NbtFileStore(promotionFailing),
                ).readDocument()
            },
        )
        assertEquals(1, promotionFailing.attempts)
        assertContentEquals(
            byteArrayOf(2),
            promotionBase.readFileBytes(promotionPaths.levelData),
        )
        assertTrue(promotionBase.exists(promotionPaths.previousLevelData))
    }

    @Test
    fun serializerMappingFailureDoesNotTriggerLevelOrPlayerRecovery() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val primary = edgeDocument(51)
        val compatiblePrevious = RequiredValue(52)
        nbtFileStore.writeDocument(minecraftWorldPaths.levelData, primary)
        nbtFileStore.write(
            minecraftWorldPaths.previousLevelData,
            compatiblePrevious,
            serializationStrategy = RequiredValue.serializer(),
        )
        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)

        assertFailsWith<NbtDecodingException> {
            levelDataStore.read(RequiredValue.serializer())
        }
        assertFailsWith<NbtDecodingException> {
            levelDataStore.readForSharedAccess(RequiredValue.serializer())
        }
        assertEquals(primary, nbtFileStore.readDocument(minecraftWorldPaths.levelData))
        assertEquals(
            compatiblePrevious,
            nbtFileStore.read(
                minecraftWorldPaths.previousLevelData,
                deserializationStrategy = RequiredValue.serializer(),
            ),
        )
        assertTrue(
            fakeFileSystem.list(minecraftWorldPaths.root).none {
                it.name.startsWith("level.dat_corrupted_")
            },
        )

        val playerUuid = "player"
        nbtFileStore.writeDocument(minecraftWorldPaths.playerData(playerUuid), primary)
        nbtFileStore.write(
            minecraftWorldPaths.previousPlayerData(playerUuid),
            compatiblePrevious,
            serializationStrategy = RequiredValue.serializer(),
        )
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)

        assertFailsWith<NbtDecodingException> {
            playerDataStore.read(playerUuid, RequiredValue.serializer())
        }
        assertFailsWith<NbtDecodingException> {
            playerDataStore.readForSharedAccess(playerUuid, RequiredValue.serializer())
        }
        assertEquals(primary, nbtFileStore.readDocument(minecraftWorldPaths.playerData(playerUuid)))
        assertEquals(
            compatiblePrevious,
            nbtFileStore.read(
                minecraftWorldPaths.previousPlayerData(playerUuid),
                deserializationStrategy = RequiredValue.serializer(),
            ),
        )
        assertTrue(
            fakeFileSystem.list(checkNotNull(minecraftWorldPaths.playerData(playerUuid).parent)).none {
                it.name.startsWith("player.dat_corrupted_")
            },
        )
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun playerReadCoversMissingPrimaryPreviousAndFailureBranches() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
        val player = "player"
        val previous = edgeDocument(1)

        assertNull(playerDataStore.readDocument(player))
        nbtFileStore.writeDocument(minecraftWorldPaths.previousPlayerData(player), previous)
        assertEquals(previous, playerDataStore.readDocument(player))
        assertFalse(fakeFileSystem.exists(minecraftWorldPaths.playerData(player)))

        val primary = edgeDocument(2)
        nbtFileStore.writeDocument(minecraftWorldPaths.playerData(player), primary)
        assertEquals(primary, playerDataStore.readDocument(player))

        fakeFileSystem.writeRaw(minecraftWorldPaths.playerData(player), byteArrayOf(1, 2, 3))
        fakeFileSystem.writeRaw(minecraftWorldPaths.previousPlayerData(player), byteArrayOf(4, 5))
        assertNull(playerDataStore.readDocument(player))
        assertTrue(
            fakeFileSystem.list(checkNotNull(minecraftWorldPaths.playerData(player).parent)).any {
                it.name.startsWith("player.dat_corrupted_")
            },
        )
        assertFalse(
            fakeFileSystem.list(checkNotNull(minecraftWorldPaths.playerData(player).parent)).any {
                it.name.startsWith("player.dat_old_corrupted_")
            },
        )
        val preserved = fakeFileSystem.list(checkNotNull(minecraftWorldPaths.playerData(player).parent)).filter {
            it.name.startsWith("player.dat_corrupted_")
        }
        val replacement = edgeDocument(3)
        playerDataStore.writeDocument(player, replacement)
        assertEquals(replacement, playerDataStore.readDocument(player))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fakeFileSystem.readFileBytes(minecraftWorldPaths.previousPlayerData(player)),
        )
        assertEquals(1, preserved.count(fakeFileSystem::exists))

        val withoutPrevious = "without-previous"
        val corrupt = byteArrayOf(9, 8, 7)
        fakeFileSystem.writeRaw(minecraftWorldPaths.playerData(withoutPrevious), corrupt)
        assertNull(playerDataStore.readDocument(withoutPrevious))
        assertContentEquals(
            corrupt,
            fakeFileSystem.list(
                checkNotNull(minecraftWorldPaths.playerData(withoutPrevious).parent),
            ).single {
                it.name.startsWith(
                    "without-previous.dat_corrupted_",
                )
            }.let(fakeFileSystem::readFileBytes),
        )
        assertContentEquals(
            corrupt,
            fakeFileSystem.readFileBytes(minecraftWorldPaths.playerData(withoutPrevious)),
        )

        val onlyCorruptPrevious = "only-corrupt-previous"
        fakeFileSystem.writeRaw(
            minecraftWorldPaths.previousPlayerData(onlyCorruptPrevious),
            byteArrayOf(6, 5, 4),
        )
        val sharedRead = assertIs<CoordinatedRead.Complete<NbtDocument?>>(
            playerDataStore.readDocumentForSharedAccess(onlyCorruptPrevious),
        )
        assertNull(sharedRead.value)
        assertNull(playerDataStore.readDocument(onlyCorruptPrevious))
        assertTrue(
            fakeFileSystem.list(checkNotNull(minecraftWorldPaths.playerData(onlyCorruptPrevious).parent)).none {
                it.name.startsWith("only-corrupt-previous.dat_old_corrupted_")
            },
        )
    }

    @Test
    fun playerCorruptionCopyFailureDoesNotBlockFallback() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val previous = edgeDocument(8)
        base.writeRaw(minecraftWorldPaths.playerData(player), byteArrayOf(1, 2, 3))
        NbtFileStore(base).writeDocument(
            minecraftWorldPaths.previousPlayerData(player),
            previous,
        )
        val failing = SecondPrimarySourceThrowingFileSystem(
            base,
            minecraftWorldPaths.playerData(player),
            IOException("synthetic corruption copy failure"),
        )

        assertEquals(
            previous,
            PlayerDataStore(minecraftWorldPaths, NbtFileStore(failing)).readDocument(player),
        )
        assertTrue(
            base.list(checkNotNull(minecraftWorldPaths.playerData(player).parent)).none {
                it.name.startsWith("player.dat_corrupted_")
            },
        )
    }

    @Test
    fun playerCorruptionCopyWriteAndCloseFailuresCleanUpAndFallback() = runTest {
        CorruptedCopySinkFailure.entries.forEach { failurePoint ->
            val base = FakeFileSystem()
            val minecraftWorldPaths = MinecraftWorldPaths(
                "/copy-${failurePoint.name}".toPath(),
            )
            val player = "player"
            val previous = edgeDocument(failurePoint.ordinal + 20)
            base.writeRaw(
                minecraftWorldPaths.playerData(player),
                byteArrayOf(1, 2, 3),
            )
            NbtFileStore(base).writeDocument(
                minecraftWorldPaths.previousPlayerData(player),
                previous,
            )
            val corruptedCopySinkFailingFileSystem = CorruptedCopySinkFailingFileSystem(
                base,
                failurePoint,
            )

            assertEquals(
                previous,
                PlayerDataStore(
                    minecraftWorldPaths,
                    NbtFileStore(corruptedCopySinkFailingFileSystem),
                ).readDocument(player),
            )
            assertTrue(
                base.list(checkNotNull(minecraftWorldPaths.playerData(player).parent)).none {
                    it.name.startsWith("player.dat_corrupted_")
                },
            )
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                base.readFileBytes(minecraftWorldPaths.playerData(player)),
            )
            base.checkNoOpenFiles()
        }
    }

    @Test
    fun cancellationDoesNotTriggerPlayerFallbackOrCorruptionCopy() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        base.writeRaw(minecraftWorldPaths.playerData(player), byteArrayOf(1))
        NbtFileStore(base).writeDocument(
            minecraftWorldPaths.previousPlayerData(player),
            edgeDocument(1),
        )
        val cancelling = CancellationSourceFileSystem(
            base,
            minecraftWorldPaths.playerData(player),
        )

        assertFailsWith<CancellationException> {
            PlayerDataStore(minecraftWorldPaths, NbtFileStore(cancelling)).readDocument(player)
        }
        assertTrue(
            base.list(checkNotNull(minecraftWorldPaths.playerData(player).parent)).none {
                it.name.contains("_corrupted_")
            },
        )
    }

    @Test
    fun cancellationDuringPlayerCopyOrFallbackIsNotConsumed() = runTest {
        val copyBase = FakeFileSystem()
        val copyPaths = MinecraftWorldPaths("/copy".toPath())
        val player = "player"
        copyBase.writeRaw(
            copyPaths.playerData(player),
            byteArrayOf(1, 2, 3),
        )
        NbtFileStore(copyBase).writeDocument(
            copyPaths.previousPlayerData(player),
            edgeDocument(1),
        )
        val copyCancellation = CancellationException("copy cancelled")
        val copyCancelling = SecondPrimarySourceThrowingFileSystem(
            copyBase,
            copyPaths.playerData(player),
            copyCancellation,
        )

        assertSame(
            copyCancellation,
            assertFailsWith<CancellationException> {
                PlayerDataStore(
                    copyPaths,
                    NbtFileStore(copyCancelling),
                ).readDocument(player)
            },
        )
        assertTrue(
            copyBase.list(
                checkNotNull(copyPaths.playerData(player).parent),
            ).none { it.name.contains("_corrupted_") },
        )

        val fallbackBase = FakeFileSystem()
        val fallbackPaths = MinecraftWorldPaths("/fallback".toPath())
        fallbackBase.writeRaw(
            fallbackPaths.playerData(player),
            byteArrayOf(1, 2, 3),
        )
        NbtFileStore(fallbackBase).writeDocument(
            fallbackPaths.previousPlayerData(player),
            edgeDocument(2),
        )
        val fallbackCancelling = CancellationSourceFileSystem(
            fallbackBase,
            fallbackPaths.previousPlayerData(player),
        )

        assertFailsWith<CancellationException> {
            PlayerDataStore(
                fallbackPaths,
                NbtFileStore(fallbackCancelling),
            ).readDocument(player)
        }
        assertTrue(fallbackBase.exists(fallbackPaths.previousPlayerData(player)))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fallbackBase.readFileBytes(fallbackPaths.playerData(player)),
        )
    }

    @Test
    fun unexpectedPlayerCopyAndFallbackFailuresPropagateUnchanged() = runTest {
        val copyBase = FakeFileSystem()
        val copyPaths = MinecraftWorldPaths("/player-copy-runtime".toPath())
        val player = "player"
        copyBase.writeRaw(copyPaths.playerData(player), byteArrayOf(1, 2, 3))
        NbtFileStore(copyBase).writeDocument(
            copyPaths.previousPlayerData(player),
            edgeDocument(41),
        )
        val copyFailure = IllegalStateException(
            "synthetic player copy failure",
        )
        val copyFailing = SecondPrimarySourceThrowingFileSystem(
            copyBase,
            copyPaths.playerData(player),
            copyFailure,
        )

        assertSame(
            copyFailure,
            assertFailsWith<IllegalStateException> {
                PlayerDataStore(
                    copyPaths,
                    NbtFileStore(copyFailing),
                ).readDocument(player)
            },
        )
        assertTrue(copyBase.exists(copyPaths.previousPlayerData(player)))

        val fallbackBase = FakeFileSystem()
        val fallbackPaths = MinecraftWorldPaths(
            "/player-fallback-runtime".toPath(),
        )
        fallbackBase.writeRaw(
            fallbackPaths.playerData(player),
            byteArrayOf(4, 5, 6),
        )
        NbtFileStore(fallbackBase).writeDocument(
            fallbackPaths.previousPlayerData(player),
            edgeDocument(42),
        )
        val fallbackFailure = IllegalStateException(
            "synthetic player fallback failure",
        )
        val fallbackFailing = SourceThrowingFileSystem(
            fallbackBase,
            fallbackPaths.previousPlayerData(player),
            fallbackFailure,
        )

        assertSame(
            fallbackFailure,
            assertFailsWith<IllegalStateException> {
                PlayerDataStore(
                    fallbackPaths,
                    NbtFileStore(fallbackFailing),
                ).readDocument(player)
            },
        )
        assertContentEquals(
            byteArrayOf(4, 5, 6),
            fallbackBase.readFileBytes(fallbackPaths.playerData(player)),
        )
        assertTrue(fallbackBase.exists(fallbackPaths.previousPlayerData(player)))
    }

    @Test
    fun playerWritesBackUpOnlyAfterTheFirstSuccessfulSave() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
        val player = "player"

        playerDataStore.writeDocument(player, edgeDocument(1))
        assertFalse(fakeFileSystem.exists(minecraftWorldPaths.previousPlayerData(player)))
        playerDataStore.writeDocument(player, edgeDocument(2))

        assertEquals(edgeDocument(2), nbtFileStore.readDocument(minecraftWorldPaths.playerData(player)))
        assertEquals(
            edgeDocument(1),
            nbtFileStore.readDocument(minecraftWorldPaths.previousPlayerData(player)),
        )
    }

    @Test
    fun savedDataCoversMissingShortMagicAndNamespacedPaths() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val savedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionId.Overworld),
            NbtFileStore(base),
        )
        assertNull(savedDataStore.readDocument(SavedDataId("missing")))

        val savedDataId = SavedDataId("state/value", namespace = "example")
        val path = minecraftWorldPaths.savedData(savedDataId, SavedDataScope.Dimension(DimensionId.Overworld))
        NbtFileStore(base).writeDocument(
            path,
            edgeDocument(3),
            Compression.NONE,
        )
        val shortReads = ShortReadHandleFileSystem(base, path)
        assertEquals(
            edgeDocument(3),
            SavedDataStore(
                minecraftWorldPaths,
                SavedDataScope.Dimension(DimensionId.Overworld),
                NbtFileStore(shortReads),
            ).readDocument(savedDataId),
        )

        base.writeRaw(path, byteArrayOf(0x1F))
        assertFails { savedDataStore.readDocument(savedDataId) }
        base.checkNoOpenFiles()
    }

    @Test
    fun jsonReadsAndWritesHaveNoPolicyLimitAndRejectNonFiles() {
        val fakeFileSystem = FakeFileSystem()
        val utf8JsonFileStore = Utf8JsonFileStore(fakeFileSystem)
        val path = "/world/value.json".toPath()

        assertFailsWith<WorldIOException> { utf8JsonFileStore.readJson(path) { source -> source.readUtf8() } }
        fakeFileSystem.createDirectories(path)
        assertFailsWith<WorldIOException> { utf8JsonFileStore.readJson(path) { source -> source.readUtf8() } }
        fakeFileSystem.delete(path)
        utf8JsonFileStore.writeJson(path) { sink -> sink.writeUtf8("{}") }
        assertEquals("{}", utf8JsonFileStore.readJson(path) { source -> source.readUtf8() })
        utf8JsonFileStore.writeJson(path) { sink -> sink.writeUtf8("\u00E9x") }
        assertEquals("\u00E9x", utf8JsonFileStore.readJson(path) { source -> source.readUtf8() })
    }

    @Test
    fun jsonWriteAndCloseFailuresExposeDirectFinalPathBoundaries() {
        JsonFailure.entries.forEach { failurePoint ->
            val base = FakeFileSystem()
            val path = "/world-${failurePoint.name}/value.json".toPath()
            Utf8JsonFileStore(base).writeJson(path) { sink -> sink.writeUtf8("old") }
            val jsonSinkFailingFileSystem = JsonSinkFailingFileSystem(
                base,
                path,
                failurePoint,
            )

            assertFailsWith<IOException> {
                Utf8JsonFileStore(jsonSinkFailingFileSystem).writeJson(path) { sink -> sink.writeUtf8("new") }
            }

            val actual = Utf8JsonFileStore(base).readJson(path) { source -> source.readUtf8() }
            when (failurePoint) {
                JsonFailure.WRITE -> {
                    assertTrue(actual.isNotEmpty())
                    assertTrue("new".startsWith(actual))
                    assertNotEquals("new", actual)
                }

                JsonFailure.CLOSE -> assertEquals("new", actual)
            }
            base.checkNoOpenFiles()
        }
    }
}

private class TemporaryHandleFailingFileSystem(
    delegate: FileSystem,
    private val failurePoint: TemporaryNbtFailure,
) : ForwardingFileSystem(delegate) {
    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.startsWith(".tmp-")) return fileHandle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = fileHandle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                if (failurePoint == TemporaryNbtFailure.WRITE) {
                    val partial = minOf(byteCount, 1)
                    if (partial > 0) {
                        fileHandle.write(
                            fileOffset,
                            array,
                            arrayOffset,
                            partial,
                        )
                    }
                    throw IOException("synthetic temporary write failure")
                }
                fileHandle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() {
                fileHandle.flush()
                if (failurePoint == TemporaryNbtFailure.FLUSH) {
                    throw IOException("synthetic temporary flush failure")
                }
            }

            override fun protectedResize(size: Long) {
                if (failurePoint == TemporaryNbtFailure.RESIZE) {
                    throw IOException("synthetic temporary resize failure")
                }
                fileHandle.resize(size)
            }

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() {
                fileHandle.close()
                if (failurePoint == TemporaryNbtFailure.CLOSE) {
                    throw IOException("synthetic temporary close failure")
                }
            }
        }
    }
}

private enum class TemporaryNbtFailure {
    RESIZE,
    WRITE,
    FLUSH,
    CLOSE,
}

private class PromotionFailingFileSystem(
    delegate: FileSystem,
    private val primary: Path,
    private val previous: Path,
) : ForwardingFileSystem(delegate) {
    var attempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (source == previous && target == primary) {
            attempts++
            throw IOException("synthetic promotion failure")
        }
        super.atomicMove(source, target)
    }
}

private class CorruptedMoveFailingFileSystem(
    delegate: FileSystem,
    private val primary: Path,
) : ForwardingFileSystem(delegate) {
    var attempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (
            source == primary &&
            target.name.startsWith("level.dat_corrupted_")
        ) {
            attempts++
            throw IOException("synthetic corrupted move failure")
        }
        super.atomicMove(source, target)
    }
}

private class SelectedMoveThrowingFileSystem(
    delegate: FileSystem,
    private val source: Path,
    private val failure: Throwable,
    private var failures: Int = Int.MAX_VALUE,
    private val matchesTarget: (Path) -> Boolean,
) : ForwardingFileSystem(delegate) {
    var attempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (source == this.source && matchesTarget(target)) {
            attempts++
            if (failures > 0) {
                failures--
                throw failure
            }
        }
        super.atomicMove(source, target)
    }
}

private class SecondPrimarySourceThrowingFileSystem(
    delegate: FileSystem,
    private val primary: Path,
    private val failure: Throwable,
) : ForwardingFileSystem(delegate) {
    private var primarySourceCalls = 0

    override fun source(file: Path): Source {
        if (file == primary) {
            primarySourceCalls++
            if (primarySourceCalls == 2) {
                throw failure
            }
        }
        return super.source(file)
    }
}

private class CancellationSourceFileSystem(
    delegate: FileSystem,
    private val primary: Path,
) : ForwardingFileSystem(delegate) {
    override fun source(file: Path): Source {
        if (file == primary) throw CancellationException("cancelled")
        return super.source(file)
    }
}

private class SourceThrowingFileSystem(
    delegate: FileSystem,
    private val target: Path,
    private val failure: Throwable,
) : ForwardingFileSystem(delegate) {
    override fun source(file: Path): Source {
        if (file == target) throw failure
        return super.source(file)
    }
}

private class ShortReadHandleFileSystem(
    delegate: FileSystem,
    private val target: Path,
) : ForwardingFileSystem(delegate) {
    override fun openReadOnly(file: Path): FileHandle {
        val fileHandle = super.openReadOnly(file)
        if (file != target) return fileHandle
        return object : FileHandle(readWrite = false) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = fileHandle.read(
                fileOffset,
                array,
                arrayOffset,
                minOf(byteCount, 1),
            )

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) = fileHandle.write(fileOffset, array, arrayOffset, byteCount)

            override fun protectedFlush() = fileHandle.flush()

            override fun protectedResize(size: Long) = fileHandle.resize(size)

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() = fileHandle.close()
        }
    }
}

private class JsonSinkFailingFileSystem(
    delegate: FileSystem,
    private val target: Path,
    private val failurePoint: JsonFailure,
) : ForwardingFileSystem(delegate) {
    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val sink = super.sink(file, mustCreate)
        if (file != target) return sink
        return object : Sink by sink {
            override fun write(source: Buffer, byteCount: Long) {
                if (failurePoint == JsonFailure.WRITE) {
                    val partial = minOf(byteCount, 1)
                    if (partial > 0) sink.write(source, partial)
                    throw IOException("synthetic JSON write failure")
                }
                sink.write(source, byteCount)
            }

            override fun close() {
                sink.close()
                if (failurePoint == JsonFailure.CLOSE) {
                    throw IOException("synthetic JSON close failure")
                }
            }
        }
    }
}

private class CorruptedCopySinkFailingFileSystem(
    delegate: FileSystem,
    private val failurePoint: CorruptedCopySinkFailure,
) : ForwardingFileSystem(delegate) {
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.startsWith("player.dat_corrupted_")) return fileHandle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = fileHandle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                if (failurePoint == CorruptedCopySinkFailure.WRITE) {
                    val partial = minOf(byteCount, 1)
                    if (partial > 0) fileHandle.write(fileOffset, array, arrayOffset, partial)
                    throw IOException("synthetic corrupted-copy write failure")
                }
                fileHandle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() = fileHandle.flush()

            override fun protectedResize(size: Long) = fileHandle.resize(size)

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() {
                fileHandle.close()
                if (failurePoint == CorruptedCopySinkFailure.CLOSE) {
                    throw IOException("synthetic corrupted-copy close failure")
                }
            }
        }
    }
}

private enum class CorruptedCopySinkFailure {
    WRITE,
    CLOSE,
}

private enum class JsonFailure {
    WRITE,
    CLOSE,
}

@Serializable
private data class RequiredValue(
    @SerialName("Required")
    val required: Int,
)

private fun edgeDocument(value: Int): NbtDocument = NbtDocument(
    NbtCompound(
        linkedMapOf(
            "DataVersion" to NbtInt(4_000),
            "Value" to NbtInt(value),
            "Name" to NbtString("edge\u0000case"),
        ),
    ),
)

private fun FileSystem.writeRaw(path: Path, bytes: ByteArray) {
    path.parent?.let(::createDirectories)
    val sink = sink(path)
    val buffer = Buffer().apply { write(bytes) }
    try {
        sink.write(buffer, bytes.size.toLong())
    } finally {
        sink.close()
    }
}
