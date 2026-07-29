package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.protocol.model.type.NbtCompound
import com.hiczp.minecraft.protocol.model.type.NbtInt
import com.hiczp.minecraft.protocol.model.type.NbtString
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import kotlin.test.*
import java.nio.file.Path as NioPath

class WorldFileStoreTest {
    @Test
    fun constructsCanonicalWorldPaths() {
        val paths = MinecraftWorldPaths(Path("world"))

        assertEquals("world/level.dat".platformPath(), paths.levelData.toString())
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
                assertFalse(
                    SystemFileSystem.exists(
                        Path(root, ".${path.name}.minecraft-protocol.tmp"),
                    ),
                )
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
            val strict = NbtFileStore(
                configuration = NbtFileStoreConfiguration(
                    maximumCompressedBytes = 1,
                    maximumDecompressedBytes = 1,
                ),
            )
            assertFailsWith<WorldIOException> {
                strict.write(
                    Path(root, "too-large.dat"),
                    sampleDocument(),
                    NbtFileCompression.NONE,
                )
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

        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("", "path")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("bad/name", "path")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("bad\\name", "path")
        }
        assertFailsWith<IllegalArgumentException> {
            DimensionDirectory.Custom("example", "")
        }
        for (segment in listOf(".", "..", "bad/name", "bad\\name", " ")) {
            assertFailsWith<IllegalArgumentException> {
                DimensionDirectory.Custom("example", listOf(segment))
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
                Files.createDirectories(NioPath.of(directory.toString()))
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
    fun failedAtomicWriteRemovesItsTemporaryFile() = withTemporaryWorld {
        runTest {
            val destination = Path(root, "blocked.dat")
            Files.createDirectories(NioPath.of(destination.toString()))
            val temporary = Path(
                root,
                ".${destination.name}.minecraft-protocol.tmp",
            )

            assertFailsWith<WorldIOException> {
                NbtFileStore().write(
                    destination,
                    sampleDocument(),
                    NbtFileCompression.NONE,
                )
            }

            assertTrue(Files.isDirectory(NioPath.of(destination.toString())))
            assertFalse(SystemFileSystem.exists(temporary))
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
            rootName = "",
            root = NbtCompound(
                linkedMapOf(
                    "DataVersion" to NbtInt(4_000),
                    "Name" to NbtString("test\u0000world"),
                ),
            ),
        )

    private fun withTemporaryWorld(block: TemporaryWorld.() -> Unit) {
        val directory = Files.createTempDirectory("minecraft-protocol-world-")
        try {
            TemporaryWorld(Path(directory.toString())).block()
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    private fun String.platformPath(): String =
        replace('/', java.io.File.separatorChar)

    private class TemporaryWorld(val root: Path)
}
