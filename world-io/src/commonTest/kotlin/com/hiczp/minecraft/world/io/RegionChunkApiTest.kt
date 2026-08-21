package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionChunkApiTest {
    @Test
    fun strongChunkDocumentAndStorageMetadataUseTheirOwningLayers() = runTest {
        val fileSystem = FakeFileSystem()
        val storage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        val firstPosition = ChunkPosition(-1, 32)
        val secondPosition = ChunkPosition(-2, 32)
        val streamedPosition = ChunkPosition(-3, 32)
        val chunk = emptyChunk()
        chunk.setBlock(15, TEST_LAYOUT.minBlockY, 0, STONE)
        chunk.setBiome(12, TEST_LAYOUT.minBlockY, 12, "example:crystal_caves")

        try {
            storage.openRegion(firstPosition.region).use { regionHandle ->
                regionHandle.writeChunk(firstPosition, chunk, TEST_CODEC, Compression.NONE)
                regionHandle.writeChunk(secondPosition, chunk, TEST_CODEC, Compression.ZLIB)
                regionHandle.writeChunkNbt(streamedPosition, Compression.GZIP) { sink ->
                    storage.chunkNbtFormat.nbt.encodeDocumentToSink(
                        TEST_CODEC.encodeDocument(chunk, streamedPosition),
                        sink,
                    )
                }
            }

            val info = checkNotNull(storage.readChunkInfo(firstPosition))
            assertEquals(firstPosition, info.position)
            assertEquals(firstPosition.region, info.region)
            assertEquals(firstPosition.local, info.localPosition)
            assertEquals(Compression.NONE, info.compression)
            assertEquals(AnvilChunkPlacement.INLINE, info.placement)
            assertTrue(info.compressedByteCount > 0)

            val document = checkNotNull(storage.readChunkNbtDocument(firstPosition))
            assertEquals(firstPosition.x, (document.root["xPos"] as NbtInt).value)
            assertEquals(firstPosition.z, (document.root["zPos"] as NbtInt).value)
            val secondDocument = checkNotNull(storage.readChunkNbtDocument(secondPosition))
            assertEquals(secondPosition.x, (secondDocument.root["xPos"] as NbtInt).value)
            assertEquals(
                streamedPosition.x,
                (checkNotNull(storage.readChunkNbtDocument(streamedPosition)).root["xPos"] as NbtInt).value,
            )
            assertEquals(Compression.GZIP, storage.readChunkInfo(streamedPosition)?.compression)

            val decoded = checkNotNull(storage.readChunk(firstPosition.region, firstPosition.local, TEST_CODEC))
            assertEquals(STONE, decoded.block(15, TEST_LAYOUT.minBlockY, 0))
            assertEquals("example:crystal_caves", decoded.biome(12, TEST_LAYOUT.minBlockY, 12))

            storage.withChunkNbtSource(firstPosition) { sourceInfo, source ->
                assertEquals(info, sourceInfo)
                val sourceChunk = TEST_CODEC.decodeFromSource(source, firstPosition)
                assertEquals(STONE, sourceChunk.block(15, TEST_LAYOUT.minBlockY, 0))
            }

            storage.openRegion(firstPosition.region).use { regionHandle ->
                assertEquals(3, regionHandle.readChunkCount())
                assertEquals(
                    listOf(streamedPosition.local, secondPosition.local, firstPosition.local),
                    regionHandle.readLocalChunkPositions(),
                )
                val positions = regionHandle.readChunkInfos().mapTo(linkedSetOf(), RegionChunkInfo::position)
                assertEquals(setOf(firstPosition, secondPosition, streamedPosition), positions)
                val regionChunk = checkNotNull(regionHandle.readChunk(firstPosition, TEST_CODEC))
                assertEquals(STONE, regionChunk.block(15, TEST_LAYOUT.minBlockY, 0))

                val compressedSink = Buffer()
                assertEquals(info, regionHandle.readCompressedChunkTo(firstPosition, compressedSink))
                assertContentEquals(
                    checkNotNull(regionHandle.readCompressedChunk(firstPosition)).toByteArray(),
                    compressedSink.readByteArray(),
                )

                val nbtSink = Buffer()
                assertEquals(info, regionHandle.readChunkNbtTo(firstPosition, nbtSink))
                assertEquals(document, TEST_CODEC.nbt.decodeDocumentFromSource(nbtSink))
            }
        } finally {
            storage.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun compressedInputAndRegionReplacementStreamThroughWriteTo() = runTest {
        val fileSystem = FakeFileSystem()
        val storage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        val region = RegionPosition(3, -2)
        val first = TrackingCompressedInput(byteArrayOf(1, 2, 3))
        val second = TrackingCompressedInput(byteArrayOf(4, 5))

        try {
            storage.replaceRegion(
                region,
                listOf(
                    RegionChunkInput(LocalChunkPosition(0, 0), first),
                    RegionChunkInput(LocalChunkPosition(31, 31), second),
                ),
            )

            assertEquals(1, first.writeCount)
            assertEquals(1, second.writeCount)
            val storedFirst = storage.readCompressedChunk(region, LocalChunkPosition(0, 0))
            val storedSecond = storage.readCompressedChunk(region, LocalChunkPosition(31, 31))
            assertContentEquals(first.bytes, storedFirst?.toByteArray())
            assertContentEquals(second.bytes, storedSecond?.toByteArray())
        } finally {
            storage.close()
        }
        fileSystem.checkNoOpenFiles()
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
                layout = TEST_LAYOUT,
                registries = ChunkDataRegistries(
                    blockStates = DescriptorBlockStateRegistry(AIR),
                    biomes = NamedBiomeRegistry(),
                ),
                expectedDataVersion = TEST_DATA_VERSION,
            ),
        )

        fun emptyChunk(): Chunk<BlockStateDescriptor, String> = Chunk(
            metadata = ChunkMetadata(TEST_DATA_VERSION, status = "minecraft:full"),
            layout = TEST_LAYOUT,
            defaultBlockState = AIR,
            defaultBiome = "minecraft:plains",
        )
    }
}
