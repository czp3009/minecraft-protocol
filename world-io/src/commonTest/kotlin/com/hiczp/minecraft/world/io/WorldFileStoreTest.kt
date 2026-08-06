package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtLimitException
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemPathSeparator
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

class WorldFileStoreTest {
    @Test
    fun constructsCanonicalWorldPaths() {
        val paths = MinecraftWorldPaths(Path("world"))

        assertEquals("world/level.dat".platformPath(), paths.levelData.toString())
        assertEquals(
            "world/level.dat_old".platformPath(),
            paths.previousLevelData.toString(),
        )
        assertEquals(
            "world/session.lock".platformPath(),
            paths.sessionLock.toString(),
        )
        assertEquals(
            "world/dimensions/minecraft/overworld".platformPath(),
            paths.dimension(DimensionDirectory.Overworld).toString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_nether".platformPath(),
            paths.dimension(DimensionDirectory.Nether).toString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_end".platformPath(),
            paths.dimension(DimensionDirectory.End).toString(),
        )
        assertEquals(
            "world",
            paths.dimension(DimensionDirectory.LegacyOverworld).toString(),
        )
        assertEquals(
            "world/DIM-1".platformPath(),
            paths.dimension(DimensionDirectory.LegacyNether).toString(),
        )
        assertEquals(
            "world/DIM1".platformPath(),
            paths.dimension(DimensionDirectory.LegacyEnd).toString(),
        )
        assertEquals(
            "world/dimensions/minecraft/the_nether/entities/r.-1.2.mca"
                .platformPath(),
            paths.regionFile(
                RegionPosition(-1, 2),
                RegionStorageDirectory.ENTITIES,
                DimensionDirectory.Nether,
            ).toString(),
        )
        assertEquals(
            "world/dimensions/example/moon/orbit/poi/r.0.0.mca".platformPath(),
            paths.regionFile(
                RegionPosition(0, 0),
                RegionStorageDirectory.POINTS_OF_INTEREST,
                DimensionDirectory.Custom("example", "moon/orbit"),
            ).toString(),
        )
        assertEquals(
            "world/dimensions/minecraft/overworld/region/c.-1.33.mcc"
                .platformPath(),
            paths.externalChunk(ChunkPosition(-1, 33)).toString(),
        )
        assertEquals(
            "world/players/data/player.dat".platformPath(),
            paths.playerData("player").toString(),
        )
        assertEquals(
            "world/players/advancements/player.json".platformPath(),
            paths.advancement("player").toString(),
        )
        assertEquals(
            "world/players/stats/player.json".platformPath(),
            paths.statistics("player").toString(),
        )
        assertEquals(
            "world/playerdata/player.dat".platformPath(),
            paths.legacyPlayerData("player").toString(),
        )
        assertEquals(
            "world/advancements/player.json".platformPath(),
            paths.legacyAdvancement("player").toString(),
        )
        assertEquals(
            "world/stats/player.json".platformPath(),
            paths.legacyStatistics("player").toString(),
        )
    }

    @Test
    fun standaloneNbtFilesRoundTripAllSupportedCompression() = withTemporaryWorld {
        runTest {
            val store = NbtFileStore()
            val document = sampleDocument()
            for (compression in NbtFileCompression.entries) {
                val path = Path(root, "${compression.name.lowercase()}.dat")
                store.write(path, document, compression)

                assertEquals(document, store.read(path, compression))
                assertNoAtomicTemporaryFiles(root, path.name)
            }
        }
    }

    @Test
    fun regionStoreResolvesAndCleansExternalChunks() = withTemporaryWorld {
        runTest {
            val paths = MinecraftWorldPaths(root)
            val store = WorldRegionStore(paths)
            val position = ChunkPosition(-1, 33)
            val document = sampleDocument()

            store.writeChunkNbt(
                position = position,
                document = document,
                timestamp = 123,
                compression = RegionCompression.LZ4,
                external = true,
            )

            assertTrue(SystemFileSystem.exists(paths.regionFile(position.region)))
            assertTrue(SystemFileSystem.exists(paths.externalChunk(position)))
            assertEquals(document, store.readChunkNbt(position))
            assertEquals(123, store.readChunk(position)?.timestamp)

            store.writeChunkNbt(
                position = position,
                document = document,
                timestamp = 456,
                compression = RegionCompression.ZLIB,
                external = false,
            )

            assertFalse(SystemFileSystem.exists(paths.externalChunk(position)))
            assertEquals(document, store.readChunkNbt(position))
            assertEquals(456, store.readChunk(position)?.timestamp)

            store.writeChunk(position, null)

            assertNull(store.readChunk(position))
        }
    }

