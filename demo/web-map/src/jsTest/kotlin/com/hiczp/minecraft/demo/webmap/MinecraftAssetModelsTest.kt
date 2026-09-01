package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class MinecraftAssetModelsTest {
    @Test
    fun selectsVariantAndMatchingMultipartModels() {
        val definition = MinecraftAssetJsonParser.parseBlockState(
            Json.parseToJsonElement(
                """
                {
                  "variants": {
                    "axis=x": { "model": "minecraft:block/oak_log", "x": 90, "y": 90 },
                    "axis=y": { "model": "minecraft:block/oak_log" }
                  },
                  "multipart": [
                    {
                      "when": { "waterlogged": "true" },
                      "apply": { "model": "minecraft:block/water_overlay" }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val selected = definition.select(
            SurfaceBlockState(
                name = Identifier("oak_log"),
                properties = mapOf("axis" to "x", "waterlogged" to "true"),
            ),
            blockX = 12,
            blockZ = -4,
        )

        assertEquals(
            listOf("minecraft:block/oak_log", "minecraft:block/water_overlay"),
            selected.map { it.model.toString() })
        assertEquals(90, selected.first().xRotation)
        assertEquals(90, selected.first().yRotation)
    }

    @Test
    fun weightedSelectionIsDeterministicForCanonicalStateAndWorldPosition() {
        val definition = MinecraftAssetJsonParser.parseBlockState(
            Json.parseToJsonElement(
                """
                {
                  "variants": {
                    "": [
                      { "model": "minecraft:block/a", "weight": 1 },
                      { "model": "minecraft:block/b", "weight": 7 }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        val firstState = SurfaceBlockState(Identifier("test"), mapOf("b" to "2", "a" to "1"))
        val reorderedState = SurfaceBlockState(Identifier("test"), mapOf("a" to "1", "b" to "2"))

        assertEquals(definition.select(firstState, 12, -4), definition.select(reorderedState, 12, -4))
        val selectedModels = (0..63).map { blockX -> definition.select(firstState, blockX, -4).single().model }.toSet()
        assertEquals(setOf(Identifier("block/a"), Identifier("block/b")), selectedModels)
        assertEquals(firstState.canonicalAssetKey(), reorderedState.canonicalAssetKey())
        assertNotEquals(0, stableAssetHash(firstState.canonicalAssetKey()))
    }

    @Test
    fun resolvesTextureReferencesAndRejectsCycles() {
        val textures = mapOf(
            "top" to AssetTextureSlot("#all"),
            "all" to AssetTextureSlot("minecraft:block/stone", forceTranslucent = true),
        )

        assertEquals(
            AssetTextureSlot("minecraft:block/stone", forceTranslucent = true),
            resolveTextureReference(textures, "#top"),
        )
        assertEquals(
            null,
            resolveTextureReference(
                mapOf("a" to AssetTextureSlot("#b"), "b" to AssetTextureSlot("#a")),
                "#a",
            ),
        )
    }

    @Test
    fun readsFirstExplicitAnimationFrame() {
        val metadata = Json.parseToJsonElement(
            """
            {
              "animation": {
                "frames": [3, { "index": 1, "time": 2 }]
              }
            }
            """.trimIndent(),
        )

        assertEquals(3, MinecraftAssetJsonParser.firstAnimationFrame(metadata))
        assertEquals(0, MinecraftAssetJsonParser.firstAnimationFrame(null))
    }

    @Test
    fun preservesParticleTextureForModelsWithoutElements() {
        val model = MinecraftAssetJsonParser.parseModel(
            Json.parseToJsonElement(
                """
                {
                  "textures": {
                    "particle": "minecraft:block/water_still"
                  }
                }
                """.trimIndent(),
            ),
            defaultNamespace = "minecraft",
        )

        assertEquals(AssetTextureSlot("minecraft:block/water_still"), model.textures["particle"])
        assertNull(model.elements)
    }

    @Test
    fun readsObjectTextureSlots() {
        val model = MinecraftAssetJsonParser.parseModel(
            Json.parseToJsonElement(
                """
                {
                  "textures": {
                    "all": {
                      "sprite": "minecraft:block/black_stained_glass",
                      "force_translucent": true
                    }
                  }
                }
                """.trimIndent(),
            ),
            defaultNamespace = "minecraft",
        )

        assertEquals(
            AssetTextureSlot("minecraft:block/black_stained_glass", forceTranslucent = true),
            model.textures["all"],
        )
    }

    @Test
    fun inFlightAndUnavailableResourcesAreCached() = runTest {
        var loadCount = 0
        val cache = AsyncResourceCache<String, String>(this) { key ->
            loadCount++
            if (key == "missing") CachedResource.Unavailable("missing") else CachedResource.Available("value-$key")
        }

        val first = async { cache.get("shared") }
        val second = async { cache.get("shared") }
        assertEquals(CachedResource.Available("value-shared"), first.await())
        assertEquals(CachedResource.Available("value-shared"), second.await())
        assertEquals(1, loadCount)
        assertIs<CachedResource.Unavailable>(cache.get("missing"))
        assertIs<CachedResource.Unavailable>(cache.get("missing"))
        assertEquals(2, loadCount)
        assertEquals(2, cache.size)
        assertEquals(CachedResource.Available("value-shared"), cache.completed("shared"))
        assertIs<CachedResource.Unavailable>(cache.completed("missing"))
    }
}
