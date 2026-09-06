package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiRegionHandleTest {
    @Test
    fun mutableAndLivePoiApisMirrorGenericAndSemanticRegionReads() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        fakeFileSystem.createDirectories(minecraftWorldPaths.root)
        val regionPosition = RegionPosition(2, -3)
        val localChunkPosition = LocalChunkPosition(4, 5)
        val chunkPosition = regionPosition.chunk(localChunkPosition)
        val typedLocalChunkPosition = LocalChunkPosition(6, 7)
        val typedChunkPosition = regionPosition.chunk(typedLocalChunkPosition)
        val levelDat = testLevelDat(levelName = "typed-poi-region")
        val poiChunkContext = PoiChunkContext(DimensionId.Overworld, ChunkLayout(-4, 24))
        val poiChunkNbtDecoder = PoiChunkNbtDecoder(
            PoiChunkNbtDecoderContext(poiChunkContext, chunkPosition, NbtFormat, NbtPropertyReadMappings()),
        )
        val poiChunkNbtEncoder = PoiChunkNbtEncoder(
            PoiChunkNbtEncoderContext(
                poiChunkContext.chunkLayout,
                NbtFormat,
                NbtPropertyWriteMappings(),
                PoiChunkNbtMetadata(12345)
            ),
        )
        val poiChunk = PoiChunk(
            chunkPosition = chunkPosition,
            poiChunkContext = poiChunkContext,
            sections = linkedMapOf(
                4 to PoiSection(
                    isValid = true,
                    records = linkedMapOf(
                        BlockPosition(chunkPosition.x * 16 + 1, 65, chunkPosition.z * 16 + 2) to
                                PoiRecord(PoiTypeId("minecraft:home"), 1),
                    ),
                    properties = DataProperties(),
                ),
            ),
            properties = DataProperties(),
        )
        val poiDocument = poiChunkNbtEncoder.encodeDocument(poiChunk)
        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)

        assertFalse(minecraftWorldAccess.dimensions.overworld.hasPoiRegion(regionPosition))
        minecraftWorldAccess.dimensions.overworld.openPoiRegion(regionPosition).use { poiRegionHandle ->
            poiRegionHandle.writeChunk(poiChunk, poiChunkNbtEncoder, Compression.NONE)
            poiRegionHandle.writeChunkNbt(
                typedLocalChunkPosition,
                levelDat,
                Compression.NONE,
                LevelDat.serializer(),
            )
            poiRegionHandle.writeChunkNbt(typedChunkPosition, levelDat, Compression.NONE)
            assertEquals(levelDat, poiRegionHandle.readChunkNbt(typedChunkPosition, LevelDat.serializer()))
            assertEquals(levelDat, poiRegionHandle.readChunkNbt<LevelDat>(typedLocalChunkPosition))
            assertEquals(poiDocument, poiRegionHandle.readChunkNbtDocument(localChunkPosition))
            poiRegionHandle.withReadScope {
                assertEquals(poiDocument, readChunkNbtDocument(localChunkPosition))
                assertEquals(levelDat, readChunkNbt(typedLocalChunkPosition, LevelDat.serializer()))
                assertEquals(levelDat, readChunkNbt<LevelDat>(typedChunkPosition))
            }
            assertEquals(1, poiRegionHandle.readChunk(poiChunkNbtDecoder)?.poiChunk?.sections?.get(4)?.records?.size)
            assertEquals(
                1,
                poiRegionHandle.withReadScope { readChunk(poiChunkNbtDecoder) }?.poiChunk?.sections?.get(4)?.records?.size
            )
            assertEquals(12345, poiRegionHandle.readChunk(poiChunkNbtDecoder)?.poiChunkNbtMetadata?.dataVersion)
        }
        assertTrue(minecraftWorldAccess.dimensions.overworld.hasPoiRegion(regionPosition))
        assertEquals(listOf(regionPosition), minecraftWorldAccess.dimensions.overworld.listPoiRegionPositions())
        minecraftWorldAccess.close()

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertTrue(liveMinecraftWorldAccess.dimensions.overworld.hasPoiRegion(regionPosition))
        assertEquals(listOf(regionPosition), liveMinecraftWorldAccess.dimensions.overworld.listPoiRegionPositions())
        liveMinecraftWorldAccess.dimensions.overworld.openPoiRegion(regionPosition).use { livePoiRegionHandle ->
            assertEquals(poiDocument, livePoiRegionHandle.readChunkNbtDocument(localChunkPosition))
            assertEquals(levelDat, livePoiRegionHandle.readChunkNbt(typedChunkPosition, LevelDat.serializer()))
            assertEquals(levelDat, livePoiRegionHandle.readChunkNbt<LevelDat>(typedLocalChunkPosition))
            assertEquals(
                1,
                livePoiRegionHandle.readChunk(poiChunkNbtDecoder)?.poiChunk?.sections?.get(4)?.records?.size
            )
            livePoiRegionHandle.withReadScope({ selectedPosition ->
                PoiChunkNbtDecoder(poiChunkNbtDecoder.poiChunkNbtDecoderContext.copy(chunkPosition = selectedPosition))
            }) {
                assertEquals(1, readChunk(localChunkPosition)?.poiChunk?.sections?.get(4)?.records?.size)
                assertEquals(levelDat, readChunkNbt(typedLocalChunkPosition, LevelDat.serializer()))
                assertEquals(levelDat, readChunkNbt<LevelDat>(typedChunkPosition))
            }
        }
        fakeFileSystem.checkNoOpenFiles()
    }
}
