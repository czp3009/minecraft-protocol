package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class RegionChunkApiTest {
    @Test
    fun strongChunkDocumentAndStorageMetadataUseTheirOwningLayers() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val regionStorage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val firstPosition = ChunkPosition(-1, 32)
        val secondPosition = ChunkPosition(-2, 32)
        val streamedPosition = ChunkPosition(-3, 32)
        val chunk = emptyChunk(firstPosition)
        chunk.setBlock(15, TEST_LAYOUT.minBlockY, 0, STONE)
        chunk.setBiome(12, TEST_LAYOUT.minBlockY, 12, "example:crystal_caves")
        val expectedNbtDocument = TEST_CODEC.encodeDocument(chunk)

        try {
            regionStorage.openRegion(firstPosition.regionPosition).use { regionHandle ->
                regionHandle.writeChunk(chunk, TEST_CODEC, Compression.NONE)
                regionHandle.writeChunk(emptyChunk(secondPosition), TEST_CODEC, Compression.ZLIB)
                assertFailsWith<IllegalArgumentException> {
                    regionHandle.writeChunk(emptyChunk(ChunkPosition(32, 32)), TEST_CODEC, Compression.NONE)
                }
                regionHandle.writeChunkNbt(streamedPosition, Compression.GZIP) { sink ->
                    regionStorage.chunkNbtFormat.nbtFormat.encodeDocumentToSink(
                        TEST_CODEC.encodeDocument(emptyChunk(streamedPosition)),
                        sink,
                    )
                }
            }

            val regionChunkInfo = checkNotNull(regionStorage.readChunkInfo(firstPosition))
            assertEquals(firstPosition, regionChunkInfo.chunkPosition)
            assertEquals(firstPosition.regionPosition, regionChunkInfo.regionPosition)
            assertEquals(firstPosition.localChunkPosition, regionChunkInfo.localChunkPosition)
            assertEquals(Compression.NONE, regionChunkInfo.compression)
            assertEquals(AnvilChunkPlacement.INLINE, regionChunkInfo.anvilChunkPlacement)
            assertTrue(regionChunkInfo.compressedByteCount > 0)

            val positionedAnvilRegion = checkNotNull(regionStorage.readAnvilRegion(firstPosition.regionPosition))
            assertEquals(firstPosition.regionPosition, positionedAnvilRegion.regionPosition)
            assertTrue(positionedAnvilRegion.hasChunk(firstPosition))
            assertEquals(setOf(firstPosition, secondPosition, streamedPosition), positionedAnvilRegion.chunkPositions)

            val nbtDocument = checkNotNull(regionStorage.readChunkNbtDocument(firstPosition))
            assertEquals(firstPosition.x, (nbtDocument.root["xPos"] as NbtInt).value)
            assertEquals(firstPosition.z, (nbtDocument.root["zPos"] as NbtInt).value)
            val secondDocument = checkNotNull(regionStorage.readChunkNbtDocument(secondPosition))
            assertEquals(secondPosition.x, (secondDocument.root["xPos"] as NbtInt).value)
            assertEquals(
                streamedPosition.x,
                (checkNotNull(regionStorage.readChunkNbtDocument(streamedPosition)).root["xPos"] as NbtInt).value,
            )
            assertEquals(Compression.GZIP, regionStorage.readChunkInfo(streamedPosition)?.compression)

            val decoded =
                checkNotNull(regionStorage.readChunk(firstPosition.regionPosition, firstPosition.localChunkPosition, TEST_CODEC))
            val absoluteBlock = firstPosition.block(ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0))
            assertEquals(firstPosition, decoded.chunkPosition)
            assertEquals(STONE, decoded.block(15, TEST_LAYOUT.minBlockY, 0))
            assertEquals(STONE, decoded.block(absoluteBlock))
            assertTrue(decoded.hasSection(absoluteBlock))
            assertEquals("example:crystal_caves", decoded.biome(12, TEST_LAYOUT.minBlockY, 12))

            regionStorage.withChunkNbtSource(firstPosition) { sourceInfo, source ->
                assertEquals(regionChunkInfo, sourceInfo)
                val sourceChunk = TEST_CODEC.decodeFromSource(source, firstPosition)
                assertEquals(STONE, sourceChunk.block(15, TEST_LAYOUT.minBlockY, 0))
            }

            regionStorage.openRegion(firstPosition.regionPosition).use { regionHandle ->
                assertEquals(3, regionHandle.readChunkCount())
                assertEquals(
                    listOf(streamedPosition.localChunkPosition, secondPosition.localChunkPosition, firstPosition.localChunkPosition),
                    regionHandle.readLocalChunkPositions(),
                )
                assertEquals(
                    listOf(streamedPosition, secondPosition, firstPosition),
                    regionHandle.readChunkPositions(),
                )
                assertTrue(regionHandle.hasChunk(absoluteBlock))
                assertEquals(firstPosition, regionHandle.readChunkInfo(absoluteBlock.chunkPosition)?.chunkPosition)
                val positions = regionHandle.readChunkInfos().mapTo(linkedSetOf(), RegionChunkInfo::chunkPosition)
                assertEquals(setOf(firstPosition, secondPosition, streamedPosition), positions)
                val regionChunk = checkNotNull(regionHandle.readChunk(absoluteBlock, TEST_CODEC))
                assertEquals(firstPosition, regionChunk.chunkPosition)
                assertEquals(STONE, regionChunk.block(15, TEST_LAYOUT.minBlockY, 0))

                val compressedSink = Buffer()
                assertEquals(regionChunkInfo, regionHandle.readCompressedChunkTo(firstPosition, compressedSink))
                assertContentEquals(
                    checkNotNull(regionHandle.readCompressedChunk(firstPosition)).toByteArray(),
                    compressedSink.readByteArray(),
                )

                val nbtSink = Buffer()
                assertEquals(regionChunkInfo, regionHandle.readChunkNbtTo(firstPosition, nbtSink))
                assertEquals(nbtDocument, TEST_CODEC.nbtFormat.decodeDocumentFromSource(nbtSink))

                var escapedRegionReadScope: RegionReadScope? = null
                regionHandle.withReadScope {
                    escapedRegionReadScope = this
                    assertEquals(firstPosition.regionPosition, regionPosition)
                    assertEquals(firstPosition, assertNotNull(readChunk(firstPosition, TEST_CODEC)).chunkPosition)
                    assertEquals(
                        secondPosition,
                        assertNotNull(readChunk(secondPosition.localChunkPosition, TEST_CODEC)).chunkPosition,
                    )
                    assertEquals(firstPosition, assertNotNull(readChunk(absoluteBlock, TEST_CODEC)).chunkPosition)
                    assertEquals(nbtDocument, readChunkNbtDocument(firstPosition))

                    val scopedNbtSink = Buffer()
                    assertEquals(regionChunkInfo, readChunkNbtTo(firstPosition.localChunkPosition, scopedNbtSink))
                    assertEquals(nbtDocument, TEST_CODEC.nbtFormat.decodeDocumentFromSource(scopedNbtSink))
                }
                assertFailsWith<IllegalStateException> {
                    checkNotNull(escapedRegionReadScope).readChunk(firstPosition, TEST_CODEC)
                }
            }
        } finally {
            regionStorage.close()
        }

        val regionPath = "/world/region/r.-1.1.mca".toPath()
        val countingMutableRegionFileSystem = CountingMutableRegionFileSystem(fakeFileSystem, regionPath)
        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open("/world".toPath(), countingMutableRegionFileSystem)
        liveMinecraftWorldAccess.openRegion(
            firstPosition.regionPosition,
            DimensionDirectory.LegacyOverworld,
        ).use { liveRegionHandle ->
            var escapedRegionReadScope: RegionReadScope? = null
            liveRegionHandle.withReadScope {
                escapedRegionReadScope = this
                assertEquals(firstPosition, assertNotNull(readChunk(firstPosition, TEST_CODEC)).chunkPosition)
                assertEquals(secondPosition, assertNotNull(readChunk(secondPosition, TEST_CODEC)).chunkPosition)
                assertEquals(expectedNbtDocument, readChunkNbtDocument(firstPosition))
            }
            assertEquals(1, countingMutableRegionFileSystem.headerReads)
            assertFailsWith<IllegalStateException> {
                checkNotNull(escapedRegionReadScope).readChunk(firstPosition, TEST_CODEC)
            }
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun compressedInputAndRegionReplacementStreamThroughWriteTo() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val regionStorage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val regionPosition = RegionPosition(3, -2)
        val first = TrackingCompressedInput(byteArrayOf(1, 2, 3))
        val second = TrackingCompressedInput(byteArrayOf(4, 5))

        try {
            regionStorage.replaceRegion(
                regionPosition,
                listOf(
                    RegionChunkInput(LocalChunkPosition(0, 0), first),
                    RegionChunkInput(LocalChunkPosition(31, 31), second),
                ),
            )

            assertEquals(1, first.writeCount)
            assertEquals(1, second.writeCount)
            val storedFirst = regionStorage.readCompressedChunk(regionPosition, LocalChunkPosition(0, 0))
            val storedSecond = regionStorage.readCompressedChunk(regionPosition, LocalChunkPosition(31, 31))
            assertContentEquals(first.bytes, storedFirst?.toByteArray())
            assertContentEquals(second.bytes, storedSecond?.toByteArray())
        } finally {
            regionStorage.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    private class TrackingCompressedInput(
        val bytes: ByteArray,
    ) : CompressedChunkInput {
        override val compression: Compression = Compression.NONE
        override val compressedByteCount: Long = bytes.size.toLong()
        var writeCount = 0
            private set

        override fun writeTo(sink: Sink) {
            writeCount++
            sink.write(bytes)
        }
    }

    private companion object {
        const val TEST_DATA_VERSION = 12_345
        val TEST_LAYOUT = ChunkLayout(minSectionY = -1, sectionCount = 2)
        val AIR = BlockStateDescriptor("minecraft:air")
        val STONE = BlockStateDescriptor("minecraft:stone")
        val TEST_CODEC = ChunkNbtCodec(
            ChunkNbtContext(
                chunkLayout = TEST_LAYOUT,
                chunkDataRegistries = ChunkDataRegistries(
                    blockStates = DescriptorBlockStateRegistry(AIR),
                    biomes = NamedBiomeRegistry(),
                ),
                expectedDataVersion = TEST_DATA_VERSION,
            ),
        )

        fun emptyChunk(chunkPosition: ChunkPosition): Chunk<BlockStateDescriptor, String> = Chunk(
            chunkPosition = chunkPosition,
            chunkMetadata = ChunkMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
            chunkLayout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )
    }
}
