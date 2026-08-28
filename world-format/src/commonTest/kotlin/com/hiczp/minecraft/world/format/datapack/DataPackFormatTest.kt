package com.hiczp.minecraft.world.format.datapack

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.Compression
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class DataPackFormatTest {
    @Test
    fun customDecoderReceivesPackIdentityAndStreamingInputRejectsDuplicates() {
        val dataPackId = DataPackId("mod:generated")
        val dataPackFilePath = DataPackFilePath("data/mod/custom/value.mod")
        val dataPackFileBytes = DataPackFileBytes(byteArrayOf(1, 2, 3))
        val dataPackFormat = DataPackFormat(
            dataPackFileDecoders = listOf(
                DataPackFileDecoder { decodedDataPackId, decodedDataPackFilePath, decodedDataPackFileBytes ->
                    if (decodedDataPackFilePath == dataPackFilePath) {
                        ModFile(decodedDataPackId, decodedDataPackFilePath, decodedDataPackFileBytes.sizeInBytes)
                    } else {
                        null
                    }
                },
            ),
        )

        val dataPack = dataPackFormat.decode(dataPackId, sequenceOf(dataPackFilePath to dataPackFileBytes))

        assertEquals(
            ModFile(dataPackId, dataPackFilePath, 3),
            assertIs<ModFile>(dataPack.dataPackFileContent(dataPackFilePath)),
        )
        assertFailsWith<DataPackFormatException> {
            dataPackFormat.decode(
                dataPackId,
                sequenceOf(dataPackFilePath to dataPackFileBytes, dataPackFilePath to dataPackFileBytes),
            )
        }
    }

    @Test
    fun parsesEveryStandardContentKindAndCurrentMetadataVersion() {
        val dataPackMetadataJson = buildJsonObject {
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
        val structureDocument = NbtDocument(NbtCompound(mapOf("name" to NbtString("house"))))
        val structureFileBytes = Buffer().also { sink ->
            CompressedNbtFormat().encodeDocumentToSink(structureDocument, Compression.GZIP, sink)
        }.readByteArray()
        val dataPackArchive = DataPackArchive(
            DataPackId("test"),
            listOf(
                DataPackFilePath.PACK_METADATA to jsonBytes(dataPackMetadataJson),
                DataPackFilePath("data/test/worldgen/biome/example.json") to jsonBytes(
                    buildJsonObject { put("temperature", 0.5) },
                ),
                DataPackFilePath("data/test/structure/house.nbt") to structureFileBytes,
                DataPackFilePath("data/test/custom/value.snbt") to "{name:house}".encodeToByteArray(),
                DataPackFilePath("data/test/function/run.mcfunction") to "say hello".encodeToByteArray(),
                DataPackFilePath("data/test/mod/value.bin") to byteArrayOf(1, 2, 3),
            ),
        )

        val dataPack = DataPackFormat().decode(dataPackArchive)

        assertTrue(
            DataPackFormatVersion(107, 1) in
                    requireNotNull(dataPack.dataPackMetadata).supportedDataPackFormatVersionRange,
        )
        assertEquals(setOf("test:feature"), dataPack.dataPackMetadata.enabledFeatureFlags)
        val biomeJsonFile = assertIs<DataPackFileContent.JsonFile>(
            dataPack.dataPackFileContent(DataPackFilePath("data/test/worldgen/biome/example.json")),
        )
        assertEquals(BiomeJson(0.5), biomeJsonFile.decode(BiomeJson.serializer()))
        assertEquals(BiomeJson(0.5), biomeJsonFile.decode<BiomeJson>())
        assertEquals(
            structureDocument,
            assertIs<DataPackFileContent.NbtFile>(
                dataPack.dataPackFileContent(DataPackFilePath("data/test/structure/house.nbt")),
            ).nbtDocument,
        )
        assertEquals(
            NbtCompound(mapOf("name" to NbtString("house"))),
            assertIs<DataPackFileContent.SnbtFile>(
                dataPack.dataPackFileContent(DataPackFilePath("data/test/custom/value.snbt")),
            ).nbtTag,
        )
        assertEquals(
            "say hello",
            assertIs<DataPackFileContent.TextFile>(
                dataPack.dataPackFileContent(DataPackFilePath("data/test/function/run.mcfunction")),
            ).text,
        )
        assertEquals(
            DataPackFileBytes(byteArrayOf(1, 2, 3)),
            assertIs<DataPackFileContent.BinaryFile>(
                dataPack.dataPackFileContent(DataPackFilePath("data/test/mod/value.bin")),
            ).dataPackFileBytes,
        )
    }

    @Test
    fun compressedNbtFailureIsDeferredUntilTheDocumentIsRequested() {
        val dataPackId = DataPackId("test")
        val dataPackFilePath = DataPackFilePath("data/test/structure/invalid.nbt")
        val nbtFile = assertIs<DataPackFileContent.NbtFile>(
            DataPackFormat().decodeFile(
                dataPackId,
                dataPackFilePath,
                DataPackFileBytes(byteArrayOf(1, 2, 3)),
            ),
        )

        val failure = assertFailsWith<DataPackFormatException> { nbtFile.nbtDocument }

        assertContains(failure.message.orEmpty(), dataPackFilePath.value)
        assertContains(failure.message.orEmpty(), dataPackId.value)
    }

    @Test
    fun customDecoderFailuresRetainFileContextAndCancellation() {
        val dataPackId = DataPackId("test")
        val dataPackFilePath = DataPackFilePath("data/test/custom/value.mod")
        val dataPackFileBytes = DataPackFileBytes(byteArrayOf(1))
        val cause = IllegalStateException("decoder failed")
        val failure = assertFailsWith<DataPackFormatException> {
            DataPackFormat(dataPackFileDecoders = listOf(DataPackFileDecoder { _, _, _ -> throw cause }))
                .decode(dataPackId, sequenceOf(dataPackFilePath to dataPackFileBytes))
        }

        assertSame(cause, failure.cause)
        assertContains(failure.message.orEmpty(), dataPackFilePath.value)
        assertContains(failure.message.orEmpty(), dataPackId.value)

        val cancellationException = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> {
            DataPackFormat(dataPackFileDecoders = listOf(DataPackFileDecoder { _, _, _ -> throw cancellationException }))
                .decode(dataPackId, sequenceOf(dataPackFilePath to dataPackFileBytes))
        }
        assertSame(cancellationException, thrown)
    }

    @Test
    fun resolvesOverlaysFiltersAndTagAppendInPriorityOrder() {
        val dataPackFormatVersion = DataPackFormatVersion(107, 1)
        val baseDataPack = parsedDataPack(
            dataPackId = "base",
            dataPackMetadata = dataPackMetadata(),
            dataPackFileContentsByPath = mapOf(
                "data/test/tags/block/logs.json" to tagJson("test:oak"),
                "data/test/recipe/removed.json" to buildJsonObject { put("value", 1) },
            ),
        )
        val higherDataPackMetadata = dataPackMetadata(
            dataPackFilterPatterns = listOf(
                DataPackFilterPattern(namespacePattern = "test", pathPattern = "recipe/removed\\.json"),
                DataPackFilterPattern(namespacePattern = "test", pathPattern = "recipe/replaced\\.json"),
            ),
            dataPackOverlays = listOf(
                DataPackOverlay(
                    DataPackFormatVersionRange.exact(dataPackFormatVersion),
                    DataPackFilePath("overlay"),
                ),
            ),
        )
        val higherDataPack = parsedDataPack(
            dataPackId = "higher",
            dataPackMetadata = higherDataPackMetadata,
            dataPackFileContentsByPath = mapOf(
                "data/test/tags/block/logs.json" to tagJson("test:birch"),
                "data/test/recipe/replaced.json" to buildJsonObject { put("value", 3) },
                "data/test/recipe/value.json" to buildJsonObject { put("value", 1) },
                "overlay/data/test/recipe/value.json" to buildJsonObject { put("value", 2) },
            ),
        )

        val resolvedDataPackStack = DataPackStack(baseDataPack, higherDataPack).resolve(dataPackFormatVersion)

        assertEquals(null, resolvedDataPackStack.resource(DataPackResourcePath("test", "recipe/removed.json")))
        val recipeJson = assertIs<DataPackFileContent.JsonFile>(
            resolvedDataPackStack.resource(DataPackResourcePath("test", "recipe/value.json"))?.dataPackFileContent,
        ).jsonElement.jsonObject
        assertEquals(2, recipeJson.getValue("value").jsonPrimitive.int)
        val tagJson = assertIs<DataPackFileContent.JsonFile>(
            resolvedDataPackStack.resource(
                DataPackResourcePath("test", "tags/block/logs.json"),
            )?.dataPackFileContent,
        ).jsonElement.jsonObject
        assertEquals(
            listOf("test:oak", "test:birch"),
            tagJson.getValue("values").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(resolvedDataPackStack.filtersBaseResource(DataPackResourcePath("test", "recipe/removed.json")))
        assertTrue(resolvedDataPackStack.filtersBaseResource(DataPackResourcePath("test", "recipe/replaced.json")))
    }

    @Test
    fun tagFilesDecodeTypedEntries() {
        val dataPack = parsedDataPack(
            dataPackId = "tags",
            dataPackMetadata = dataPackMetadata(),
            dataPackFileContentsByPath = mapOf(
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

        val resolvedDataPackResource = requireNotNull(
            DataPackStack(dataPack).resolve().resource(DataPackResourcePath("test", "tags/block/values.json")),
        )

        assertEquals(
            DataPackTagFile(
                dataPackTagValues = listOf(
                    DataPackTagValue(DataPackResourceId("minecraft", "stone")),
                    DataPackTagValue(DataPackResourceId("test", "logs"), isTagReference = true),
                    DataPackTagValue(DataPackResourceId("test", "optional"), isRequired = false),
                ),
            ),
            resolvedDataPackResource.decodeDataPackTagFile(),
        )
    }

    private fun parsedDataPack(
        dataPackId: String,
        dataPackMetadata: DataPackMetadata,
        dataPackFileContentsByPath: Map<String, JsonElement>,
    ): DataPack = DataPack(
        dataPackId = DataPackId(dataPackId),
        dataPackMetadata = dataPackMetadata,
        dataPackFileContentsByPath = dataPackFileContentsByPath.mapKeys { DataPackFilePath(it.key) }
            .mapValues { DataPackFileContent.JsonFile(it.value) },
    )

    private fun dataPackMetadata(
        dataPackFilterPatterns: List<DataPackFilterPattern> = emptyList(),
        dataPackOverlays: List<DataPackOverlay> = emptyList(),
    ): DataPackMetadata = DataPackMetadata(
        description = JsonPrimitive("test"),
        supportedDataPackFormatVersionRange = DataPackFormatVersionRange.exact(DataPackFormatVersion(107, 1)),
        dataPackFilterPatterns = dataPackFilterPatterns,
        dataPackOverlays = dataPackOverlays,
    )

    private fun tagJson(value: String): JsonObject = buildJsonObject {
        put("values", buildJsonArray { add(value) })
    }

    private fun jsonBytes(jsonElement: JsonElement): ByteArray =
        Json.encodeToString(jsonElement).encodeToByteArray()

    private data class ModFile(
        val dataPackId: DataPackId,
        val dataPackFilePath: DataPackFilePath,
        val size: Int,
    ) : DataPackFileContent
}

@Serializable
private data class BiomeJson(
    val temperature: Double,
)
