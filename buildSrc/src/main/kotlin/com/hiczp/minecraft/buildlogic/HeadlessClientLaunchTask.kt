package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import kotlin.io.path.readText

/** Resolves the official client and Fabric profiles before any fixture process starts. */
@CacheableTask
abstract class GenerateHeadlessClientLaunchTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fabricProfileFile: RegularFileProperty

    @get:Input
    abstract val operatingSystem: Property<String>

    @get:Input
    abstract val architecture: Property<String>

    @get:Input
    abstract val operatingSystemVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val metadata = buildLogicJson.parseToJsonElement(metadataFile.asFile.get().toPath().readText()).jsonObject
        val fabricProfile = buildLogicJson.parseToJsonElement(fabricProfileFile.asFile.get().toPath().readText()).jsonObject
        val launch = headlessClientLaunchManifest(
            metadata, fabricProfile, operatingSystem.get(), architecture.get(), operatingSystemVersion.get(),
        )
        outputFile.asFile.get().toPath().writeJson(launch, sortKeys = true)
    }
}

internal fun headlessClientLaunchManifest(
    metadata: JsonObject,
    fabricProfile: JsonObject,
    operatingSystem: String,
    architecture: String,
    operatingSystemVersion: String,
): JsonObject {
    fun permitted(value: JsonObject): Boolean {
        val rules = value["rules"]?.jsonArray ?: return true
        var allowed = false
        rules.forEach { element ->
            val rule = element.jsonObject
            val os = rule["os"]?.jsonObject
            val matches = (os?.get("name")?.jsonPrimitive?.content?.let { it == operatingSystem } ?: true) &&
                    (os?.get("arch")?.jsonPrimitive?.content?.let { Regex(it).matches(architecture) } ?: true) &&
                    (os?.get("version")?.jsonPrimitive?.content?.let { Regex(it).containsMatchIn(operatingSystemVersion) } ?: true) &&
                    (rule["features"]?.jsonObject?.values?.all { !it.jsonPrimitive.boolean } ?: true)
            if (matches) allowed = rule.getValue("action").jsonPrimitive.content == "allow"
        }
        return allowed
    }

    fun arguments(profile: JsonObject, kind: String): List<JsonElement> =
        profile["arguments"]?.jsonObject?.get(kind)?.jsonArray.orEmpty().flatMap { argument ->
            if (argument is JsonPrimitive) listOf(argument) else {
                val conditional = argument.jsonObject
                if (!permitted(conditional)) emptyList() else when (val value = conditional.getValue("value")) {
                    is JsonArray -> value
                    else -> listOf(value)
                }
            }
        }

    // Fabric's libraries take precedence over Mojang's version of the same artifact.
    val libraries = linkedMapOf<String, String>()
    listOf(fabricProfile, metadata).forEach { profile ->
        profile.getValue("libraries").jsonArray.forEach libraryLoop@{ element ->
            val library = element.jsonObject
            if (!permitted(library)) return@libraryLoop
            val coordinate = library.getValue("name").jsonPrimitive.content.split(':')
            val group = coordinate[0]
            val artifact = coordinate[1]
            val version = coordinate[2]
            val classifier = coordinate.getOrNull(3)
            val identity = "$group:$artifact:${classifier.orEmpty()}"
            val artifactFile = if (classifier == null) "$artifact-$version.jar" else "$artifact-$version-$classifier.jar"
            val path = library["downloads"]?.jsonObject?.get("artifact")?.jsonObject?.get("path")?.jsonPrimitive?.content
                ?: "${group.replace('.', '/')}/$artifact/$version/$artifactFile"
            libraries.putIfAbsent(identity, "libraries/$path")
        }
    }
    val minecraftVersion = metadata.getValue("id").jsonPrimitive.content
    return buildJsonObject {
        put("main_class", fabricProfile.getValue("mainClass"))
        put("asset_index", metadata.getValue("assetIndex").jsonObject.getValue("id"))
        put("version_type", metadata.getValue("type"))
        put("classpath", JsonArray((libraries.values + "versions/$minecraftVersion/$minecraftVersion.jar").map(::JsonPrimitive)))
        put("jvm_arguments", JsonArray(arguments(metadata, "jvm") + arguments(fabricProfile, "jvm")))
        put("game_arguments", JsonArray(arguments(metadata, "game") + arguments(fabricProfile, "game")))
    }
}