    @Test
    fun regionDirectoriesSupportChunkEntityAndPoiData() = withTemporaryWorld {
        val paths = MinecraftWorldPaths(root)
        val store = WorldRegionStore(paths)
        val regionPosition = RegionPosition(2, -3)
        val local = LocalChunkPosition(4, 5)

        RegionStorageDirectory.entries.forEachIndexed { index, storage ->
            val region = RegionFile(
                mapOf(
                    local to RegionChunk(
                        RegionCompression.NONE,
                        RegionChunkPayload.Inline(byteArrayOf(index.toByte())),
                        timestamp = index,
                    ),
                ),
            )
            store.writeRegion(
                position = regionPosition,
                region = region,
                storage = storage,
                dimension = DimensionDirectory.End,
            )

            assertEquals(
                region,
                store.readRegion(
                    regionPosition,
                    storage,
                    DimensionDirectory.End,
                ),
            )
        }
    }

    @Test
    fun fileLimitsAreEnforcedBeforeDecode() = withTemporaryWorld {
        runTest {
            val decompressedLimited = NbtFileStore(
                configuration = NbtFileStoreConfiguration(
                    maximumCompressedBytes = Int.MAX_VALUE,
                    maximumDecompressedBytes = 1,
                ),
            )
            assertFailsWith<WorldIOException> {
                decompressedLimited.write(
                    Path(root, "too-large.dat"),
                    sampleDocument(),
                    NbtFileCompression.NONE,
                )
            }

            val compressedPath = Path(root, "compressed-too-large.dat")
            val compressedLimited = NbtFileStore(
                configuration = NbtFileStoreConfiguration(
                    maximumCompressedBytes = 0,
                    maximumDecompressedBytes = Int.MAX_VALUE,
                ),
            )
            assertFailsWith<WorldIOException> {
                compressedLimited.write(
                    compressedPath,
                    sampleDocument(),
                    NbtFileCompression.NONE,
                )
            }
            assertFalse(SystemFileSystem.exists(compressedPath))

            val readablePath = Path(root, "read-limit.dat")
            NbtFileStore().write(
                readablePath,
                sampleDocument(),
                NbtFileCompression.NONE,
            )
            val encodedSize = checkNotNull(
                SystemFileSystem.metadataOrNull(readablePath),
            ).size.toInt()
            assertEquals(
                sampleDocument(),
                NbtFileStore(
                    configuration = NbtFileStoreConfiguration(
                        maximumCompressedBytes = encodedSize,
                        maximumDecompressedBytes = encodedSize,
                    ),
                ).read(readablePath, NbtFileCompression.NONE),
            )
            assertFailsWith<WorldIOException> {
                NbtFileStore(
                    configuration = NbtFileStoreConfiguration(
                        maximumCompressedBytes = encodedSize - 1,
                        maximumDecompressedBytes = encodedSize,
                    ),
                ).read(readablePath, NbtFileCompression.NONE)
            }
            assertFailsWith<RegionFormatException> {
                NbtFileStore(
                    configuration = NbtFileStoreConfiguration(
                        maximumCompressedBytes = encodedSize,
                        maximumDecompressedBytes = encodedSize - 1,
                    ),
                ).read(readablePath, NbtFileCompression.NONE)
            }
        }
    }

