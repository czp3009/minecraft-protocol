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
        val fakeFileSystem = FakeFileSystem()
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val nbtDocument = sampleDocument(1)

        standaloneFileCompressions.forEach { compression ->
            val path = "/world/${compression.name}.dat".toPath()
            nbtFileStore.writeDocument(path, nbtDocument, compression)
            assertEquals(nbtDocument, nbtFileStore.readDocument(path, compression))
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun physicalNbtFilesStreamBothDirectionsForEverySupportedWrapper() {
        val fakeFileSystem = FakeFileSystem()
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val nbtDocument = sampleDocument(7)

        standaloneFileCompressions.forEach { compression ->
            val path = "/world/stream-${compression.name}.dat".toPath()
            nbtFileStore.write(path, compression) { sink ->
                nbtFileStore.nbtFormat.encodeDocumentToOkio(nbtDocument, sink)
            }
            assertEquals(
                nbtDocument,
                nbtFileStore.read(path, compression) { source ->
                    nbtFileStore.nbtFormat.decodeDocumentFromOkio(source)
                },
            )
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun physicalNbtFilesDecodeCallerAndBuiltInTypesDirectlyFromTheCompressedStream() {
        val fakeFileSystem = FakeFileSystem()
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val callerPath = "/world/caller.dat".toPath()
        val callerValue = CallerLevelData(
            marker = 7,
            values = mapOf("one" to 1, "two" to 2),
        )
        val callerSerializer = CountingSerializer(CallerLevelData.serializer())

        nbtFileStore.write(callerPath, callerSerializer, callerValue)
        assertEquals(callerValue, nbtFileStore.read(callerPath, callerSerializer))
        assertEquals(1, callerSerializer.encodeCalls)
        assertEquals(1, callerSerializer.decodeCalls)

        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val levelDat = testLevelDat(levelName = "world")
        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
        levelDataStore.write(LevelDat.serializer(), levelDat)
        assertEquals(levelDat, levelDataStore.read(LevelDat.serializer()))
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun levelWritesBackUpAndFallbackPromotesTheOldFile() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
        val first = sampleDocument(1)
        val second = sampleDocument(2)

        levelDataStore.writeDocument(first)
        levelDataStore.writeDocument(second)
        assertEquals(second, nbtFileStore.readDocument(minecraftWorldPaths.levelData))
        assertEquals(first, nbtFileStore.readDocument(minecraftWorldPaths.previousLevelData))

        fakeFileSystem.writeRaw(minecraftWorldPaths.levelData, byteArrayOf(1, 2, 3))
        assertEquals(first, levelDataStore.readDocument())
        assertEquals(first, nbtFileStore.readDocument(minecraftWorldPaths.levelData))
        assertFalse(fakeFileSystem.exists(minecraftWorldPaths.previousLevelData))
        assertTrue(
            fakeFileSystem.list(minecraftWorldPaths.root).any {
                it.name.startsWith("level.dat_corrupted_")
            },
        )
        assertTrue(fakeFileSystem.allPaths.none { it.name.startsWith(".tmp-") })
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun playerFallbackCopiesCorruptionButDoesNotPromoteOldData() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
        val player = "00000000-0000-0000-0000-000000000000"
        val previous = sampleDocument(7)

        nbtFileStore.writeDocument(minecraftWorldPaths.previousPlayerData(player), previous)
        fakeFileSystem.writeRaw(minecraftWorldPaths.playerData(player), byteArrayOf(1, 2, 3))

        assertEquals(previous, playerDataStore.readDocument(player))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fakeFileSystem.readFileBytes(minecraftWorldPaths.playerData(player)),
        )
        assertEquals(previous, nbtFileStore.readDocument(minecraftWorldPaths.previousPlayerData(player)))
        assertTrue(
            fakeFileSystem.list(checkNotNull(minecraftWorldPaths.playerData(player).parent)).any {
                it.name.startsWith("${minecraftWorldPaths.playerData(player).name}_corrupted_")
            },
        )
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun savedDataDetectsLegacyUncompressedNbtAndWritesCurrentGzip() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val savedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionDirectory.Overworld),
            nbtFileStore,
        )
        val path = minecraftWorldPaths.savedData("maps/map_1", SavedDataScope.Dimension(DimensionDirectory.Overworld))
        val legacy = sampleDocument(1)
        val current = sampleDocument(2)

        nbtFileStore.writeDocument(path, legacy, Compression.NONE)
        assertEquals(legacy, savedDataStore.readDocument("maps/map_1"))
        savedDataStore.writeDocument("maps/map_1", current)
        assertEquals(current, savedDataStore.readDocument("maps/map_1"))
        assertContentEquals(
            byteArrayOf(0x1F, 0x8B.toByte()),
            fakeFileSystem.readFileBytes(path).copyOfRange(0, 2),
        )
    }

    @Test
    fun eachSavedDataReadDetectsCompressionAndConsumesContentFromOneOpenSource() {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val savedDataScope = SavedDataScope.Dimension(DimensionDirectory.Overworld)
        val path = minecraftWorldPaths.savedData("maps/map_1", savedDataScope)
        val nbtDocument = sampleDocument(3)
        NbtFileStore(base).writeDocument(path, nbtDocument, Compression.NONE)
        val sourceOpeningCountingFileSystem = SourceOpeningCountingFileSystem(base)
        val savedDataStore = SavedDataStore(
            minecraftWorldPaths,
            savedDataScope,
            NbtFileStore(sourceOpeningCountingFileSystem),
        )

        assertEquals(nbtDocument, savedDataStore.readDocument("maps/map_1"))
        assertEquals(1, sourceOpeningCountingFileSystem.sourceOpenCount)

        assertEquals(
            nbtDocument,
            savedDataStore.read("maps/map_1") { source ->
                minecraftWorldNbtFormat().decodeDocumentFromOkio(source)
            },
        )
        assertEquals(2, sourceOpeningCountingFileSystem.sourceOpenCount)
        base.checkNoOpenFiles()
    }

    @Test
    fun jsonWritesDirectlyTruncateWithoutBackupOrTemporaryFiles() {
        val fakeFileSystem = FakeFileSystem()
        val utf8JsonFileStore = Utf8JsonFileStore(fakeFileSystem)
        val path = "/world/players/stats/player.json".toPath()

        utf8JsonFileStore.writeText(path, "{\"long\":true}")
        utf8JsonFileStore.writeText(path, "{}")

        assertEquals("{}", utf8JsonFileStore.readText(path))
        assertEquals(setOf(path), fakeFileSystem.allPaths.filter { fakeFileSystem.metadata(it).isRegularFile }.toSet())
    }

    @Test
    fun jsonSupportsStructuredAndRawStreamingWithoutPolicyLimits() {
        val fakeFileSystem = FakeFileSystem()
        val utf8JsonFileStore = Utf8JsonFileStore(fakeFileSystem)
        val path = "/world/advancements/player.json".toPath()
        val jsonObject = buildJsonObject {
            put("values", buildJsonArray { repeat(2_048) { add(JsonPrimitive(it)) } })
        }

        utf8JsonFileStore.writeJson(path, jsonObject)
        assertEquals(jsonObject, utf8JsonFileStore.readJson(path))

        val encoded = Json.encodeToString(jsonObject)
        utf8JsonFileStore.write(path) { sink ->
            encoded.chunked(257).forEach(sink::writeUtf8)
        }
        val copied = Buffer()
        val reads = mutableListOf<Long>()
        utf8JsonFileStore.read(path) { source ->
            while (true) {
                val read = source.read(copied, 257L)
                if (read < 0L) break
                reads += read
            }
        }

        assertTrue(reads.size > 1)
        assertTrue(reads.all { it in 1L..257L })
        assertEquals(encoded, copied.readUtf8())
    }

    @Test
    fun jsonFilesUseOneGenericSerializerPathForCallerCollectionsAndBuiltInModels() {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val utf8JsonFileStore = Utf8JsonFileStore(fakeFileSystem)
        val player = "00000000-0000-0000-0000-000000000000"
        val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
        val callerMap = mapOf("alpha" to 1, "beta" to 2)

        utf8JsonFileStore.writeJson(minecraftWorldPaths.statistics(player), mapSerializer, callerMap)
        assertEquals(callerMap, utf8JsonFileStore.readJson(minecraftWorldPaths.statistics(player), mapSerializer))

        val playerStatistics = PlayerStatistics(
            stats = mapOf("minecraft:mined" to mapOf("minecraft:stone" to 42)),
            dataVersion = 4_903,
        )
        utf8JsonFileStore.writeJson(minecraftWorldPaths.statistics(player), PlayerStatistics.serializer(), playerStatistics)
        assertEquals(
            playerStatistics,
            utf8JsonFileStore.readJson(minecraftWorldPaths.statistics(player), PlayerStatistics.serializer()),
        )

        val playerAdvancements = PlayerAdvancements(
            dataVersion = 4_903,
            advancements = mapOf(
                "minecraft:story/root" to PlayerAdvancements.Progress(
                    criteria = mapOf("crafting_table" to "2026-08-18 00:00:00 +0000"),
                    done = true,
                ),
            ),
        )
        utf8JsonFileStore.writeJson(minecraftWorldPaths.advancement(player), PlayerAdvancements.serializer(), playerAdvancements)
        assertEquals(
            playerAdvancements,
            utf8JsonFileStore.readJson(minecraftWorldPaths.advancement(player), PlayerAdvancements.serializer()),
        )
        fakeFileSystem.checkNoOpenFiles()
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
        val nbtFileStore = NbtFileStore(segmented)
        nbtFileStore.write(nbtPath, CallerLevelData.serializer(), nbtValue)
        assertTrue(segmented.writeCalls > 1)
        assertEquals(nbtValue, nbtFileStore.read(nbtPath, CallerLevelData.serializer()))
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
        val utf8JsonFileStore = Utf8JsonFileStore(segmented)
        val writesBeforeJson = segmented.writeCalls
        utf8JsonFileStore.writeJson(jsonPath, PlayerAdvancements.serializer(), jsonValue)
        assertTrue(segmented.writeCalls > writesBeforeJson + 1)
        val readsBeforeJson = segmented.readCalls
        assertEquals(jsonValue, utf8JsonFileStore.readJson(jsonPath, PlayerAdvancements.serializer()))
        assertTrue(segmented.readCalls > readsBeforeJson + 1)
        base.checkNoOpenFiles()
    }

    @Test
    fun failedFinalReplacementRestoresTheBackedUpLevelData() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val initialStore = LevelDataStore(minecraftWorldPaths, NbtFileStore(base))
        val first = sampleDocument(1)
        initialStore.writeDocument(first)
        val replacementFailingFileSystem = ReplacementFailingFileSystem(base, minecraftWorldPaths.levelData)
        val levelDataStore = LevelDataStore(minecraftWorldPaths, NbtFileStore(replacementFailingFileSystem))

        assertFailsWith<WorldIOException> {
            levelDataStore.writeDocument(sampleDocument(2))
        }

        assertEquals(first, NbtFileStore(base).readDocument(minecraftWorldPaths.levelData))
        assertEquals(10, replacementFailingFileSystem.replacementAttempts)
        assertFalse(base.exists(minecraftWorldPaths.previousLevelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun failedBackupMovePreservesPrimaryAndCleansTheTemporaryFile() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        LevelDataStore(minecraftWorldPaths, NbtFileStore(base)).writeDocument(sampleDocument(1))
        val backupMoveFailingFileSystem = BackupMoveFailingFileSystem(
            delegate = base,
            primary = minecraftWorldPaths.levelData,
            backup = minecraftWorldPaths.previousLevelData,
        )

        assertFailsWith<WorldIOException> {
            LevelDataStore(minecraftWorldPaths, NbtFileStore(backupMoveFailingFileSystem))
                .writeDocument(sampleDocument(2))
        }

        assertEquals(
            sampleDocument(1),
            NbtFileStore(base).readDocument(minecraftWorldPaths.levelData),
        )
        assertEquals(10, backupMoveFailingFileSystem.attempts)
        assertFalse(base.exists(minecraftWorldPaths.previousLevelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun failedRollbackLeavesTheOfficialBackupBoundaryVisible() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val first = sampleDocument(1)
        LevelDataStore(minecraftWorldPaths, NbtFileStore(base)).writeDocument(first)
        val replacementAndRollbackFailingFileSystem = ReplacementAndRollbackFailingFileSystem(
            delegate = base,
            primary = minecraftWorldPaths.levelData,
            backup = minecraftWorldPaths.previousLevelData,
        )

        val failure = assertFailsWith<WorldIOException> {
            LevelDataStore(minecraftWorldPaths, NbtFileStore(replacementAndRollbackFailingFileSystem))
                .writeDocument(sampleDocument(2))
        }

        assertFalse(base.exists(minecraftWorldPaths.levelData))
        assertEquals(
            first,
            NbtFileStore(base).readDocument(minecraftWorldPaths.previousLevelData),
        )
        assertEquals(10, replacementAndRollbackFailingFileSystem.replacementAttempts)
        assertEquals(10, replacementAndRollbackFailingFileSystem.rollbackAttempts)
        assertTrue(failure.suppressedExceptions.isNotEmpty())
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun streamingNbtFailureDoesNotReplaceThePrimaryFile() = runTest {
        val base = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val first = sampleDocument(1)
        LevelDataStore(minecraftWorldPaths, NbtFileStore(base)).writeDocument(first)
        val failure = WorldIOException("synthetic streaming failure")

        assertSame(failure, assertFails { LevelDataStore(minecraftWorldPaths, NbtFileStore(base)).write { throw failure } })

        assertEquals(first, NbtFileStore(base).readDocument(minecraftWorldPaths.levelData))
        assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
    }

    @Test
    fun jsonHasNoPolicyLimitAndInvalidSavedDataIdentifiersAreRejected() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        assertFailsWith<IllegalArgumentException> {
            minecraftWorldPaths.savedData("a/../b", SavedDataScope.Dimension(DimensionDirectory.Overworld))
        }
        val jsonPath = "/world/value.json".toPath()
        Utf8JsonFileStore(fakeFileSystem).writeText(jsonPath, "\u00E9")
        assertEquals("\u00E9", Utf8JsonFileStore(fakeFileSystem).readText(jsonPath))
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
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = fileHandle.read(
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
                    fileHandle.write(
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

            override fun protectedFlush() = fileHandle.flush()

            override fun protectedResize(size: Long) = fileHandle.resize(size)

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() = fileHandle.close()
        }
    }
}

private class SourceOpeningCountingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    var sourceOpenCount: Int = 0
        private set

    override fun source(file: Path): Source {
        sourceOpenCount++
        return super.source(file)
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
