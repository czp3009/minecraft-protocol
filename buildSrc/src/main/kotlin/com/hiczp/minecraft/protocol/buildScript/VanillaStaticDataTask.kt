package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Produces the committed, multiplatform static registry catalogue from the
 * matching vanilla data-generator reports. The runtime never reads reports or
 * downloads artifacts.
 */
abstract class VanillaStaticDataTask : MinecraftProtocolToolTask() {
    @get:Input
    abstract val checkOnly: Property<Boolean>

    init {
        checkOnly.convention(true)
    }

    @TaskAction
    fun generateOrCheck() {
        val target = repository.readMinecraftProtocolTarget()
        val reports = repository.resolve(
            "build/protocol-reference/mojang/" +
                    "${target.minecraftVersion}/generated/reports",
        )
        val registriesPath = reports.resolve("registries.json")
        val blocksPath = reports.resolve("blocks.json")
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
        val output = repository.resolve(
            "protocol-vanilla-data/src/commonMain/kotlin/com/hiczp/" +
                    "minecraft/protocol/data/VanillaStaticDataPayloads.kt",
        )
        if (checkOnly.get()) {
            check(output.isRegularFile()) {
                "Vanilla static data is absent; run updateVanillaStaticData"
            }
            check(output.readText() == source) {
                "Committed vanilla static data is stale; run " +
                        "updateVanillaStaticData and review the diff"
            }
            logger.lifecycle(
                "Vanilla static registry and block-state data match the " +
                        "official ${target.minecraftVersion} reports.",
            )
        } else {
            val changed = output.writeIfChanged(source)
            logger.lifecycle(
                if (changed) {
                    "Updated vanilla static data: $output"
                } else {
                    "Vanilla static data is already current: $output"
                },
            )
        }
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
