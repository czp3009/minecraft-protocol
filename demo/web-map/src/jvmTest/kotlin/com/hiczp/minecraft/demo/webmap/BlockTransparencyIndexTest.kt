package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaBlockState
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BlockTransparencyIndexTest {
    @Test
    fun pngAlphaAndIncompleteTopGeometryRemainTransparent() {
        val resources = buildMap {
            put(blockStatePath("stone"), blockStateJson("stone_model"))
            put(blockStatePath("leaves"), blockStateJson("leaves_model"))
            put(blockStatePath("slab"), blockStateJson("slab_model"))
            put(modelPath("stone_model"), cubeModelJson("stone"))
            put(modelPath("leaves_model"), cubeModelJson("leaves"))
            put(modelPath("slab_model"), partialTopModelJson())
            put(texturePath("stone"), png(colorType = 2))
            put(texturePath("leaves"), png(colorType = 6))
        }
        val blockStates = listOf("stone", "leaves", "slab").mapIndexed { rawId, name ->
            VanillaBlockState(rawId, Identifier(name), emptyMap(), isDefault = true)
        }

        val blockAssetIndex = BlockAssetIndex.create(resources, blockStates)

        assertFalse(blockAssetIndex.isTransparent(SurfaceBlockState(Identifier("stone"))))
        assertTrue(blockAssetIndex.isTransparent(SurfaceBlockState(Identifier("leaves"))))
        assertTrue(blockAssetIndex.isTransparent(SurfaceBlockState(Identifier("slab"))))
    }

    @Test
    fun indexedPngTransparencyChunkIsDetected() {
        val resources = mapOf(
            blockStatePath("plant") to blockStateJson("plant_model"),
            modelPath("plant_model") to particleModelJson(),
            texturePath("plant") to png(colorType = 3, includeTransparencyChunk = true),
        )
        val blockStates = listOf(
            VanillaBlockState(0, Identifier("plant"), emptyMap(), isDefault = true),
        )

        val blockAssetIndex = BlockAssetIndex.create(resources, blockStates)

        assertTrue(blockAssetIndex.isTransparent(SurfaceBlockState(Identifier("plant"))))
    }

    @Test
    fun objectTextureSlotCanForceAnOpaquePngToRemainTranslucent() {
        val resources = mapOf(
            blockStatePath("glass") to blockStateJson("glass_model"),
            modelPath("glass_model") to forceTranslucentCubeModelJson(),
            texturePath("glass") to png(colorType = 2),
        )
        val blockStates = listOf(
            VanillaBlockState(0, Identifier("glass"), emptyMap(), isDefault = true),
        )

        val blockAssetIndex = BlockAssetIndex.create(resources, blockStates)

        assertTrue(blockAssetIndex.isTransparent(SurfaceBlockState(Identifier("glass"))))
    }

    @Test
    fun reusableRenderResourceContainsResolvedTopGeometryWhileAnimationStaysWithTheTexture() = runTest {
        val resources = mapOf(
            blockStatePath("stone") to blockStateJson("stone_model"),
            modelPath("stone_model") to cubeModelJson("stone"),
            texturePath("stone") to png(colorType = 2),
            "${texturePath("stone")}.mcmeta" to """{"animation":{"frames":[{"index":3}]}}""".encodeToByteArray(),
        )
        val surfaceBlockState = SurfaceBlockState(Identifier("stone"))
        val blockAssetIndex = BlockAssetIndex.create(
            resources,
            listOf(VanillaBlockState(0, Identifier("stone"), emptyMap(), isDefault = true)),
        )

        val blockRenderResource = assertNotNull(blockAssetIndex.blockRenderResource(surfaceBlockState))
        val surfaceSprite = assertNotNull(blockRenderResource.sprite(surfaceBlockState, blockX = 4, blockZ = 7))

        assertEquals(Identifier("block/stone"), surfaceSprite.layers.single().texture)
        assertEquals(SurfaceSpriteRectangle(0f, 0f, 16f, 16f), surfaceSprite.layers.single().destination)
        assertEquals(3, blockAssetIndex.animationFrame(Identifier("block/stone")))
        assertEquals(0, blockAssetIndex.animationFrame(Identifier("block/stone_without_metadata")))
    }

    @Test
    fun invertedOfficialModelCoordinatesAreNormalizedWithoutLosingTextureOrientation() = runTest {
        val resources = mapOf(
            blockStatePath("inverted") to blockStateJson("inverted_model"),
            modelPath("inverted_model") to invertedCubeModelJson(),
            texturePath("stone") to png(colorType = 2),
        )
        val surfaceBlockState = SurfaceBlockState(Identifier("inverted"))
        val blockAssetIndex = BlockAssetIndex.create(
            resources,
            listOf(VanillaBlockState(0, Identifier("inverted"), emptyMap(), isDefault = true)),
        )

        assertFalse(blockAssetIndex.isTransparent(surfaceBlockState))
        val blockRenderResource = assertNotNull(blockAssetIndex.blockRenderResource(surfaceBlockState))
        val surfaceSpriteLayer = assertNotNull(blockRenderResource.sprite(surfaceBlockState, blockX = 0, blockZ = 0))
            .layers.single()
        assertEquals(SurfaceSpriteRectangle(0f, 0f, 16f, 16f), surfaceSpriteLayer.destination)
        assertEquals(SurfaceSpriteRectangle(0f, 0f, 16f, 16f), surfaceSpriteLayer.uv)
        assertTrue(surfaceSpriteLayer.flipTextureX)
        assertFalse(surfaceSpriteLayer.flipTextureY)
    }

    private fun blockStatePath(name: String): String = "assets/minecraft/blockstates/$name.json"

    private fun modelPath(name: String): String = "assets/minecraft/models/block/$name.json"

    private fun texturePath(name: String): String = "assets/minecraft/textures/block/$name.png"

    private fun blockStateJson(model: String): ByteArray =
        """{"variants":{"":{"model":"minecraft:block/$model"}}}""".encodeToByteArray()

    private fun cubeModelJson(texture: String): ByteArray =
        """{"textures":{"all":"minecraft:block/$texture","particle":"#all"},"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"up":{"texture":"#all"}}}]}""".encodeToByteArray()

    private fun partialTopModelJson(): ByteArray =
        """{"textures":{"all":"minecraft:block/stone","particle":"#all"},"elements":[{"from":[0,0,0],"to":[8,16,16],"faces":{"up":{"texture":"#all"}}}]}""".encodeToByteArray()

    private fun forceTranslucentCubeModelJson(): ByteArray =
        """{"textures":{"all":{"sprite":"minecraft:block/glass","force_translucent":true}},"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"up":{"texture":"#all"}}}]}""".encodeToByteArray()

    private fun invertedCubeModelJson(): ByteArray =
        """{"textures":{"all":"minecraft:block/stone"},"elements":[{"from":[16,0,16],"to":[0,16,0],"faces":{"up":{"texture":"#all","uv":[16,0,0,16]}}}]}""".encodeToByteArray()

    private fun particleModelJson(): ByteArray =
        """{"textures":{"particle":"minecraft:block/plant"}}""".encodeToByteArray()

    private fun png(colorType: Int, includeTransparencyChunk: Boolean = false): ByteArray = buildList {
        addAll(listOf(-119, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
        addChunk(
            "IHDR",
            byteArrayOf(
                0, 0, 0, 16,
                0, 0, 0, 16,
                8,
                colorType.toByte(),
                0, 0, 0,
            ),
        )
        if (includeTransparencyChunk) addChunk("tRNS", byteArrayOf(0))
        addChunk("IEND", byteArrayOf())
    }.toByteArray()

    private fun MutableList<Byte>.addChunk(type: String, data: ByteArray) {
        val length = data.size
        add((length ushr 24).toByte())
        add((length ushr 16).toByte())
        add((length ushr 8).toByte())
        add(length.toByte())
        addAll(type.encodeToByteArray().toList())
        addAll(data.toList())
        repeat(4) { add(0) }
    }
}
