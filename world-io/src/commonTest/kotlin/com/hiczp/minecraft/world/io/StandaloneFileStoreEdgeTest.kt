package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.world.format.Compression
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class StandaloneFileStoreEdgeTest {
    @Test
    fun nbtReadsRejectTrailingBytesWithoutPolicyLimits() = runTest {
        val fileSystem = FakeFileSystem()
        val path = "/world/value.dat".toPath()
        val normal = NbtFileStore(fileSystem)
        normal.writeDirect(path, edgeDocument(1), Compression.NONE)
        val original = fileSystem.readRaw(path)
        fileSystem.writeRaw(
            path,
            original.copyOf(original.size + 1).also { it[it.lastIndex] = 1 },
        )
        assertFailsWith<NbtDecodingException> {
            normal.read(path, Compression.NONE)
        }

        fileSystem.writeRaw(path, original)
        assertEquals(edgeDocument(1), normal.read(path, Compression.NONE))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun directNbtStreamingFailureLeavesTheOfficialTruncatedBoundary() = runTest {
        val fileSystem = FakeFileSystem()
        val path = "/world/data/value.dat".toPath()
        val normal = NbtFileStore(fileSystem)
        normal.writeDirect(path, edgeDocument(1))
        val oldBytes = fileSystem.readRaw(path)
        val failure = WorldIOException("synthetic streaming failure")

        assertSame(failure, assertFails { normal.writeDirect(path) { throw failure } })

        val failedBytes = fileSystem.readRaw(path)
        assertFalse(oldBytes.contentEquals(failedBytes))
        assertFails { normal.read(path) }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun everyTemporaryNbtIoFailureCleansUpWithoutReplacingPrimary() = runTest {
        TemporaryNbtFailure.entries.forEach { failurePoint ->
            val base = FakeFileSystem()
            val paths = MinecraftWorldPaths(
                "/world-${failurePoint.name}".toPath(),
            )
            val first = edgeDocument(1)
            LevelDataStore(paths, NbtFileStore(base)).write(first)
            val failing = TemporaryHandleFailingFileSystem(
                base,
                failurePoint,
            )

            val failure = assertFails {
                LevelDataStore(paths, NbtFileStore(failing))
                    .write(edgeDocument(2))
            }
            assertIs<IOException>(failure)

            assertEquals(first, NbtFileStore(base).read(paths.levelData))
            assertFalse(base.exists(paths.previousLevelData))
            assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
            base.checkNoOpenFiles()
        }
    }

    @Test
    fun levelPrimaryWinsAndMissingPrimaryPromotesPreviousWithoutCorruption() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(fileSystem)
        val level = LevelDataStore(paths, nbt)
        val primary = edgeDocument(1)
        val previous = edgeDocument(2)
        nbt.writeDirect(paths.levelData, primary)
        nbt.writeDirect(paths.previousLevelData, previous)

        assertEquals(primary, level.read())
        assertEquals(previous, nbt.read(paths.previousLevelData))

        fileSystem.delete(paths.levelData)
        assertEquals(previous, level.read())
        assertEquals(previous, nbt.read(paths.levelData))
        assertFalse(fileSystem.exists(paths.previousLevelData))
        assertTrue(
            fileSystem.list(paths.root).none {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
    }

    @Test
    fun levelReportsPrimaryFailureWithFallbackAndPromotionFailuresSuppressed() = runTest {
        val empty = FakeFileSystem()
        val emptyPaths = MinecraftWorldPaths("/empty".toPath())
        val missing = assertFailsWith<WorldIOException> {
            LevelDataStore(emptyPaths, NbtFileStore(empty)).read()
        }
        assertEquals(1, missing.suppressedExceptions.size)

        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(base)
        base.writeRaw(paths.levelData, byteArrayOf(1, 2, 3))
        nbt.writeDirect(paths.previousLevelData, edgeDocument(7))
        val failing = PromotionFailingFileSystem(
            base,
            paths.levelData,
            paths.previousLevelData,
        )

        val promotionFailure = assertFails {
            LevelDataStore(paths, NbtFileStore(failing)).read()
        }

        assertTrue(promotionFailure.suppressedExceptions.isNotEmpty())
        assertEquals(10, failing.attempts)
        assertFalse(base.exists(paths.levelData))
        assertTrue(base.exists(paths.previousLevelData))
        assertTrue(
            base.list(paths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
    }

    @Test
    fun levelCorruptedMoveFailureKeepsBothOriginalFiles() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(base)
        val corrupt = byteArrayOf(1, 2, 3)
        base.writeRaw(paths.levelData, corrupt)
        nbt.writeDirect(paths.previousLevelData, edgeDocument(4))
        val failing = CorruptedMoveFailingFileSystem(base, paths.levelData)

        val failure = assertFails {
            LevelDataStore(paths, NbtFileStore(failing)).read()
        }

        assertTrue(failure.suppressedExceptions.isNotEmpty())
        assertEquals(10, failing.attempts)
        assertContentEquals(corrupt, base.readRaw(paths.levelData))
        assertTrue(base.exists(paths.previousLevelData))
        assertTrue(
            base.list(paths.root).none {
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
        NbtFileStore(displacementBase).writeDirect(
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
            ).read(),
        )
        assertEquals(3, displacement.attempts)
        assertEquals(
            displacementPrevious,
            NbtFileStore(displacementBase).read(
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
            }.let(displacementBase::readRaw),
        )

        val promotionBase = FakeFileSystem()
        val promotionPaths = MinecraftWorldPaths("/promotion".toPath())
        val promotionCorrupt = byteArrayOf(4, 5, 6)
        val promotionPrevious = edgeDocument(12)
        promotionBase.writeRaw(promotionPaths.levelData, promotionCorrupt)
        NbtFileStore(promotionBase).writeDirect(
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
            ).read(),
        )
        assertEquals(3, promotion.attempts)
        assertEquals(
            promotionPrevious,
            NbtFileStore(promotionBase).read(promotionPaths.levelData),
        )
        assertFalse(promotionBase.exists(promotionPaths.previousLevelData))
        assertContentEquals(
            promotionCorrupt,
            promotionBase.list(promotionPaths.root).single {
                it.name.startsWith("level.dat_corrupted_")
            }.let(promotionBase::readRaw),
        )
    }

    @Test
    fun cancellationDoesNotTriggerLevelFallback() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        base.writeRaw(paths.levelData, byteArrayOf(1))
        NbtFileStore(base).writeDirect(
            paths.previousLevelData,
            edgeDocument(1),
        )
        val cancelling = CancellationSourceFileSystem(
            base,
            paths.levelData,
        )

        assertFailsWith<CancellationException> {
            LevelDataStore(paths, NbtFileStore(cancelling)).read()
        }
        assertTrue(base.exists(paths.previousLevelData))
        assertContentEquals(byteArrayOf(1), base.readRaw(paths.levelData))
    }

    @Test
    fun cancellationDuringLevelFallbackIsNotConsumed() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        base.writeRaw(paths.levelData, byteArrayOf(1))
        NbtFileStore(base).writeDirect(
            paths.previousLevelData,
            edgeDocument(1),
        )
        val cancelling = CancellationSourceFileSystem(
            base,
            paths.previousLevelData,
        )

        assertFailsWith<CancellationException> {
            LevelDataStore(paths, NbtFileStore(cancelling)).read()
        }
        assertTrue(base.exists(paths.previousLevelData))
        assertContentEquals(byteArrayOf(1), base.readRaw(paths.levelData))
    }

    @Test
    fun cancellationDuringEitherLevelPromotionMoveIsNotConsumed() = runTest {
        val corruptedMoveBase = FakeFileSystem()
        val corruptedMovePaths = MinecraftWorldPaths("/corrupted".toPath())
        corruptedMoveBase.writeRaw(
            corruptedMovePaths.levelData,
            byteArrayOf(1, 2, 3),
        )
        NbtFileStore(corruptedMoveBase).writeDirect(
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
                ).read()
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
        NbtFileStore(previousMoveBase).writeDirect(
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
                ).read()
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
        NbtFileStore(fallbackBase).writeDirect(
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
                ).read()
            },
        )
        assertContentEquals(
            byteArrayOf(1),
            fallbackBase.readRaw(fallbackPaths.levelData),
        )
        assertTrue(fallbackBase.exists(fallbackPaths.previousLevelData))

        val promotionBase = FakeFileSystem()
        val promotionPaths = MinecraftWorldPaths("/level-promotion".toPath())
        promotionBase.writeRaw(promotionPaths.levelData, byteArrayOf(2))
        NbtFileStore(promotionBase).writeDirect(
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
                ).read()
            },
        )
        assertEquals(1, promotionFailing.attempts)
        assertContentEquals(
            byteArrayOf(2),
            promotionBase.readRaw(promotionPaths.levelData),
        )
        assertTrue(promotionBase.exists(promotionPaths.previousLevelData))
    }

    @Test
    fun playerReadCoversMissingPrimaryPreviousAndFailureBranches() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(fileSystem)
        val players = PlayerDataStore(paths, nbt)
        val player = "player"
        val previous = edgeDocument(1)

        assertNull(players.read(player))
        nbt.writeDirect(paths.previousPlayerData(player), previous)
        assertEquals(previous, players.read(player))
        assertFalse(fileSystem.exists(paths.playerData(player)))

        val primary = edgeDocument(2)
        nbt.writeDirect(paths.playerData(player), primary)
        assertEquals(primary, players.read(player))

        fileSystem.writeRaw(paths.playerData(player), byteArrayOf(1, 2, 3))
        fileSystem.writeRaw(paths.previousPlayerData(player), byteArrayOf(4, 5))
        val failure = assertFails { players.read(player) }
        assertTrue(failure.suppressedExceptions.isNotEmpty())
        assertTrue(
            fileSystem.list(checkNotNull(paths.playerData(player).parent)).any {
                it.name.startsWith("player.dat_corrupted_")
            },
        )

        val withoutPrevious = "without-previous"
        val corrupt = byteArrayOf(9, 8, 7)
        fileSystem.writeRaw(paths.playerData(withoutPrevious), corrupt)
        val primaryFailure = assertFails {
            players.read(withoutPrevious)
        }
        assertTrue(primaryFailure.suppressedExceptions.isEmpty())
        assertContentEquals(
            corrupt,
            fileSystem.list(
                checkNotNull(paths.playerData(withoutPrevious).parent),
            ).single {
                it.name.startsWith(
                    "without-previous.dat_corrupted_",
                )
            }.let(fileSystem::readRaw),
        )
        assertContentEquals(
            corrupt,
            fileSystem.readRaw(paths.playerData(withoutPrevious)),
        )
    }

    @Test
    fun playerCorruptionCopyFailureDoesNotBlockFallback() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val previous = edgeDocument(8)
        base.writeRaw(paths.playerData(player), byteArrayOf(1, 2, 3))
        NbtFileStore(base).writeDirect(
            paths.previousPlayerData(player),
            previous,
        )
        val failing = SecondPrimarySourceThrowingFileSystem(
            base,
            paths.playerData(player),
            IOException("synthetic corruption copy failure"),
        )

        assertEquals(
            previous,
            PlayerDataStore(paths, NbtFileStore(failing)).read(player),
        )
        assertTrue(
            base.list(checkNotNull(paths.playerData(player).parent)).none {
                it.name.startsWith("player.dat_corrupted_")
            },
        )
    }

    @Test
    fun playerCorruptionCopyWriteAndCloseFailuresCleanUpAndFallback() = runTest {
        CorruptedCopySinkFailure.entries.forEach { failurePoint ->
            val base = FakeFileSystem()
            val paths = MinecraftWorldPaths(
                "/copy-${failurePoint.name}".toPath(),
            )
            val player = "player"
            val previous = edgeDocument(failurePoint.ordinal + 20)
            base.writeRaw(
                paths.playerData(player),
                byteArrayOf(1, 2, 3),
            )
            NbtFileStore(base).writeDirect(
                paths.previousPlayerData(player),
                previous,
            )
            val failing = CorruptedCopySinkFailingFileSystem(
                base,
                failurePoint,
            )

            assertEquals(
                previous,
                PlayerDataStore(paths, NbtFileStore(failing)).read(player),
            )
            assertTrue(
                base.list(checkNotNull(paths.playerData(player).parent)).none {
                    it.name.startsWith("player.dat_corrupted_")
                },
            )
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                base.readRaw(paths.playerData(player)),
            )
            base.checkNoOpenFiles()
        }
    }

    @Test
    fun cancellationDoesNotTriggerPlayerFallbackOrCorruptionCopy() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        base.writeRaw(paths.playerData(player), byteArrayOf(1))
        NbtFileStore(base).writeDirect(
            paths.previousPlayerData(player),
            edgeDocument(1),
        )
        val cancelling = CancellationSourceFileSystem(
            base,
            paths.playerData(player),
        )

        assertFailsWith<CancellationException> {
            PlayerDataStore(paths, NbtFileStore(cancelling)).read(player)
        }
        assertTrue(
            base.list(checkNotNull(paths.playerData(player).parent)).none {
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
        NbtFileStore(copyBase).writeDirect(
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
                ).read(player)
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
        NbtFileStore(fallbackBase).writeDirect(
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
            ).read(player)
        }
        assertTrue(fallbackBase.exists(fallbackPaths.previousPlayerData(player)))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fallbackBase.readRaw(fallbackPaths.playerData(player)),
        )
    }

    @Test
    fun unexpectedPlayerCopyAndFallbackFailuresPropagateUnchanged() = runTest {
        val copyBase = FakeFileSystem()
        val copyPaths = MinecraftWorldPaths("/player-copy-runtime".toPath())
        val player = "player"
        copyBase.writeRaw(copyPaths.playerData(player), byteArrayOf(1, 2, 3))
        NbtFileStore(copyBase).writeDirect(
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
                ).read(player)
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
        NbtFileStore(fallbackBase).writeDirect(
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
                ).read(player)
            },
        )
        assertContentEquals(
            byteArrayOf(4, 5, 6),
            fallbackBase.readRaw(fallbackPaths.playerData(player)),
        )
        assertTrue(fallbackBase.exists(fallbackPaths.previousPlayerData(player)))
    }

    @Test
    fun playerWritesBackUpOnlyAfterTheFirstSuccessfulSave() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(fileSystem)
        val players = PlayerDataStore(paths, nbt)
        val player = "player"

        players.write(player, edgeDocument(1))
        assertFalse(fileSystem.exists(paths.previousPlayerData(player)))
        players.write(player, edgeDocument(2))

        assertEquals(edgeDocument(2), nbt.read(paths.playerData(player)))
        assertEquals(
            edgeDocument(1),
            nbt.read(paths.previousPlayerData(player)),
        )
    }

    @Test
    fun savedDataCoversMissingShortMagicAndNamespacedPaths() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val saved = SavedDataFileStore(paths, nbtFiles = NbtFileStore(base))
        assertNull(saved.read("missing"))

        val identifier = "example:state/value"
        val path = paths.savedData(identifier)
        NbtFileStore(base).writeDirect(
            path,
            edgeDocument(3),
            Compression.NONE,
        )
        val shortReads = ShortReadHandleFileSystem(base, path)
        assertEquals(
            edgeDocument(3),
            SavedDataFileStore(
                paths,
                nbtFiles = NbtFileStore(shortReads),
            ).read(identifier),
        )

        base.writeRaw(path, byteArrayOf(0x1F))
        assertFails { saved.read(identifier) }
        base.checkNoOpenFiles()
    }

    @Test
    fun jsonReadsAndWritesHaveNoPolicyLimitAndRejectNonFiles() {
        val fileSystem = FakeFileSystem()
        val store = Utf8JsonFileStore(fileSystem)
        val path = "/world/value.json".toPath()

        assertFailsWith<WorldIOException> { store.read(path) }
        fileSystem.createDirectories(path)
        assertFailsWith<WorldIOException> { store.read(path) }
        fileSystem.delete(path)
        store.write(path, "{}")
        assertEquals("{}", store.read(path))
        store.write(path, "\u00E9x")
        assertEquals("\u00E9x", store.read(path))
    }

    @Test
    fun jsonWriteAndCloseFailuresExposeDirectFinalPathBoundaries() {
        JsonFailure.entries.forEach { failurePoint ->
            val base = FakeFileSystem()
            val path = "/world-${failurePoint.name}/value.json".toPath()
            Utf8JsonFileStore(base).write(path, "old")
            val failing = JsonSinkFailingFileSystem(
                base,
                path,
                failurePoint,
            )

            assertFailsWith<IOException> {
                Utf8JsonFileStore(failing).write(path, "new")
            }

            val expected = when (failurePoint) {
                JsonFailure.WRITE -> "n"
                JsonFailure.CLOSE -> "new"
            }
            assertEquals(expected, Utf8JsonFileStore(base).read(path))
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
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.startsWith(".tmp-")) return handle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = handle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                if (failurePoint == TemporaryNbtFailure.WRITE) {
                    val partial = minOf(byteCount, 1)
                    if (partial > 0) {
                        handle.write(
                            fileOffset,
                            array,
                            arrayOffset,
                            partial,
                        )
                    }
                    throw IOException("synthetic temporary write failure")
                }
                handle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() {
                handle.flush()
                if (failurePoint == TemporaryNbtFailure.FLUSH) {
                    throw IOException("synthetic temporary flush failure")
                }
            }

            override fun protectedResize(size: Long) {
                if (failurePoint == TemporaryNbtFailure.RESIZE) {
                    throw IOException("synthetic temporary resize failure")
                }
                handle.resize(size)
            }

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() {
                handle.close()
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
        val handle = super.openReadOnly(file)
        if (file != target) return handle
        return object : FileHandle(readWrite = false) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = handle.read(
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
            ) = handle.write(fileOffset, array, arrayOffset, byteCount)

            override fun protectedFlush() = handle.flush()

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() = handle.close()
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
    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val sink = super.sink(file, mustCreate)
        if (!file.name.startsWith("player.dat_corrupted_")) return sink
        return object : Sink by sink {
            override fun write(source: Buffer, byteCount: Long) {
                if (failurePoint == CorruptedCopySinkFailure.WRITE) {
                    val partial = minOf(byteCount, 1)
                    if (partial > 0) sink.write(source, partial)
                    throw IOException("synthetic corrupted-copy write failure")
                }
                sink.write(source, byteCount)
            }

            override fun close() {
                sink.close()
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

private fun FileSystem.readRaw(path: Path): ByteArray =
    readFileBytes(path)
