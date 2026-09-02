package com.hiczp.minecraft.buildlogic

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Produces the multiplatform vanilla registry catalogue from the matching data-generator reports.
 */
@CacheableTask
abstract class GenerateVanillaRegistryDataSourceTask :
    DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val registriesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val blocksFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val registryReportPath = registriesFile.asFile.get().toPath()
        val blockReportPath = blocksFile.asFile.get().toPath()
        check(registryReportPath.isRegularFile()) {
            "Vanilla registries report is missing: $registryReportPath"
        }
        check(blockReportPath.isRegularFile()) {
            "Vanilla blocks report is missing: $blockReportPath"
        }

        val vanillaRegistryDataPayload = buildVanillaRegistryDataPayload(
            protocolJson.decodeFromString<JsonObject>(registryReportPath.readText()),
            protocolJson.decodeFromString<JsonObject>(blockReportPath.readText()),
        )
        val sourceFile = renderSource(vanillaRegistryDataPayload).toString()
        val outputFilePath = outputFile.asFile.get().toPath()
        outputFilePath.atomicWriteText(sourceFile)
        logger.lifecycle("Generated vanilla registry data: $outputFilePath")
    }

    private fun buildVanillaRegistryDataPayload(
        registryReport: JsonObject,
        blockReport: JsonObject,
    ): String {
        val registryPayloads = buildJsonArray {
            registryReport.entries.sortedBy { it.key }.forEach { (registryId, registryReportEntry) ->
                val registryEntries = registryReportEntry.jsonObject.getValue("entries").jsonObject
                    .map { (entryId, entryValue) ->
                        entryValue.jsonObject.getValue("protocol_id").jsonPrimitive.int to entryId
                    }
                    .sortedBy { it.first }
                check(registryEntries.map { it.first } == registryEntries.indices.toList()) {
                    "$registryId protocol IDs are not contiguous from zero"
                }
                add(
                    buildJsonObject {
                        put("id", registryId)
                        put(
                            "entries",
                            buildJsonArray {
                                registryEntries.forEach { (_, registryEntryId) ->
                                    add(registryEntryId)
                                }
                            },
                        )
                    },
                )
            }
        }

        val blockStatePayloadsByRawId = linkedMapOf<Int, JsonObject>()
        blockReport.entries.sortedBy { it.key }.forEach { (blockId, blockReportEntry) ->
            blockReportEntry.jsonObject.getValue("states").jsonArray.forEach { blockStateElement ->
                val blockStateJson = blockStateElement.jsonObject
                val blockStateRawId = blockStateJson.getValue("id").jsonPrimitive.int
                check(blockStateRawId >= 0) {
                    "$blockId has a negative block-state ID"
                }
                val isDefault = blockStateJson["default"]?.jsonPrimitive?.boolean ?: false
                val blockStateProperties = blockStateJson["properties"]
                    ?.jsonObject
                    ?.entries
                    ?.sortedBy { it.key }
                    .orEmpty()
                val blockStatePayload = buildJsonObject {
                    put("id", blockStateRawId)
                    put("block", blockId)
                    put(
                        "properties",
                        buildJsonObject {
                            blockStateProperties.forEach { (propertyName, propertyValue) ->
                                put(propertyName, propertyValue.jsonPrimitive.content)
                            }
                        },
                    )
                    put("isDefault", isDefault)
                }
                check(blockStatePayloadsByRawId.put(blockStateRawId, blockStatePayload) == null) {
                    "Duplicate block-state ID $blockStateRawId"
                }
            }
        }
        val orderedBlockStateRawIds = blockStatePayloadsByRawId.keys.sorted()
        val vanillaRegistryDataPayload = buildJsonObject {
            put("format", REGISTRY_DATA_FORMAT)
            put("registries", registryPayloads)
            put(
                "blockStates",
                buildJsonArray {
                    orderedBlockStateRawIds.forEach { blockStateRawId ->
                        add(blockStatePayloadsByRawId.getValue(blockStateRawId))
                    }
                },
            )
        }
        return Json.encodeToString(vanillaRegistryDataPayload)
    }

    private fun renderSource(vanillaRegistryDataPayload: String): FileSpec {
        val encodedVanillaRegistryDataPayload = Base64.getEncoder().encodeToString(
            vanillaRegistryDataPayload.toByteArray(StandardCharsets.UTF_8),
        )
        val payloadChunks = encodedVanillaRegistryDataPayload.chunked(SOURCE_CHUNK_SIZE)
        val payloadChunksInitializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply {
                payloadChunks.forEach { payloadChunk -> add("%S,\n", payloadChunk) }
            }
            .unindent()
            .add(")")
            .build()
        return FileSpec.builder(
            "com.hiczp.minecraft.protocol.datapack.vanilla",
            "VanillaRegistryDataPayloads",
        ).addType(
            TypeSpec.objectBuilder("VanillaRegistryDataPayloads")
                .addModifiers(INTERNAL)
                .addKdoc(
                    "Exact official registries and block states; regenerated by Gradle.\n",
                )
                .addProperty(
                    PropertySpec.builder(
                        "payloadChunks",
                        LIST.parameterizedBy(STRING),
                    ).initializer(payloadChunksInitializer)
                        .build(),
                )
                .build(),
        ).build()
    }

    companion object {
        private const val REGISTRY_DATA_FORMAT: String = "minecraft-registry-data-v2"
        private const val SOURCE_CHUNK_SIZE: Int = 12_000
        private val LIST_OF = MemberName("kotlin.collections", "listOf")
    }
}
