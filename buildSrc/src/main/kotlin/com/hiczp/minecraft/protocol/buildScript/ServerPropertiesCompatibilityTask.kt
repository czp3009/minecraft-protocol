package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

abstract class CheckOfficialServerPropertiesCompatibilityTask :
    MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val officialPropertiesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compatibilityFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun checkCompatibility() {
        val target = repository.readMinecraftProtocolTarget()
        val official = officialPropertiesFile.asFile.get().toPath()
            .readJsonObject()
        val compatibility = compatibilityFile.asFile.get().toPath()
            .readJsonObject()
        val errors = auditServerPropertiesCompatibility(
            official = official,
            compatibility = compatibility,
        ).toMutableList()

        if (
            official.requiredString("minecraft_version") !=
            target.minecraftVersion
        ) {
            errors +=
                "Official property report targets " +
                        official.requiredString("minecraft_version") +
                        ", expected ${target.minecraftVersion}"
        }
        if (
            official.requiredInt("protocol_version") != target.protocolVersion
        ) {
            errors +=
                "Official property report protocol is " +
                        official.requiredInt("protocol_version") +
                        ", expected ${target.protocolVersion}"
        }

        val entries = compatibility.requiredArray("properties")
            .map { it.jsonObject }
        val scopeCounts = SERVER_PROPERTY_SCOPES.associateWith { scope ->
            entries.count { it.requiredString("scope") == scope }
        }
        val supportCounts = SERVER_PROPERTY_SUPPORT.associateWith { support ->
            entries.count { it.requiredString("library_support") == support }
        }
        val report = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to
                    jsonString(official.requiredString("minecraft_version")),
            "protocol_version" to
                    jsonNumber(official.requiredInt("protocol_version")),
            "official_server_sha1" to
                    jsonString(official.requiredString("official_server_sha1")),
            "property_count" to jsonNumber(entries.size),
            "status" to jsonString(if (errors.isEmpty()) "pass" else "fail"),
            "scope_counts" to JsonObject(
                scopeCounts.mapValues { jsonNumber(it.value) },
            ),
            "library_support_counts" to JsonObject(
                supportCounts.mapValues { jsonNumber(it.value) },
            ),
            "errors" to JsonArray(errors.map(::jsonString)),
        )
        val output = reportFile.asFile.get().toPath()
        output.writeJson(report)
        check(errors.isEmpty()) {
            "Official server.properties compatibility audit failed:\n" +
                    errors.joinToString("\n") { "- $it" }
        }
        logger.lifecycle(
            "Verified ${entries.size} official server.properties entries: " +
                    output,
        )
    }
}

internal fun auditServerPropertiesCompatibility(
    official: JsonObject,
    compatibility: JsonObject,
): List<String> {
    val errors = mutableListOf<String>()
    if (official.requiredInt("schema_version") != 1) {
        errors += "Official property report schema must be 1"
    }
    if (compatibility.requiredInt("schema_version") != 1) {
        errors += "Compatibility manifest schema must be 1"
    }
    listOf(
        "minecraft_version",
        "protocol_version",
        "official_server_sha1",
        "property_count",
    ).forEach { field ->
        if (official.getValue(field) != compatibility.getValue(field)) {
            errors += "Compatibility metadata '$field' differs from official"
        }
    }

    val officialEntries = official.requiredArray("properties")
        .map { entry ->
            entry.jsonObject.let {
                ServerPropertyIdentity(
                    name = it.requiredString("name"),
                    default = it.requiredString("default"),
                )
            }
        }
    val compatibilityObjects = compatibility.requiredArray("properties")
        .map { it.jsonObject }
    val compatibilityEntries = compatibilityObjects.map {
        ServerPropertyIdentity(
            name = it.requiredString("name"),
            default = it.requiredString("default"),
        )
    }
    validateServerPropertyOrder("Official report", officialEntries, errors)
    validateServerPropertyOrder(
        "Compatibility manifest",
        compatibilityEntries,
        errors,
    )

    val officialByName = officialEntries.associateBy { it.name }
    val compatibilityByName = compatibilityEntries.associateBy { it.name }
    (officialByName.keys - compatibilityByName.keys).sorted().forEach {
        errors += "Compatibility manifest is missing '$it'"
    }
    (compatibilityByName.keys - officialByName.keys).sorted().forEach {
        errors += "Compatibility manifest has unknown property '$it'"
    }
    (officialByName.keys intersect compatibilityByName.keys).sorted()
        .forEach { name ->
            val officialDefault = officialByName.getValue(name).default
            val compatibleDefault = compatibilityByName.getValue(name).default
            if (officialDefault != compatibleDefault) {
                errors +=
                    "Default for '$name' is '$compatibleDefault', expected " +
                            "'$officialDefault'"
            }
        }

    compatibilityObjects.forEach { entry ->
        val name = entry.requiredString("name")
        val scope = entry.requiredString("scope")
        val support = entry.requiredString("library_support")
        if (scope !in SERVER_PROPERTY_SCOPES) {
            errors += "Property '$name' has invalid scope '$scope'"
        }
        if (support !in SERVER_PROPERTY_SUPPORT) {
            errors +=
                "Property '$name' has invalid library_support '$support'"
        }
        listOf("library_mapping", "consumer_responsibility").forEach { field ->
            if (entry.requiredString(field).isBlank()) {
                errors += "Property '$name' has a blank '$field'"
            }
        }
    }
    if (officialEntries.size != official.requiredInt("property_count")) {
        errors += "Official property_count does not match its property array"
    }
    if (
        compatibilityEntries.size !=
        compatibility.requiredInt("property_count")
    ) {
        errors +=
            "Compatibility property_count does not match its property array"
    }
    return errors
}

private fun validateServerPropertyOrder(
    label: String,
    entries: List<ServerPropertyIdentity>,
    errors: MutableList<String>,
) {
    val names = entries.map { it.name }
    if (names != names.sorted()) {
        errors += "$label properties are not sorted by name"
    }
    if (names.size != names.toSet().size) {
        errors += "$label contains duplicate property names"
    }
}

private data class ServerPropertyIdentity(
    val name: String,
    val default: String,
)

private val SERVER_PROPERTY_SCOPES = setOf(
    "protocol",
    "world-storage",
    "gameplay-policy",
    "operations",
)

private val SERVER_PROPERTY_SUPPORT = setOf(
    "direct",
    "extension-point",
    "not-applicable",
)
