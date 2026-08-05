package com.hiczp.minecraft.buildlogic

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier.CONST
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.io.path.isRegularFile

/**
 * Produces the multiplatform static registry catalogue from the matching
 * vanilla data-generator reports.
 */
@CacheableTask
abstract class GenerateVanillaStaticDataSourceTask :
    DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetFile: RegularFileProperty

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
        val target = targetFile.asFile.get().toPath()
            .readOfficialMinecraftTargetReport()
            .target
        val registriesPath = registriesFile.asFile.get().toPath()
        val blocksPath = blocksFile.asFile.get().toPath()
        check(registriesPath.isRegularFile()) {
            "Vanilla registries report is missing: $registriesPath"
        }
        check(blocksPath.isRegularFile()) {
            "Vanilla blocks report is missing: $blocksPath"
        }

        val payload = buildPayload(
            registriesPath.readJsonObject(),
            blocksPath.readJsonObject(),
        )
        val source = renderSource(
            minecraftVersion = target.minecraftVersion,
            protocolVersion = target.protocolVersion,
            payload = payload,
        ).toString()
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle("Generated vanilla static data: $output")
    }

    private fun buildPayload(
        registries: JsonObject,
        blocks: JsonObject,
    ): String {
        val registryPayloads = buildJsonArray {
            registries.entries.sortedBy { it.key }.forEach { (registryId, value) ->
                val entries = value.jsonObject.requiredObject("entries")
                    .map { (entryId, entryValue) ->
                        entryValue.jsonObject.requiredInt("protocol_id") to entryId
                    }
                    .sortedBy { it.first }
                check(entries.map { it.first } == entries.indices.toList()) {
                    "$registryId protocol IDs are not contiguous from zero"
                }
                add(
                    buildJsonObject {
                        put("id", registryId)
                        put(
                            "entries",
                            buildJsonArray {
                                entries.forEach { (_, entryId) ->
                                    add(entryId)
                                }
                            },
                        )
                    },
                )
            }
        }

        val statesById = linkedMapOf<Int, JsonObject>()
        blocks.entries.sortedBy { it.key }.forEach { (blockId, value) ->
            value.jsonObject.requiredArray("states").forEach { element ->
                val state = element.jsonObject
                val stateId = state.requiredInt("id")
                check(stateId >= 0) {
                    "$blockId has a negative block-state ID"
                }
                val isDefault = state["default"]
                    ?.jsonPrimitive
                    ?.booleanOrNull == true
                val properties = state["properties"]
                    ?.jsonObject
                    ?.entries
                    ?.sortedBy { it.key }
                    .orEmpty()
                val payload = buildJsonObject {
                    put("id", stateId)
                    put("block", blockId)
                    put(
                        "properties",
                        buildJsonObject {
                            properties.forEach { (name, propertyValue) ->
                                put(name, propertyValue.jsonPrimitive.content)
                            }
                        },
                    )
                    put("isDefault", isDefault)
                }
                check(statesById.put(stateId, payload) == null) {
                    "Duplicate block-state ID $stateId"
                }
            }
        }
        val orderedIds = statesById.keys.sorted()
        check(orderedIds == orderedIds.indices.toList()) {
            "Block-state IDs are not contiguous from zero"
        }
        val payload = buildJsonObject {
            put("format", FORMAT)
            put("registries", registryPayloads)
            put(
                "blockStates",
                buildJsonArray {
                    orderedIds.forEach { stateId ->
                        add(statesById.getValue(stateId))
                    }
                },
            )
        }
        return Json.encodeToString(payload)
    }

    private fun renderSource(
        minecraftVersion: String,
        protocolVersion: Int,
        payload: String,
    ): FileSpec {
        val encoded = Base64.getEncoder().encodeToString(
            payload.toByteArray(StandardCharsets.UTF_8),
        )
        val chunks = encoded.chunked(SOURCE_CHUNK_SIZE)
        val payloadInitializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply {
                chunks.forEach { chunk -> add("%S,\n", chunk) }
            }
            .unindent()
            .add(")")
            .build()
        return FileSpec.builder(
            "com.hiczp.minecraft.protocol.data",
            "VanillaStaticDataPayloads",
        ).addType(
            TypeSpec.objectBuilder("VanillaStaticDataPayloads")
                .addModifiers(INTERNAL)
                .addKdoc(
                    "Exact official static registries and block states; regenerated by Gradle.\n",
                )
                .addProperty(
                    PropertySpec.builder("minecraftVersion", STRING, CONST)
                        .initializer("%S", minecraftVersion)
                        .build(),
                )
                .addProperty(
                    PropertySpec.builder("protocolVersion", Int::class, CONST)
                        .initializer("%L", protocolVersion)
                        .build(),
                )
                .addProperty(
                    PropertySpec.builder(
                        "payload",
                        LIST.parameterizedBy(STRING),
                    ).initializer(payloadInitializer)
                        .build(),
                )
                .build(),
        ).build()
    }

    companion object {
        private const val FORMAT: String = "minecraft-static-data-v2"
        private const val SOURCE_CHUNK_SIZE: Int = 12_000
        private val LIST_OF = MemberName("kotlin.collections", "listOf")
    }
}
