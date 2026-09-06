package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class RegionChunkApiTest {
    @Test
    fun strongChunkDocumentAndStorageMetadataUseTheirOwningLayers() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val regionStorage = CoordinatedRegionStore(
            directory = "/world/dimensions/minecraft/overworld/region".toPath(),
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val firstPosition = ChunkPosition(-1, 32)
        val secondPosition = ChunkPosition(-2, 32)
        val streamedPosition = ChunkPosition(-3, 32)
        val chunk = emptyChunk(firstPosition)
        chunk.setBlockState(ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0), STONE)
        chunk.setBiome(ChunkBlockPosition(12, TEST_LAYOUT.minBlockY, 12), BiomeId("example:crystal_caves"))
        val expectedNbtDocument = TEST_ENCODER.encodeDocument(chunk)

        try {
            regionStorage.openRegion(firstPosition.regionPosition).use { regionHandle ->
                regionHandle.writeChunk(chunk, TEST_ENCODER, Compression.NONE)
                regionHandle.writeChunk(emptyChunk(secondPosition), TEST_ENCODER, Compression.ZLIB)
                assertFailsWith<IllegalArgumentException> {
                    regionHandle.writeChunk(emptyChunk(ChunkPosition(32, 32)), TEST_ENCODER, Compression.NONE)
                }
                regionHandle.writeChunkNbt(streamedPosition, Compression.GZIP) { sink ->
                    regionStorage.chunkNbtFormat.nbtFormat.encodeDocumentToOkio(
                        TEST_ENCODER.encodeDocument(emptyChunk(streamedPosition)),
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
                checkNotNull(
                    regionStorage.readChunk(
                        firstPosition.regionPosition,
                        firstPosition.localChunkPosition,
                        TEST_DECODER
                    )
                )
            val absoluteBlock = firstPosition.block(ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0))
            assertEquals(firstPosition, decoded.chunk.chunkPosition)
            assertEquals(STONE, decoded.chunk.getBlockState(ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0)))
            assertEquals(STONE, decoded.chunk.getBlockState(absoluteBlock))
            assertTrue(decoded.chunk.sections.containsKey(absoluteBlock.sectionPosition.y))
            assertEquals(
                BiomeId("example:crystal_caves"),
                decoded.chunk.getBiome(ChunkBlockPosition(12, TEST_LAYOUT.minBlockY, 12))
            )

            regionStorage.withChunkNbtSource(firstPosition) { sourceInfo, source ->
                assertEquals(regionChunkInfo, sourceInfo)
                val sourceChunk = TEST_DECODER.decodeFromOkio(source)
                assertEquals(STONE, sourceChunk.chunk.getBlockState(ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0)))
            }

            regionStorage.openRegion(firstPosition.regionPosition).use { regionHandle ->
                assertEquals(3, regionHandle.readChunkCount())
                assertEquals(
                    listOf(
                        streamedPosition.localChunkPosition,
                        secondPosition.localChunkPosition,
                        firstPosition.localChunkPosition
                    ),
                    regionHandle.readLocalChunkPositions(),
                )
                assertEquals(
                    listOf(streamedPosition, secondPosition, firstPosition),
                    regionHandle.readChunkPositions(),
                )
                assertTrue(regionHandle.hasChunk(absoluteBlock.chunkPosition))
                assertEquals(firstPosition, regionHandle.readChunkInfo(absoluteBlock.chunkPosition)?.chunkPosition)
                val positions = regionHandle.readChunkInfos().mapTo(linkedSetOf(), RegionChunkInfo::chunkPosition)
                assertEquals(setOf(firstPosition, secondPosition, streamedPosition), positions)
                val regionChunk = checkNotNull(regionHandle.readChunk(absoluteBlock.chunkPosition, TEST_DECODER))
                assertEquals(firstPosition, regionChunk.chunk.chunkPosition)
                assertEquals(STONE, regionChunk.chunk.getBlockState(ChunkBlockPosition(15, TEST_LAYOUT.minBlockY, 0)))

                val compressedSink = Buffer()
                assertEquals(regionChunkInfo, regionHandle.readCompressedChunkTo(firstPosition, compressedSink))
                assertContentEquals(
                    checkNotNull(regionHandle.readCompressedChunk(firstPosition)).toByteArray(),
                    compressedSink.readByteArray(),
                )

                val nbtSink = Buffer()
                assertEquals(regionChunkInfo, regionHandle.readChunkNbtTo(firstPosition, nbtSink))
                assertEquals(nbtDocument, NbtFormat.decodeDocumentFromOkio(nbtSink))

                var escapedRegionReadScope: RegionReadScope? = null
                regionHandle.withReadScope {
                    escapedRegionReadScope = this
                    assertEquals(firstPosition.regionPosition, regionPosition)
                    assertEquals(
                        firstPosition,
                        assertNotNull(readChunk(firstPosition, TEST_DECODER)).chunk.chunkPosition
                    )
                    assertEquals(
                        secondPosition,
                        assertNotNull(readChunk(secondPosition.localChunkPosition, TEST_DECODER)).chunk.chunkPosition,
                    )
                    assertEquals(
                        firstPosition,
                        assertNotNull(readChunk(absoluteBlock.chunkPosition, TEST_DECODER)).chunk.chunkPosition,
                    )
                    assertEquals(nbtDocument, readChunkNbtDocument(firstPosition))

                    val scopedNbtSink = Buffer()
                    assertEquals(regionChunkInfo, readChunkNbtTo(firstPosition.localChunkPosition, scopedNbtSink))
                    assertEquals(nbtDocument, NbtFormat.decodeDocumentFromOkio(scopedNbtSink))
                }
                assertFailsWith<IllegalStateException> {
                    checkNotNull(escapedRegionReadScope).readChunk(firstPosition, TEST_DECODER)
                }

                var escapedDecodedChunkRegionReadScope: DecodedChunkRegionReadScope? =
                    null
                regionHandle.withReadScope(TEST_DECODER) {
                    escapedDecodedChunkRegionReadScope = this
                    assertSame(TEST_DECODER, chunkNbtDecoder)
                    assertEquals(firstPosition, assertNotNull(readChunk(firstPosition)).chunk.chunkPosition)
                    assertEquals(
                        secondPosition,
                        assertNotNull(readChunk(secondPosition.localChunkPosition)).chunk.chunkPosition,
                    )
                }
                assertFailsWith<IllegalStateException> {
                    checkNotNull(escapedDecodedChunkRegionReadScope).readChunk(firstPosition)
                }
            }
        } finally {
            regionStorage.close()
        }

        val regionPath = "/world/dimensions/minecraft/overworld/region/r.-1.1.mca".toPath()
        val countingMutableRegionFileSystem = CountingMutableRegionFileSystem(fakeFileSystem, regionPath)
        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open("/world".toPath(), countingMutableRegionFileSystem)
        liveMinecraftWorldAccess.dimensions.overworld.openRegion(firstPosition.regionPosition).use { liveRegionHandle ->
            var escapedRegionReadScope: DecodedChunkRegionReadScope? = null
            liveRegionHandle.withReadScope(TEST_DECODER) {
                escapedRegionReadScope = this
                assertEquals(firstPosition, assertNotNull(readChunk(firstPosition)).chunk.chunkPosition)
                assertEquals(secondPosition, assertNotNull(readChunk(secondPosition)).chunk.chunkPosition)
                assertEquals(expectedNbtDocument, readChunkNbtDocument(firstPosition))
            }
            assertEquals(1, countingMutableRegionFileSystem.headerReads)
            assertFailsWith<IllegalStateException> {
                checkNotNull(escapedRegionReadScope).readChunk(firstPosition)
            }
        }
        val liveDimensionChunkReads = liveMinecraftWorldAccess.dimensions.overworld.chunks(TEST_DECODER)
        liveDimensionChunkReads.withRegionReadScope(firstPosition.regionPosition) {
            assertSame(TEST_DECODER, chunkNbtDecoder)
            val chunkNbtDecodeResult = assertNotNull(readChunk(firstPosition))
            assertSame(TEST_CONTEXT, chunkNbtDecodeResult.chunk.chunkContext)
            assertEquals(TEST_DATA_VERSION, chunkNbtDecodeResult.chunkNbtMetadata.dataVersion)
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun compressedInputAndRegionReplacementStreamThroughWriteTo() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val regionStorage = CoordinatedRegionStore(
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

    @Test
    fun semanticReadsPreserveTheNbtPositionWhenItDiffersFromTheRegionSlot() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val worldRoot = "/world".toPath()
        val directory = MinecraftWorldPaths(worldRoot).regionDirectory(RegionStorageDirectory.CHUNKS)
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val slotPosition = ChunkPosition(1, 2)
        val storedPosition = ChunkPosition(40, -12)
        val nbtDocument = TEST_ENCODER.encodeDocument(emptyChunk(storedPosition))

        try {
            regionStorage.writeChunkNbtDocument(slotPosition, nbtDocument, Compression.NONE)
            assertEquals(
                storedPosition,
                assertNotNull(regionStorage.readChunk(slotPosition, TEST_DECODER)).chunk.chunkPosition
            )
            regionStorage.openRegion(slotPosition.regionPosition).use { regionHandle ->
                assertEquals(
                    storedPosition,
                    assertNotNull(regionHandle.readChunk(slotPosition, TEST_DECODER)).chunk.chunkPosition
                )
                regionHandle.withReadScope(TEST_DECODER) {
                    assertEquals(storedPosition, assertNotNull(readChunk(slotPosition)).chunk.chunkPosition)
                }
            }
        } finally {
            regionStorage.close()
        }

        LiveMinecraftWorldAccess.open(worldRoot, fakeFileSystem).dimensions.overworld
            .openRegion(slotPosition.regionPosition)
            .use { liveRegionHandle ->
                assertEquals(
                    storedPosition,
                    assertNotNull(liveRegionHandle.readChunk(slotPosition, TEST_DECODER)).chunk.chunkPosition
                )
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
        val AIR = BlockState(BlockId("minecraft:air"))
        val STONE = BlockState(BlockId("minecraft:stone"))
        val TEST_CONTEXT = ChunkContext(
            DimensionId.Overworld, DimensionTypeLayout(-16, 32, 32, true, false), AIR, BiomeId("minecraft:plains"),
        )
        val TEST_DECODER = ChunkNbtDecoder(
            ChunkNbtDecoderContext(TEST_CONTEXT, NbtFormat, NbtPropertyReadMappings(), 0),
        )
        val TEST_ENCODER = ChunkNbtEncoder(
            ChunkNbtEncoderContext(
                TEST_CONTEXT.dimensionTypeLayout.chunkLayout,
                NbtFormat,
                NbtPropertyWriteMappings(),
                0,
                ChunkNbtMetadata(TEST_DATA_VERSION, 0)
            ),
        )

        fun emptyChunk(chunkPosition: ChunkPosition): Chunk = Chunk(chunkPosition, TEST_CONTEXT)
    }
}
