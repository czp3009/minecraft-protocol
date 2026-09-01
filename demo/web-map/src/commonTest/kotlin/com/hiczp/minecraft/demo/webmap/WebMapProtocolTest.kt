package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
    fun sealedResultsRoundTripWithStatusDiscriminators() {
        val surfaceQueryResult: SurfaceQueryResult = SurfaceQueryResult.Success(
            SurfaceResponse(
                minChunkX = -1,
                minChunkZ = 2,
                maxChunkX = 0,
                maxChunkZ = 2,
                chunks = listOf(
                    SurfaceChunkResult.Success(
                        chunkX = -1,
                        chunkZ = 2,
                        surface = ChunkSurface(
                            palette = listOf(SurfaceBlockState(Identifier("oak_log"), mapOf("axis" to "y"))),
                            cells = listOf(0) + List(SURFACE_CELL_COUNT - 1) { null },
                        ),
                    ),
                    SurfaceChunkResult.ReadFailed(0, 2),
                ),
            ),
        )

        val encoded = WebMapJson.encodeToString(surfaceQueryResult)
        val jsonObject = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("success", jsonObject.getValue("status").jsonPrimitive.content)
        val chunks = jsonObject.getValue("response").jsonObject.getValue("chunks").jsonArray
        assertEquals("success", chunks[0].jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("read_failed", chunks[1].jsonObject.getValue("status").jsonPrimitive.content)

        val decoded = WebMapJson.decodeFromString<SurfaceQueryResult>(encoded)
        assertEquals(surfaceQueryResult, decoded)
        assertIs<SurfaceQueryResult.Success>(decoded)
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
