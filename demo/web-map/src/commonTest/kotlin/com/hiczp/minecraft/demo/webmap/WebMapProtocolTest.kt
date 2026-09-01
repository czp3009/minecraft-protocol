package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class WebMapProtocolTest {
    @Test
    fun chunkViewportNormalizesInclusiveEndpointsWithoutOverflow() {
        val chunkViewport = ChunkViewport(
            minChunkX = 7,
            minChunkZ = 4,
            maxChunkX = -2,
            maxChunkZ = -9,
        )

        assertEquals(ChunkViewport(-2, -9, 7, 4), chunkViewport.normalized)
        assertEquals(10L, chunkViewport.width)
        assertEquals(14L, chunkViewport.height)
        assertEquals(140L, chunkViewport.chunkCount)
        assertTrue(chunkViewport.isWithinQueryLimits)

        val maximumCoordinates = ChunkViewport(Int.MIN_VALUE, 0, Int.MAX_VALUE, 0)
        assertEquals(4_294_967_296L, maximumCoordinates.width)
        assertFalse(maximumCoordinates.isWithinQueryLimits)
    }

    @Test
    fun singleChunkAndCrossRegionRangesRetainBothEnds() {
        val single = ChunkViewport.single(ChunkCoordinate(-33, 32))
        assertEquals(listOf(ChunkCoordinate(-33, 32)), single.chunkRange.map(ChunkCoordinate::from))

        val crossRegion = ChunkViewport(-1, -1, 32, 32)
        assertEquals(9, crossRegion.chunkRange.regionPositions().count())
        assertEquals(34L * 34L, crossRegion.chunkCount)
    }

    @Test
    fun chunkUpdatesRoundTripWithTimestampsAndLayeredColumns() {
        val surfaceQueryUpdate: SurfaceQueryUpdate = SurfaceQueryUpdate.Chunk(
            SurfaceChunkResult.Success(
                chunkX = 30,
                chunkZ = 2,
                timestampEpochSeconds = 1_234_567_890,
                surface = ChunkSurface(
                    palette = listOf(
                        SurfaceColumn(
                            listOf(
                                SurfaceBlockState(Identifier("oak_leaves"), mapOf("persistent" to "true")),
                                SurfaceBlockState(Identifier("grass_block"), mapOf("snowy" to "false")),
                                SurfaceBlockState(Identifier("dirt")),
                            ),
                        ),
                    ),
                    cells = listOf(0) + List(SURFACE_CELL_COUNT - 1) { null },
                ),
            ),
        )

        val encoded = WebMapJson.encodeToString(surfaceQueryUpdate)
        assertFalse("\"texture\"" in encoded)
        val jsonObject = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("chunk", jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(
            "success",
            jsonObject.getValue("result").jsonObject.getValue("status").jsonPrimitive.content,
        )

        val decoded = WebMapJson.decodeFromString<SurfaceQueryUpdate>(encoded)
        assertEquals(surfaceQueryUpdate, decoded)
        assertIs<SurfaceQueryUpdate.Chunk>(decoded)
    }

    @Test
    fun reusableBlockAndTextureResourcesRoundTripOutsideTheChunkPayload() {
        val surfaceBlockState = SurfaceBlockState(Identifier("grass_block"), mapOf("snowy" to "false"))
        val surfaceSpriteLayer = SurfaceSpriteLayer(
            texture = Identifier("block/grass_block_top"),
            destination = SurfaceSpriteRectangle(0f, 0f, 16f, 16f),
            uv = SurfaceSpriteRectangle(0f, 0f, 16f, 16f),
            yRotation = 0,
            textureRotation = 0,
            tintColor = "#78a84f",
        )
        val blockRenderResourceResult = BlockRenderResourceResult(
            blockState = surfaceBlockState,
            resource = BlockRenderResource(
                modelChoices = listOf(
                    SurfaceModelChoice(
                        models = listOf(
                            SurfaceModelResource(
                                weight = 1,
                                topLayers = listOf(surfaceSpriteLayer),
                                particleLayer = null,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            blockRenderResourceResult,
            WebMapJson.decodeFromString<BlockRenderResourceResult>(
                WebMapJson.encodeToString(blockRenderResourceResult),
            ),
        )
        assertEquals(
            SurfaceSprite(listOf(surfaceSpriteLayer)),
            blockRenderResourceResult.resource?.sprite(surfaceBlockState, blockX = 12, blockZ = -4),
        )

        val textureResource = TextureResource(byteArrayOf(1, 2, 3), animationFrame = 2)
        val encodedTextureResource = WebMapJson.encodeToString(textureResource)
        assertTrue("\"AQID\"" in encodedTextureResource)
        assertEquals(
            textureResource,
            WebMapJson.decodeFromString<TextureResource>(encodedTextureResource),
        )
    }

    @Test
    fun assetProgressRoundTripsEveryDetailedCounter() {
        val assetLoadStatus: AssetLoadStatus = AssetLoadStatus.Loading(
            action = "Indexing block assets",
            detail = "Reading retained files",
            completedSteps = 3,
            totalSteps = 5,
            loadedFiles = 128,
            totalFiles = 1_024,
            loadedBytes = 4_096,
            totalBytes = 32_768,
        )

        assertEquals(
            assetLoadStatus,
            WebMapJson.decodeFromString<AssetLoadStatus>(WebMapJson.encodeToString(assetLoadStatus)),
        )
    }

    @Test
    fun metadataRetainsNamespacedDimensions() {
        val metadata = WorldMetadata(
            minecraftVersion = "selected-release",
            dimensionIds = listOf(DimensionId.Overworld, DimensionId("moon", "example")),
        )

        assertEquals(metadata, WebMapJson.decodeFromString<WorldMetadata>(WebMapJson.encodeToString(metadata)))
    }
}