    @Test
    fun validatesStoreLimitsAndCustomDimensionSegments() {
        assertFailsWith<IllegalArgumentException> {
            NbtFileStoreConfiguration(maximumCompressedBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NbtFileStoreConfiguration(maximumDecompressedBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            WorldRegionStoreConfiguration(maximumRegionBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            WorldRegionStoreConfiguration(maximumExternalChunkBytes = -1)
        }

        for (namespace in listOf(
            "",
            ".",
            "..",
            "bad/name",
            "bad\\name",
            "Bad",
            "bad name",
            "bad:name",
        )) {
            assertFailsWith<IllegalArgumentException> {
                DimensionDirectory.Custom(namespace, "path")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("example", "")
        }
        for (path in listOf(
            "/path",
            "path/",
            "path//segment",
        )) {
            assertFailsWith<IllegalArgumentException> {
                DimensionDirectory.Custom("example", path)
            }
        }
        for (segment in listOf(
            ".",
            "..",
            "bad/name",
            "bad\\name",
            "Bad",
            "bad name",
            "bad:name",
            " ",
        )) {
            assertFailsWith<IllegalArgumentException> {
                DimensionDirectory.Custom("example", listOf(segment))
            }
        }

        val mutableSegments = mutableListOf("safe", "dimension")
        val custom = DimensionDirectory.Custom("example", mutableSegments)
        mutableSegments[0] = ".."
        assertEquals(listOf("safe", "dimension"), custom.pathSegments)
        assertEquals(
            "world/dimensions/example/safe/dimension".platformPath(),
            MinecraftWorldPaths(Path("world")).dimension(custom).toString(),
        )
        assertEquals(custom, custom.copy())

        val paths = MinecraftWorldPaths(Path("world"))
        val playerPathFactories = listOf<(String) -> Path>(
            paths::playerData,
            paths::advancement,
            paths::statistics,
            paths::legacyPlayerData,
            paths::legacyAdvancement,
            paths::legacyStatistics,
        )
        for (invalidPlayerKey in listOf(
            "",
            ".",
            "..",
            "../escape",
            "bad/name",
            "bad\\name",
            "bad:name",
            "bad name",
        )) {
            playerPathFactories.forEach { pathFactory ->
                assertFailsWith<IllegalArgumentException> {
                    pathFactory(invalidPlayerKey)
                }
            }
        }
    }

    @Test
    fun missingAndNonRegularFilesAreReportedWithoutCreatingArtifacts() =
        withTemporaryWorld {
            runTest {
                val missing = Path(root, "missing.dat")
                assertFailsWith<WorldIOException> {
                    NbtFileStore().read(missing)
                }

                val directory = Path(root, "directory.dat")
                SystemFileSystem.createDirectories(directory)
                assertFailsWith<WorldIOException> {
                    NbtFileStore().read(directory)
                }

                val regions = WorldRegionStore(MinecraftWorldPaths(root))
                val position = ChunkPosition(33, -65)
                assertNull(regions.readRegion(position.region))
                assertNull(regions.readChunk(position))
                assertNull(regions.readChunkNbt(position))
                assertFalse(
                    SystemFileSystem.exists(
                        regions.paths.regionFile(position.region),
                    ),
                )
            }
        }

    @Test
    fun distinguishesMissingZeroByteHeaderOnlyAndTruncatedRegions() =
        withTemporaryWorld {
            val paths = MinecraftWorldPaths(root)
            val store = WorldRegionStore(paths)
            val position = RegionPosition(4, -7)
            val path = paths.regionFile(position)

            assertNull(store.readRegion(position))

            SystemFileSystem.writeByteArrayAtomically(path, byteArrayOf())
            assertEquals(RegionFile(), store.readRegion(position))

            SystemFileSystem.writeByteArrayAtomically(
                path,
                ByteArray(REGION_HEADER_BYTES),
            )
            assertEquals(RegionFile(), store.readRegion(position))

            SystemFileSystem.writeByteArrayAtomically(path, byteArrayOf(1))
            assertFailsWith<RegionFormatException> {
                store.readRegion(position)
            }
        }

    @Test
    fun chunkUpdatesPreserveUnrelatedEntriesAndRemoveOnlyTheirSidecars() =
        withTemporaryWorld {
            val paths = MinecraftWorldPaths(root)
            val store = WorldRegionStore(paths)
            val first = ChunkPosition(0, 0)
            val second = ChunkPosition(1, 0)
            val firstChunk = RegionChunk(
                RegionCompression.NONE,
                RegionChunkPayload.External(byteArrayOf(1)),
                timestamp = 1,
            )
            val secondChunk = RegionChunk(
                RegionCompression.NONE,
                RegionChunkPayload.External(byteArrayOf(2)),
                timestamp = 2,
            )

            store.writeChunk(first, firstChunk)
            store.writeChunk(second, secondChunk)
            assertEquals(firstChunk, store.readChunk(first))
            assertEquals(secondChunk, store.readChunk(second))

            store.writeChunk(first, null)

            assertNull(store.readChunk(first))
            assertEquals(secondChunk, store.readChunk(second))
            assertFalse(SystemFileSystem.exists(paths.externalChunk(first)))
            assertTrue(SystemFileSystem.exists(paths.externalChunk(second)))
        }

    @Test
    fun atomicWritesRejectParentlessDestinationsWithoutArtifacts() = runTest {
        val path = Path("parentless-world-store-test.dat")
        assertFailsWith<WorldIOException> {
            NbtFileStore().write(
                path,
                sampleDocument(),
                NbtFileCompression.NONE,
            )
        }
        assertFalse(SystemFileSystem.exists(path))
    }

    @Test
    fun atomicTemporaryFileNamesUseOneCompactFormat() {
        assertEquals(
            ".tmp-0000000000000",
            atomicTemporaryFileName(0uL),
        )
        assertEquals(
            ".tmp-3w5e11264sgsf",
            atomicTemporaryFileName(ULong.MAX_VALUE),
        )
    }

    @Test
    fun failedAtomicWriteRemovesItsTemporaryFile() = withTemporaryWorld {
        runTest {
            val destination = Path(root, "blocked.dat")
            SystemFileSystem.createDirectories(destination)

            assertFailsWith<IOException> {
                NbtFileStore().write(
                    destination,
                    sampleDocument(),
                    NbtFileCompression.NONE,
                )
            }

            assertEquals(
                expected = true,
                actual = SystemFileSystem.metadataOrNull(destination)?.isDirectory,
            )
            assertNoAtomicTemporaryFiles(root, destination.name)
        }
    }

    @Test
    fun serializationFailureIsPropagatedAndCleansTemporaryFile() =
        withTemporaryWorld {
            runTest {
                val destination = Path(root, "serialization-failure.dat")
                val store = NbtFileStore(
                    nbt = NbtFormat(
                        NbtFormatConfiguration(maximumStringBytes = 1),
                    ),
                )

                val failure = assertFailsWith<NbtLimitException> {
                    store.write(
                        destination,
                        sampleDocument(),
                        NbtFileCompression.NONE,
                    )
                }
                assertNull(failure.cause)

                assertFalse(SystemFileSystem.exists(destination))
                assertNoAtomicTemporaryFiles(root, destination.name)
            }
        }

    @Test
    fun concurrentAtomicWritesUseIndependentTemporaryFiles() =
        withTemporaryWorld {
            runTest {
                val destination = Path(root, "concurrent.dat")
                val documents = List(64) { index ->
                    NbtDocument(
                        root = NbtCompound(
                            mapOf(
                                "writer" to NbtInt(index),
                                "payload" to NbtString("x".repeat(8_192)),
                            ),
                        ),
                    )
                }
                val store = NbtFileStore()

                coroutineScope {
                    val ready = Channel<Unit>(documents.size)
                    val start = CompletableDeferred<Unit>()
                    val writes = documents.map { document ->
                        async(Dispatchers.Default) {
                            ready.send(Unit)
                            start.await()
                            store.write(
                                destination,
                                document,
                                NbtFileCompression.NONE,
                            )
                        }
                    }
                    repeat(documents.size) {
                        ready.receive()
                    }
                    start.complete(Unit)
                    writes.awaitAll()
                }

                assertTrue(store.read(destination, NbtFileCompression.NONE) in documents)
                assertNoAtomicTemporaryFiles(root, destination.name)
            }
        }

    @Test
    fun missingAndOversizedExternalChunksFailAtTheFileBoundary() =
        withTemporaryWorld {
            val paths = MinecraftWorldPaths(root)
            val position = ChunkPosition(0, 0)
            val region = RegionFile(
                mapOf(
                    position.local to RegionChunk(
                        RegionCompression.NONE,
                        RegionChunkPayload.External(byteArrayOf(1, 2, 3)),
                    ),
                ),
            )
            val store = WorldRegionStore(paths)
            store.writeRegion(position.region, region)
            SystemFileSystem.delete(paths.externalChunk(position))

            assertFailsWith<WorldIOException> {
                store.readRegion(position.region)
            }

            store.writeRegion(position.region, region)
            val strict = WorldRegionStore(
                paths = paths,
                configuration = WorldRegionStoreConfiguration(
                    maximumRegionBytes = Int.MAX_VALUE,
                    maximumExternalChunkBytes = 2,
                ),
            )
            assertFailsWith<WorldIOException> {
                strict.readRegion(position.region)
            }
        }

    @Test
    fun externalSizeValidationHappensBeforeAnyRegionFilesAreWritten() =
        withTemporaryWorld {
            val paths = MinecraftWorldPaths(root)
            val position = RegionPosition(0, 0)
            val small = LocalChunkPosition(0, 0)
            val oversized = LocalChunkPosition(1, 0)
            val store = WorldRegionStore(
                paths = paths,
                configuration = WorldRegionStoreConfiguration(
                    maximumRegionBytes = Int.MAX_VALUE,
                    maximumExternalChunkBytes = 1,
                ),
            )

            assertFailsWith<WorldIOException> {
                store.writeRegion(
                    position,
                    RegionFile(
                        linkedMapOf(
                            small to RegionChunk(
                                RegionCompression.NONE,
                                RegionChunkPayload.External(byteArrayOf(1)),
                            ),
                            oversized to RegionChunk(
                                RegionCompression.NONE,
                                RegionChunkPayload.External(byteArrayOf(1, 2)),
                            ),
                        ),
                    ),
                )
            }

            assertFalse(SystemFileSystem.exists(paths.regionFile(position)))
            assertFalse(
                SystemFileSystem.exists(
                    paths.externalChunk(position.chunk(small)),
                ),
            )
            assertFalse(
                SystemFileSystem.exists(
                    paths.externalChunk(position.chunk(oversized)),
                ),
            )

            val regionLimitedPaths = MinecraftWorldPaths(
                Path(root, "region-limited"),
            )
            assertFailsWith<WorldIOException> {
                WorldRegionStore(
                    paths = regionLimitedPaths,
                    configuration = WorldRegionStoreConfiguration(
                        maximumRegionBytes = 0,
                    ),
                ).writeRegion(position, RegionFile())
            }
            assertFalse(
                SystemFileSystem.exists(
                    regionLimitedPaths.regionFile(position),
                ),
            )
        }

    @Test
    fun compressedAndRegionFileLimitsAreAppliedBeforeParsing() =
        withTemporaryWorld {
            runTest {
                val corrupt = Path(root, "corrupt.dat")
                SystemFileSystem.writeByteArrayAtomically(
                    corrupt,
                    byteArrayOf(1, 2, 3),
                )
                assertFailsWith<RegionFormatException> {
                    NbtFileStore().read(corrupt, NbtFileCompression.GZIP)
                }

                val paths = MinecraftWorldPaths(root)
                val position = RegionPosition(0, 0)
                WorldRegionStore(paths).writeRegion(position, RegionFile())
                assertFailsWith<WorldIOException> {
                    WorldRegionStore(
                        paths,
                        configuration = WorldRegionStoreConfiguration(
                            maximumRegionBytes = 0,
                        ),
                    ).readRegion(position)
                }
            }
        }

    private fun sampleDocument(): NbtDocument =
        NbtDocument(
            root = NbtCompound(
                linkedMapOf(
                    "DataVersion" to NbtInt(4_000),
                    "Name" to NbtString("test\u0000world"),
                ),
            ),
        )

    private fun withTemporaryWorld(block: TemporaryWorld.() -> Unit) {
        val directory = Path(
            SystemTemporaryDirectory,
            "minecraft-protocol-world-${Random.nextLong().toULong().toString(16)}",
        )
        SystemFileSystem.createDirectories(directory, mustCreate = true)
        try {
            TemporaryWorld(directory).block()
        } finally {
            deleteRecursively(directory)
            check(!SystemFileSystem.exists(directory))
        }
    }

    private fun String.platformPath(): String =
        replace('/', SystemPathSeparator)

    private class TemporaryWorld(val root: Path)
}

private fun assertNoAtomicTemporaryFiles(
    directory: Path,
    destinationName: String,
) {
    val example = atomicTemporaryFileName(0uL)
    assertFalse(
        SystemFileSystem.list(directory).any {
            it.name.startsWith(".tmp-") &&
                    it.name.length == example.length
        },
        "Atomic-write temporary file was left behind for $destinationName",
    )
}

private fun deleteRecursively(path: Path) {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemFileSystem.list(path).forEach(::deleteRecursively)
    }
    SystemFileSystem.delete(path)
}
