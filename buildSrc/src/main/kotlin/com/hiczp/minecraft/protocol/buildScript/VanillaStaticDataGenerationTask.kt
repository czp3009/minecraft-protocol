package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.io.path.isRegularFile

/**
 * Produces the multiplatform static registry catalogue from the matching
 * vanilla data-generator reports.
 */
@CacheableTask
abstract class GenerateVanillaStaticDataSourceTask :
    MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

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
        val target = repository.readMinecraftProtocolTarget()
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
        )
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle("Generated vanilla static data: $output")
    }

    private fun buildPayload(
        registries: JsonObject,
        blocks: JsonObject,
    ): String = buildString {
        appendLine(FORMAT)
        registries.entries.sortedBy { it.key }.forEach { (registryId, value) ->
            requireToken(registryId, "registry identifier")
            val entries = value.jsonObject.requiredObject("entries")
                .map { (entryId, entryValue) ->
                    requireToken(entryId, "registry entry identifier")
                    entryValue.jsonObject.requiredInt("protocol_id") to entryId
                }
                .sortedBy { it.first }
            check(entries.map { it.first } == entries.indices.toList()) {
                "$registryId protocol IDs are not contiguous from zero"
            }
            append("R\t")
            append(registryId)
            append('\t')
            append(entries.joinToString(",") { it.second })
            append('\n')
        }

        val statesById = linkedMapOf<Int, String>()
        blocks.entries.sortedBy { it.key }.forEach { (blockId, value) ->
            requireToken(blockId, "block identifier")
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
                    ?.joinToString(",") { (name, propertyValue) ->
                        requirePropertyToken(name, "block-state property name")
                        val property = propertyValue.jsonPrimitive.content
                        requirePropertyToken(
                            property,
                            "block-state property value",
                        )
                        "$name=$property"
                    }
                    .orEmpty()
                val line = buildString {
                    append("S\t")
                    append(stateId)
                    append('\t')
                    append(blockId)
                    append('\t')
                    append(if (isDefault) '1' else '0')
                    append('\t')
                    append(properties)
                }
                check(statesById.put(stateId, line) == null) {
                    "Duplicate block-state ID $stateId"
                }
            }
        }
        val orderedIds = statesById.keys.sorted()
        check(orderedIds == orderedIds.indices.toList()) {
            "Block-state IDs are not contiguous from zero"
        }
        orderedIds.forEach { stateId ->
            appendLine(statesById.getValue(stateId))
        }
    }

    private fun renderSource(
        minecraftVersion: String,
        protocolVersion: Int,
        payload: String,
    ): String {
        val encoded = Base64.getEncoder().encodeToString(
            payload.toByteArray(StandardCharsets.UTF_8),
        )
        val chunks = encoded.chunked(SOURCE_CHUNK_SIZE)
        return buildString {
            appendLine("package com.hiczp.minecraft.protocol.data")
            appendLine()
            appendLine(
                "/** Exact official static registries and block states; " +
                        "regenerated by Gradle. */",
            )
            appendLine("internal object VanillaStaticDataPayloads {")
            appendLine(
                "    const val minecraftVersion: String = " +
                        "\"${escapeKotlin(minecraftVersion)}\"",
            )
            appendLine(
                "    const val protocolVersion: Int = $protocolVersion",
            )
            appendLine()
            appendLine("    val payload: List<String> = listOf(")
            chunks.forEach { chunk ->
                appendLine("        \"$chunk\",")
            }
            appendLine("    )")
            appendLine("}")
        }
    }

    private fun requireToken(value: String, description: String) {
        require(
            value.isNotEmpty() &&
                    value.none { it == '\t' || it == '\n' || it == '\r' || it == ',' },
        ) {
            "Unsafe $description '$value' in vanilla report"
        }
    }

    private fun requirePropertyToken(value: String, description: String) {
        requireToken(value, description)
        require('=' !in value) {
            "Unsafe $description '$value' in vanilla report"
        }
    }

    private fun escapeKotlin(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val FORMAT: String = "minecraft-static-data-v1"
        private const val SOURCE_CHUNK_SIZE: Int = 12_000
    }
}
