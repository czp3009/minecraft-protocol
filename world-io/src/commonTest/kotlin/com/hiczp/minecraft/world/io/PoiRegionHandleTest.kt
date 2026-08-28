package com.hiczp.minecraft.world.io

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
        val poiChunkNbtCodec = PoiChunkNbtCodec()
        val poiChunk = PoiChunk(
            chunkPosition = chunkPosition,
            dataVersion = 4_903,
            sections = listOf(
                PoiSection(
                    sectionY = 4,
                    valid = true,
                    records = listOf(
                        PoiRecord(
                            type = "minecraft:home",
                            blockPosition = BlockPosition(chunkPosition.x * 16 + 1, 65, chunkPosition.z * 16 + 2),
                            freeTickets = 1,
                        ),
                    ),
                ),
            ),
        )
        val poiDocument = poiChunkNbtCodec.encodeDocument(poiChunk)
        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)

        assertFalse(minecraftWorldAccess.hasPoiRegion(regionPosition))
        minecraftWorldAccess.openPoiRegion(regionPosition).use { poiRegionHandle ->
            poiRegionHandle.writeChunk(poiChunk, Compression.NONE)
            assertEquals(poiDocument, poiRegionHandle.readChunkNbtDocument(localChunkPosition))
            assertEquals(poiDocument, poiRegionHandle.withReadScope { readChunkNbtDocument(localChunkPosition) })
            assertEquals(1, poiRegionHandle.readChunk(localChunkPosition)?.recordCount)
            assertEquals(1, poiRegionHandle.withReadScope { readChunk(chunkPosition) }?.recordCount)
        }
        assertTrue(minecraftWorldAccess.hasPoiRegion(regionPosition))
        assertEquals(listOf(regionPosition), minecraftWorldAccess.listPoiRegionPositions())
        minecraftWorldAccess.close()

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertTrue(liveMinecraftWorldAccess.hasPoiRegion(regionPosition))
        assertEquals(listOf(regionPosition), liveMinecraftWorldAccess.listPoiRegionPositions())
        liveMinecraftWorldAccess.openPoiRegion(regionPosition).use { livePoiRegionHandle ->
            assertEquals(poiDocument, livePoiRegionHandle.readChunkNbtDocument(localChunkPosition))
            assertEquals(1, livePoiRegionHandle.readChunk(chunkPosition)?.recordCount)
            assertEquals(
                1,
                livePoiRegionHandle.withReadScope { readChunk(localChunkPosition) }?.recordCount,
            )
        }
        fakeFileSystem.checkNoOpenFiles()
    }
}
