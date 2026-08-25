package com.hiczp.minecraft.world.format.datapack

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.Compression
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class DataPackFormatTest {
    @Test
    fun customDecoderReceivesPackIdentityAndStreamingInputRejectsDuplicates() {
        val id = DataPackId("mod:generated")
        val path = DataPackPath("data/mod/custom/value.mod")
        val bytes = DataPackBinary(byteArrayOf(1, 2, 3))
        val format = DataPackFormat(
            customDecoders = listOf(
                DataPackFileDecoder { packId, filePath, content ->
                    if (filePath == path) {
                        ModFile(packId, filePath, content.size)
                    } else {
                        null
                    }
                },
            ),
        )

        val pack = format.decode(id, sequenceOf(path to bytes))

        assertEquals(
            ModFile(id, path, 3),
            assertIs<ModFile>(pack.file(path)),
        )
        assertFailsWith<DataPackFormatException> {
            format.decode(id, sequenceOf(path to bytes, path to bytes))
        }
    }

    @Test
    fun parsesEveryStandardContentKindAndCurrentMetadataVersion() {
        val metadata = buildJsonObject {
            put(
                "pack",
                buildJsonObject {
                    put("description", "test")
                    put("min_format", buildJsonArray {
                        add(107)
                        add(1)
                    })
                    put("max_format", 107)
                },
            )
            put("features", buildJsonObject { put("enabled", buildJsonArray { add("test:feature") }) })
        }
        val structure = NbtDocument(NbtCompound(mapOf("name" to NbtString("house"))))
        val structureBytes = Buffer().also { sink ->
            CompressedNbtFormat().encodeDocumentToSink(structure, Compression.GZIP, sink)
        }.readByteArray()
        val archive = DataPackArchive(
            DataPackId("test"),
            listOf(
                DataPackPath.PACK_METADATA to jsonBytes(metadata),
                DataPackPath("data/test/worldgen/biome/example.json") to jsonBytes(
                    buildJsonObject { put("temperature", 0.5) },
                ),
                DataPackPath("data/test/structure/house.nbt") to structureBytes,
                DataPackPath("data/test/custom/value.snbt") to "{name:house}".encodeToByteArray(),
                DataPackPath("data/test/function/run.mcfunction") to "say hello".encodeToByteArray(),
                DataPackPath("data/test/mod/value.bin") to byteArrayOf(1, 2, 3),
            ),
        )

        val pack = DataPackFormat().decode(archive)

        assertTrue(DataPackFormatVersion(107, 1) in requireNotNull(pack.metadata).formats)
        assertEquals(setOf("test:feature"), pack.metadata.enabledFeatures)
        assertIs<DataPackFileContent.JsonFile>(pack.file(DataPackPath("data/test/worldgen/biome/example.json")))
        assertEquals(
            structure,
            assertIs<DataPackFileContent.NbtFile>(pack.file(DataPackPath("data/test/structure/house.nbt"))).document,
        )
        assertEquals(
            NbtCompound(mapOf("name" to NbtString("house"))),
            assertIs<DataPackFileContent.SnbtFile>(pack.file(DataPackPath("data/test/custom/value.snbt"))).tag,
        )
        assertEquals(
            "say hello",
            assertIs<DataPackFileContent.TextFile>(pack.file(DataPackPath("data/test/function/run.mcfunction"))).text,
        )
        assertEquals(
            DataPackBinary(byteArrayOf(1, 2, 3)),
            assertIs<DataPackFileContent.BinaryFile>(pack.file(DataPackPath("data/test/mod/value.bin"))).bytes,
        )
    }

    @Test
    fun customDecoderFailuresRetainFileContextAndCancellation() {
        val packId = DataPackId("test")
        val path = DataPackPath("data/test/custom/value.mod")
        val content = DataPackBinary(byteArrayOf(1))
        val cause = IllegalStateException("decoder failed")
        val failure = assertFailsWith<DataPackFormatException> {
            DataPackFormat(customDecoders = listOf(DataPackFileDecoder { _, _, _ -> throw cause }))
                .decode(packId, sequenceOf(path to content))
        }

        assertSame(cause, failure.cause)
        assertContains(failure.message.orEmpty(), path.value)
        assertContains(failure.message.orEmpty(), packId.value)

        val cancellation = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> {
            DataPackFormat(customDecoders = listOf(DataPackFileDecoder { _, _, _ -> throw cancellation }))
                .decode(packId, sequenceOf(path to content))
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun resolvesOverlaysFiltersAndTagAppendInPriorityOrder() {
        val format = DataPackFormatVersion(107, 1)
        val base = parsedPack(
            id = "base",
            metadata = metadata(),
            files = mapOf(
                "data/test/tags/block/logs.json" to tagJson("test:oak"),
                "data/test/recipe/removed.json" to buildJsonObject { put("value", 1) },
            ),
        )
        val higherMetadata = metadata(
            filters = listOf(
                DataPackFilterPattern(namespacePattern = "test", pathPattern = "recipe/removed\\.json"),
                DataPackFilterPattern(namespacePattern = "test", pathPattern = "recipe/replaced\\.json"),
            ),
            overlays = listOf(DataPackOverlay(DataPackFormatRange.exact(format), DataPackPath("overlay"))),
        )
        val higher = parsedPack(
            id = "higher",
            metadata = higherMetadata,
            files = mapOf(
                "data/test/tags/block/logs.json" to tagJson("test:birch"),
                "data/test/recipe/replaced.json" to buildJsonObject { put("value", 3) },
                "data/test/recipe/value.json" to buildJsonObject { put("value", 1) },
                "overlay/data/test/recipe/value.json" to buildJsonObject { put("value", 2) },
            ),
        )

        val resolved = DataPackStack(base, higher).resolve(format)

        assertEquals(null, resolved.resource(DataPackResourcePath("test", "recipe/removed.json")))
        val recipe = assertIs<DataPackFileContent.JsonFile>(
            resolved.resource(DataPackResourcePath("test", "recipe/value.json"))?.content,
        ).element.jsonObject
        assertEquals(2, recipe.getValue("value").jsonPrimitive.int)
        val tag = assertIs<DataPackFileContent.JsonFile>(
            resolved.resource(DataPackResourcePath("test", "tags/block/logs.json"))?.content,
        ).element.jsonObject
        assertEquals(
            listOf("test:oak", "test:birch"),
            tag.getValue("values").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(resolved.filtersBaseResource(DataPackResourcePath("test", "recipe/removed.json")))
        assertTrue(resolved.filtersBaseResource(DataPackResourcePath("test", "recipe/replaced.json")))
    }

    @Test
    fun tagFilesDecodeTypedEntries() {
        val pack = parsedPack(
            id = "tags",
            metadata = metadata(),
            files = mapOf(
                "data/test/tags/block/values.json" to buildJsonObject {
                    put(
                        "values",
                        buildJsonArray {
                            add("stone")
                            add("#test:logs")
                            add(buildJsonObject {
                                put("id", "test:optional")
                                put("required", false)
                            })
                        },
                    )
                },
            ),
        )

        val resource = requireNotNull(
            DataPackStack(pack).resolve().resource(DataPackResourcePath("test", "tags/block/values.json")),
        )

        assertEquals(
            DataPackTagFile(
                values = listOf(
                    DataPackTagEntry(DataPackResourceId("minecraft", "stone")),
                    DataPackTagEntry(DataPackResourceId("test", "logs"), tag = true),
                    DataPackTagEntry(DataPackResourceId("test", "optional"), required = false),
                ),
            ),
            resource.decodeTagFile(),
        )
    }

    private fun parsedPack(
        id: String,
        metadata: DataPackMetadata,
        files: Map<String, JsonElement>,
    ): DataPack = DataPack(
        id = DataPackId(id),
        metadata = metadata,
        files = files.mapKeys { DataPackPath(it.key) }.mapValues { DataPackFileContent.JsonFile(it.value) },
    )

    private fun metadata(
        filters: List<DataPackFilterPattern> = emptyList(),
        overlays: List<DataPackOverlay> = emptyList(),
    ): DataPackMetadata = DataPackMetadata(
        description = JsonPrimitive("test"),
        formats = DataPackFormatRange.exact(DataPackFormatVersion(107, 1)),
        filters = filters,
        overlays = overlays,
    )

    private fun tagJson(value: String): JsonObject = buildJsonObject {
        put("values", buildJsonArray { add(value) })
    }

    private fun jsonBytes(element: JsonElement): ByteArray =
        Json.encodeToString(element).encodeToByteArray()

    private data class ModFile(
        val packId: DataPackId,
        val path: DataPackPath,
        val size: Int,
    ) : DataPackFileContent
}
