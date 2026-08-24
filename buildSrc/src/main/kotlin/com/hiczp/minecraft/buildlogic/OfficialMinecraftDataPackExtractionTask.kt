package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile

/** Extracts the selected release's core and built-in data packs from the official implementation JAR. */
@CacheableTask
abstract class ExtractOfficialMinecraftDataPacksTask : MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val implementationJar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val jar = implementationJar.asFile.get().toPath()
        check(jar.isRegularFile()) { "Official server implementation JAR is missing: $jar" }
        val version = minecraftVersion.get()
        val output = outputDirectory.asFile.get().toPath()
        output.deleteTree()
        val packIds = linkedSetOf(CORE_PACK_ID)
        var fileCount = 0
        ZipFile(jar.toFile()).use { archive ->
            val versionEntry = archive.getEntry(VERSION_FILE)
                ?: error("Official server implementation has no $VERSION_FILE")
            val versionJson = archive.getInputStream(versionEntry).use { it.readBytes() }
                .decodeJsonObject("$jar!/$VERSION_FILE")
            check(versionJson.requiredString("id") == version) {
                "Official implementation JAR targets a different Minecraft release"
            }
            val dataFormat = versionJson.requiredObject("pack_version")
            val dataMajor = dataFormat.requiredInt("data_major")
            val dataMinor = dataFormat.requiredInt("data_minor")
            archive.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.startsWith(DATA_PREFIX) }
                .sortedBy { it.name }
                .forEach { entry ->
                    validateJarPath(entry.name)
                    val builtIn = parseBuiltInPath(entry.name)
                    val packId = builtIn?.first ?: CORE_PACK_ID
                    val relative = builtIn?.second ?: entry.name
                    packIds += packId
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    output.resolve("packs").resolve(packId).resolve(relative).atomicWrite(bytes)
                    fileCount++
                }
            check(fileCount > 0) { "Official implementation JAR contains no data-pack files" }
            val manifest = buildJsonObject {
                put("schema_version", EXTRACTION_SCHEMA_VERSION)
                put("minecraft_version", version)
                put(
                    "data_pack_format",
                    buildJsonArray {
                        add(dataMajor)
                        add(dataMinor)
                    },
                )
                put(
                    "packs",
                    buildJsonArray {
                        packIds.forEach { packId -> add(packId) }
                    },
                )
                put("file_count", fileCount)
            }
            output.resolve(MANIFEST_FILE).writeJson(manifest, sortKeys = true)
        }
        logger.lifecycle("Extracted $fileCount official data-pack files: $output")
    }

    private fun parseBuiltInPath(path: String): Pair<String, String>? {
        if (!path.startsWith(BUILT_IN_PREFIX)) return null
        val remainder = path.removePrefix(BUILT_IN_PREFIX)
        val packId = remainder.substringBefore('/')
        val relative = remainder.substringAfter('/', missingDelimiterValue = "")
        check(packId.matches(PACK_ID_PATTERN) && relative.isNotEmpty()) {
            "Invalid built-in data-pack path in official JAR: $path"
        }
        return packId to relative
    }

    private fun validateJarPath(path: String) {
        check('\\' !in path && !path.startsWith('/') && path.split('/').none { it == "." || it == ".." }) {
            "Unsafe data-pack path in official JAR: $path"
        }
    }

    companion object {
        private const val EXTRACTION_SCHEMA_VERSION = 1
        private const val CORE_PACK_ID = "vanilla"
        private const val VERSION_FILE = "version.json"
        private const val MANIFEST_FILE = "manifest.json"
        private const val DATA_PREFIX = "data/"
        private const val BUILT_IN_PREFIX = "data/minecraft/datapacks/"
        private val PACK_ID_PATTERN = Regex("[a-z0-9._-]+")
    }
}
