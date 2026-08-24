package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.LevelDat
import com.hiczp.minecraft.world.format.PlayerAdvancements
import com.hiczp.minecraft.world.format.PlayerStatistics
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class StandaloneFileStoresTest {
    @Test
    fun physicalNbtStoreRequiresStandaloneRootEncoding() {
        assertFailsWith<IllegalArgumentException> {
            NbtFileStore(FakeFileSystem(), NbtFormat)
        }
    }

    @Test
    fun physicalNbtFilesRoundTripEverySupportedWrapper() = runTest {
        val fileSystem = FakeFileSystem()
        val store = NbtFileStore(fileSystem)
        val document = sampleDocument(1)

        standaloneFileCompressions.forEach { compression ->
            val path = "/world/${compression.name}.dat".toPath()
            store.writeDocument(path, document, compression)
            assertEquals(document, store.readDocument(path, compression))
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun physicalNbtFilesStreamBothDirectionsForEverySupportedWrapper() {
        val fileSystem = FakeFileSystem()
        val store = NbtFileStore(fileSystem)
        val document = sampleDocument(7)

        standaloneFileCompressions.forEach { compression ->
            val path = "/world/stream-${compression.name}.dat".toPath()
            store.write(path, compression) { sink ->
                store.nbt.encodeDocumentToSink(document, sink)
            }
            assertEquals(
                document,
                store.read(path, compression) { source ->
                    store.nbt.decodeDocumentFromSource(source)
                },
            )
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun physicalNbtFilesDecodeCallerAndBuiltInTypesDirectlyFromTheCompressedStream() {
        val fileSystem = FakeFileSystem()
        val store = NbtFileStore(fileSystem)
        val callerPath = "/world/caller.dat".toPath()
        val callerValue = CallerLevelData(
            marker = 7,
            values = mapOf("one" to 1, "two" to 2),
        )
        val callerSerializer = CountingSerializer(CallerLevelData.serializer())

        store.write(callerPath, callerSerializer, callerValue)
        assertEquals(callerValue, store.read(callerPath, callerSerializer))
        assertEquals(1, callerSerializer.encodeCalls)
        assertEquals(1, callerSerializer.decodeCalls)

        val paths = MinecraftWorldPaths("/world".toPath())
        val level = testLevelDat(levelName = "world")
        val levelStore = LevelDataStore(paths, store)
        levelStore.write(LevelDat.serializer(), level)
        assertEquals(level, levelStore.read(LevelDat.serializer()))
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

        level.writeDocument(first)
        level.writeDocument(second)
        assertEquals(second, nbt.readDocument(paths.levelData))
        assertEquals(first, nbt.readDocument(paths.previousLevelData))

        fileSystem.writeRaw(paths.levelData, byteArrayOf(1, 2, 3))
        assertEquals(first, level.readDocument())
        assertEquals(first, nbt.readDocument(paths.levelData))
        assertFalse(fileSystem.exists(paths.previousLevelData))
        assertTrue(
            fileSystem.list(paths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
        assertTrue(fileSystem.allPaths.none { it.name.startsWith(".tmp-") })
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

        nbt.writeDocument(paths.previousPlayerData(player), previous)
        fileSystem.writeRaw(paths.playerData(player), byteArrayOf(1, 2, 3))

        assertEquals(previous, players.readDocument(player))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fileSystem.readFileBytes(paths.playerData(player)),
        )
        assertEquals(previous, nbt.readDocument(paths.previousPlayerData(player)))
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

        nbt.writeDocument(path, legacy, Compression.NONE)
        assertEquals(legacy, saved.readDocument("maps/map_1"))
        saved.writeDocument("maps/map_1", current)
        assertEquals(current, saved.readDocument("maps/map_1"))
        assertContentEquals(
            byteArrayOf(0x1F, 0x8B.toByte()),
            fileSystem.readFileBytes(path).copyOfRange(0, 2),
        )
    }

    @Test
    fun jsonWritesDirectlyTruncateWithoutBackupOrTemporaryFiles() {
        val fileSystem = FakeFileSystem()
        val store = Utf8JsonFileStore(fileSystem)
        val path = "/world/players/stats/player.json".toPath()

        store.writeText(path, "{\"long\":true}")
        store.writeText(path, "{}")

        assertEquals("{}", store.readText(path))
        assertEquals(setOf(path), fileSystem.allPaths.filter { fileSystem.metadata(it).isRegularFile }.toSet())
    }

    @Test
    fun jsonSupportsStructuredAndRawStreamingWithoutPolicyLimits() {
        val fileSystem = FakeFileSystem()
        val store = Utf8JsonFileStore(fileSystem)
        val path = "/world/advancements/player.json".toPath()
        val element = buildJsonObject {
            put("values", buildJsonArray { repeat(2_048) { add(JsonPrimitive(it)) } })
        }

        store.writeJson(path, element)
        assertEquals(element, store.readJson(path))

        val encoded = Json.encodeToString(element)
        store.write(path) { sink ->
            encoded.chunked(257).forEach(sink::writeString)
        }
        val copied = kotlinx.io.Buffer()
        val reads = mutableListOf<Long>()
        store.read(path) { source ->
            while (true) {
                val read = source.readAtMostTo(copied, 257L)
                if (read < 0L) break
                reads += read
            }
        }

        assertTrue(reads.size > 1)
        assertTrue(reads.all { it in 1L..257L })
        assertEquals(encoded, copied.readString())
    }

    @Test
    fun jsonFilesUseOneGenericSerializerPathForCallerCollectionsAndBuiltInModels() {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val store = Utf8JsonFileStore(fileSystem)
        val player = "00000000-0000-0000-0000-000000000000"
        val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
        val callerMap = mapOf("alpha" to 1, "beta" to 2)

        store.writeJson(paths.statistics(player), mapSerializer, callerMap)
        assertEquals(callerMap, store.readJson(paths.statistics(player), mapSerializer))

        val statistics = PlayerStatistics(
            stats = mapOf("minecraft:mined" to mapOf("minecraft:stone" to 42)),
            dataVersion = 4_903,
        )
        store.writeJson(paths.statistics(player), PlayerStatistics.serializer(), statistics)
        assertEquals(
            statistics,
            store.readJson(paths.statistics(player), PlayerStatistics.serializer()),
        )

        val advancements = PlayerAdvancements(
            dataVersion = 4_903,
            advancements = mapOf(
                "minecraft:story/root" to PlayerAdvancements.Progress(
                    criteria = mapOf("crafting_table" to "2026-08-18 00:00:00 +0000"),
                    done = true,
                ),
            ),
        )
        store.writeJson(paths.advancement(player), PlayerAdvancements.serializer(), advancements)
        assertEquals(
            advancements,
            store.readJson(paths.advancement(player), PlayerAdvancements.serializer()),
        )
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun typedNbtAndJsonPathsAcceptSmallSegmentedFileTransfers() {
        val base = FakeFileSystem()
        val segmented = SegmentingFileSystem(base, maxSegmentBytes = 31L)
        val nbtPath = "/world/segmented.dat".toPath()
        val nbtValue = CallerLevelData(
            marker = 9,
            values = (0 until 2_048).associate { index -> "key_$index" to index },
        )
        val nbtStore = NbtFileStore(segmented)
        nbtStore.write(nbtPath, CallerLevelData.serializer(), nbtValue)
        assertTrue(segmented.writeCalls > 1)
        assertEquals(nbtValue, nbtStore.read(nbtPath, CallerLevelData.serializer()))
        assertTrue(segmented.readCalls > 1)

        val jsonPath = "/world/segmented.json".toPath()
        val jsonValue = PlayerAdvancements(
            dataVersion = 4_903,
            advancements = (0 until 2_048).associate { index ->
                "example:advancement_$index" to PlayerAdvancements.Progress(
                    criteria = mapOf("criterion_$index" to "2026-08-18 00:00:00 +0000"),
                    done = index % 2 == 0,
                )
            },
        )
        val jsonStore = Utf8JsonFileStore(segmented)
        val writesBeforeJson = segmented.writeCalls
        jsonStore.writeJson(jsonPath, PlayerAdvancements.serializer(), jsonValue)
        assertTrue(segmented.writeCalls > writesBeforeJson + 1)
        val readsBeforeJson = segmented.readCalls
        assertEquals(jsonValue, jsonStore.readJson(jsonPath, PlayerAdvancements.serializer()))
        assertTrue(segmented.readCalls > readsBeforeJson + 1)
        base.checkNoOpenFiles()
    }

    @Test
    fun failedFinalReplacementRestoresTheBackedUpLevelData() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val initialStore = LevelDataStore(paths, NbtFileStore(base))
        val first = sampleDocument(1)
        initialStore.writeDocument(first)
        val failing = ReplacementFailingFileSystem(base, paths.levelData)
        val level = LevelDataStore(paths, NbtFileStore(failing))

        assertFailsWith<WorldIOException> {
            level.writeDocument(sampleDocument(2))
        }

        assertEquals(first, NbtFileStore(base).readDocument(paths.levelData))
        assertEquals(10, failing.replacementAttempts)
        assertFalse(base.exists(paths.previousLevelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun failedBackupMovePreservesPrimaryAndCleansTheTemporaryFile() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        LevelDataStore(paths, NbtFileStore(base)).writeDocument(sampleDocument(1))
        val failing = BackupMoveFailingFileSystem(
            delegate = base,
            primary = paths.levelData,
            backup = paths.previousLevelData,
        )

        assertFailsWith<WorldIOException> {
            LevelDataStore(paths, NbtFileStore(failing))
                .writeDocument(sampleDocument(2))
        }

        assertEquals(
            sampleDocument(1),
            NbtFileStore(base).readDocument(paths.levelData),
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
        LevelDataStore(paths, NbtFileStore(base)).writeDocument(first)
        val failing = ReplacementAndRollbackFailingFileSystem(
            delegate = base,
            primary = paths.levelData,
            backup = paths.previousLevelData,
        )

        val failure = assertFailsWith<WorldIOException> {
            LevelDataStore(paths, NbtFileStore(failing))
                .writeDocument(sampleDocument(2))
        }

        assertFalse(base.exists(paths.levelData))
        assertEquals(
            first,
            NbtFileStore(base).readDocument(paths.previousLevelData),
        )
        assertEquals(10, failing.replacementAttempts)
        assertEquals(10, failing.rollbackAttempts)
        assertTrue(failure.suppressedExceptions.isNotEmpty())
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun streamingNbtFailureDoesNotReplaceThePrimaryFile() = runTest {
        val base = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val first = sampleDocument(1)
        LevelDataStore(paths, NbtFileStore(base)).writeDocument(first)
        val failure = WorldIOException("synthetic streaming failure")

        assertSame(failure, assertFails { LevelDataStore(paths, NbtFileStore(base)).write { throw failure } })

        assertEquals(first, NbtFileStore(base).readDocument(paths.levelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun jsonHasNoPolicyLimitAndInvalidSavedDataIdentifiersAreRejected() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        assertFailsWith<IllegalArgumentException> {
            paths.savedData("a/../b")
        }
        val jsonPath = "/world/value.json".toPath()
        Utf8JsonFileStore(fileSystem).writeText(jsonPath, "\u00E9")
        assertEquals("\u00E9", Utf8JsonFileStore(fileSystem).readText(jsonPath))
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

private class SegmentingFileSystem(
    delegate: FileSystem,
    private val maxSegmentBytes: Long,
) : ForwardingFileSystem(delegate) {
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set

    init {
        require(maxSegmentBytes in 1L..Int.MAX_VALUE.toLong())
    }

    override fun source(file: Path): Source {
        val source = super.source(file)
        return object : ForwardingSource(source) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, minOf(byteCount, maxSegmentBytes))
                if (read >= 0L) readCalls++
                return read
            }
        }
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val sink = super.sink(file, mustCreate)
        return object : Sink by sink {
            override fun write(source: Buffer, byteCount: Long) {
                var remaining = byteCount
                while (remaining > 0L) {
                    val segment = minOf(remaining, maxSegmentBytes)
                    sink.write(source, segment)
                    writeCalls++
                    remaining -= segment
                }
            }
        }
    }

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = handle.read(
                fileOffset,
                array,
                arrayOffset,
                minOf(byteCount.toLong(), maxSegmentBytes).toInt(),
            )

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                var offset = arrayOffset
                var remaining = byteCount
                while (remaining > 0) {
                    val segment = minOf(remaining.toLong(), maxSegmentBytes).toInt()
                    handle.write(
                        fileOffset + (offset - arrayOffset).toLong(),
                        array,
                        offset,
                        segment,
                    )
                    writeCalls++
                    offset += segment
                    remaining -= segment
                }
            }

            override fun protectedFlush() = handle.flush()

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() = handle.close()
        }
    }
}

private class CountingSerializer<T>(
    private val delegate: KSerializer<T>,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor
        get() = delegate.descriptor
    var encodeCalls: Int = 0
        private set
    var decodeCalls: Int = 0
        private set

    override fun serialize(encoder: Encoder, value: T) {
        encodeCalls++
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T {
        decodeCalls++
        return delegate.deserialize(decoder)
    }
}

@Serializable
private data class CallerLevelData(
    val marker: Int,
    val values: Map<String, Int>,
)

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
