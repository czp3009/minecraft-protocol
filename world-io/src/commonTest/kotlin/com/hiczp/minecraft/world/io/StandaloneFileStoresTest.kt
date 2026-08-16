package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.world.format.Compression
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class StandaloneFileStoresTest {
    @Test
    fun physicalNbtFilesRoundTripEverySupportedWrapper() = runTest {
        val fileSystem = FakeFileSystem()
        val store = NbtFileStore(fileSystem)
        val document = sampleDocument(1)

        standaloneFileCompressions.forEach { compression ->
            val path = "/world/${compression.name}.dat".toPath()
            store.writeDirect(path, document, compression)
            assertEquals(document, store.read(path, compression))
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun levelWritesBackUpAndFallbackPromotesTheOldFile() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(fileSystem)
        val level = LevelDataStore(paths, nbt)
        val first = sampleDocument(1)
        val second = sampleDocument(2)

        level.write(first)
        level.write(second)
        assertEquals(second, nbt.read(paths.levelData))
        assertEquals(first, nbt.read(paths.previousLevelData))

        fileSystem.writeRaw(paths.levelData, byteArrayOf(1, 2, 3))
        assertEquals(first, level.read())
        assertEquals(first, nbt.read(paths.levelData))
        assertFalse(fileSystem.exists(paths.previousLevelData))
        assertTrue(
            fileSystem.list(paths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
        assertTrue(fileSystem.allPaths().none { it.name.startsWith(".tmp-") })
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun playerFallbackCopiesCorruptionButDoesNotPromoteOldData() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(fileSystem)
        val players = PlayerDataStore(paths, nbt)
        val player = "00000000-0000-0000-0000-000000000000"
        val previous = sampleDocument(7)

        nbt.writeDirect(paths.previousPlayerData(player), previous)
        fileSystem.writeRaw(paths.playerData(player), byteArrayOf(1, 2, 3))

        assertEquals(previous, players.read(player))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fileSystem.readRaw(paths.playerData(player)),
        )
        assertEquals(previous, nbt.read(paths.previousPlayerData(player)))
        assertTrue(
            fileSystem.list(checkNotNull(paths.playerData(player).parent)).any {
                it.name.startsWith("${paths.playerData(player).name}_corrupted_")
            },
        )
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun savedDataDetectsLegacyUncompressedNbtAndWritesCurrentGzip() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbt = NbtFileStore(fileSystem)
        val saved = SavedDataFileStore(paths, nbtFiles = nbt)
        val path = paths.savedData("maps/map_1")
        val legacy = sampleDocument(1)
        val current = sampleDocument(2)

        nbt.writeDirect(path, legacy, Compression.NONE)
        assertEquals(legacy, saved.read("maps/map_1"))
        saved.write("maps/map_1", current)
        assertEquals(current, saved.read("maps/map_1"))
        assertContentEquals(
            byteArrayOf(0x1F, 0x8B.toByte()),
            fileSystem.readRaw(path).copyOfRange(0, 2),
        )
    }

    @Test
    fun jsonWritesDirectlyTruncateWithoutBackupOrTemporaryFiles() {
        val fileSystem = FakeFileSystem()
        val store = Utf8JsonFileStore(fileSystem)
        val path = "/world/players/stats/player.json".toPath()

        store.write(path, "{\"long\":true}")
        store.write(path, "{}")

        assertEquals("{}", store.read(path))
        assertEquals(setOf(path), fileSystem.allPaths().filter { fileSystem.metadata(it).isRegularFile }.toSet())
    }

    @Test
    fun failedFinalReplacementRestoresTheBackedUpLevelData() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val initialStore = LevelDataStore(paths, NbtFileStore(base))
        val first = sampleDocument(1)
        initialStore.write(first)
        val failing = ReplacementFailingFileSystem(base, paths.levelData)
        val level = LevelDataStore(paths, NbtFileStore(failing))

        assertFailsWith<WorldIOException> {
            level.write(sampleDocument(2))
        }

        assertEquals(first, NbtFileStore(base).read(paths.levelData))
        assertEquals(10, failing.replacementAttempts)
        assertFalse(base.exists(paths.previousLevelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun failedBackupMovePreservesPrimaryAndCleansTheTemporaryFile() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        LevelDataStore(paths, NbtFileStore(base)).write(sampleDocument(1))
        val failing = BackupMoveFailingFileSystem(
            delegate = base,
            primary = paths.levelData,
            backup = paths.previousLevelData,
        )

        assertFailsWith<WorldIOException> {
            LevelDataStore(paths, NbtFileStore(failing))
                .write(sampleDocument(2))
        }

        assertEquals(
            sampleDocument(1),
            NbtFileStore(base).read(paths.levelData),
        )
        assertEquals(10, failing.attempts)
        assertFalse(base.exists(paths.previousLevelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun failedRollbackLeavesTheOfficialBackupBoundaryVisible() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val first = sampleDocument(1)
        LevelDataStore(paths, NbtFileStore(base)).write(first)
        val failing = ReplacementAndRollbackFailingFileSystem(
            delegate = base,
            primary = paths.levelData,
            backup = paths.previousLevelData,
        )

        val failure = assertFailsWith<WorldIOException> {
            LevelDataStore(paths, NbtFileStore(failing))
                .write(sampleDocument(2))
        }

        assertFalse(base.exists(paths.levelData))
        assertEquals(
            first,
            NbtFileStore(base).read(paths.previousLevelData),
        )
        assertEquals(10, failing.replacementAttempts)
        assertEquals(10, failing.rollbackAttempts)
        assertTrue(failure.suppressedExceptions.isNotEmpty())
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun temporaryNbtFailureDoesNotReplaceThePrimaryFile() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val first = sampleDocument(1)
        LevelDataStore(paths, NbtFileStore(base)).write(first)
        val limited = NbtFileStore(
            fileSystem = base,
            configuration = NbtFileStoreConfiguration(
                maximumCompressedBytes = 1_048_576,
                maximumDecompressedBytes = 1,
            ),
        )

        assertFailsWith<WorldIOException> {
            LevelDataStore(paths, limited).write(sampleDocument(2))
        }

        assertEquals(first, NbtFileStore(base).read(paths.levelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun limitsAndInvalidSavedDataIdentifiersAreRejected() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        assertFailsWith<IllegalArgumentException> {
            paths.savedData("a/../b")
        }
        assertFailsWith<WorldIOException> {
            Utf8JsonFileStore(fileSystem, maximumBytes = 1)
                .write("/world/value.json".toPath(), "\u00E9")
        }
        assertNotEquals(
            sampleDocument(1),
            sampleDocument(2),
        )
    }
}

// Standalone NBT files officially use the GZIP and NONE wrappers; ZLIB remains selectable for callers that need it.
private val standaloneFileCompressions = listOf(Compression.NONE, Compression.GZIP, Compression.ZLIB)

private class ReplacementFailingFileSystem(
    delegate: FileSystem,
    private val target: Path,
) : ForwardingFileSystem(delegate) {
    var replacementAttempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (target == this.target && source.name.startsWith(".tmp-")) {
            replacementAttempts++
            throw IOException("synthetic replacement failure")
        }
        super.atomicMove(source, target)
    }
}

private class BackupMoveFailingFileSystem(
    delegate: FileSystem,
    private val primary: Path,
    private val backup: Path,
) : ForwardingFileSystem(delegate) {
    var attempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (source == primary && target == backup) {
            attempts++
            throw IOException("synthetic backup failure")
        }
        super.atomicMove(source, target)
    }
}

private class ReplacementAndRollbackFailingFileSystem(
    delegate: FileSystem,
    private val primary: Path,
    private val backup: Path,
) : ForwardingFileSystem(delegate) {
    var replacementAttempts = 0
        private set
    var rollbackAttempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (target == primary && source.name.startsWith(".tmp-")) {
            replacementAttempts++
            throw IOException("synthetic replacement or rollback failure")
        }
        if (target == primary && source == backup) {
            rollbackAttempts++
            throw IOException("synthetic replacement or rollback failure")
        }
        super.atomicMove(source, target)
    }
}

private fun sampleDocument(value: Int): NbtDocument = NbtDocument(
    NbtCompound(
        linkedMapOf(
            "DataVersion" to NbtInt(4_000),
            "Value" to NbtInt(value),
            "Name" to NbtString("test\u0000world"),
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
    readFileWithinLimit(path, Int.MAX_VALUE)

private fun FakeFileSystem.allPaths(): Set<Path> = allPaths
