package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.*
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
        val implementationJarPath = implementationJar.asFile.get().toPath()
        check(implementationJarPath.isRegularFile()) {
            "Official server implementation JAR is missing: $implementationJarPath"
        }
        val minecraftVersion = minecraftVersion.get()
        val outputDirectoryPath = outputDirectory.asFile.get().toPath()
        outputDirectoryPath.deleteTree()
        val dataPackIds = linkedSetOf(CORE_DATA_PACK_ID)
        var dataPackFileCount = 0
        ZipFile(implementationJarPath.toFile()).use { implementationArchive ->
            val versionArchiveEntry = implementationArchive.getEntry(VERSION_FILE)
                ?: error("Official server implementation has no $VERSION_FILE")
            val versionJson = protocolJson.decodeFromString<JsonObject>(
                implementationArchive.getInputStream(versionArchiveEntry).use { it.readBytes() }.decodeToString(),
            )
            check(versionJson.getValue("id").jsonPrimitive.content == minecraftVersion) {
                "Official implementation JAR targets a different Minecraft release"
            }
            val dataPackFormatJson = versionJson.getValue("pack_version").jsonObject
            val dataPackFormatMajor = dataPackFormatJson.getValue("data_major").jsonPrimitive.int
            val dataPackFormatMinor = dataPackFormatJson.getValue("data_minor").jsonPrimitive.int
            implementationArchive.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.startsWith(DATA_PREFIX) }
                .sortedBy { it.name }
                .forEach { archiveEntry ->
                    validateJarEntryPath(archiveEntry.name)
                    val builtInDataPackFile = parseBuiltInDataPackFile(archiveEntry.name)
                    val dataPackId = builtInDataPackFile?.dataPackId ?: CORE_DATA_PACK_ID
                    val dataPackFilePath = builtInDataPackFile?.dataPackFilePath ?: archiveEntry.name
                    dataPackIds += dataPackId
                    val dataPackFileBytes = implementationArchive.getInputStream(archiveEntry).use { it.readBytes() }
                    outputDirectoryPath.resolve("packs").resolve(dataPackId).resolve(dataPackFilePath)
                        .atomicWrite(dataPackFileBytes)
                    dataPackFileCount++
                }
            check(dataPackFileCount > 0) { "Official implementation JAR contains no data-pack files" }
            val dataPackManifest = buildJsonObject {
                put("schema_version", EXTRACTION_SCHEMA_VERSION)
                put("minecraft_version", minecraftVersion)
                put(
                    "data_pack_format",
                    buildJsonArray {
                        add(dataPackFormatMajor)
                        add(dataPackFormatMinor)
                    },
                )
                put(
                    "packs",
                    buildJsonArray {
                        dataPackIds.forEach { dataPackId -> add(dataPackId) }
                    },
                )
                put("file_count", dataPackFileCount)
            }
            outputDirectoryPath.resolve(MANIFEST_FILE).writeJson(dataPackManifest, sortKeys = true)
        }
        logger.lifecycle("Extracted $dataPackFileCount official data-pack files: $outputDirectoryPath")
    }

    private fun parseBuiltInDataPackFile(jarEntryPath: String): BuiltInDataPackFile? {
        if (!jarEntryPath.startsWith(BUILT_IN_PREFIX)) return null
        val relativeBuiltInDataPackPath = jarEntryPath.removePrefix(BUILT_IN_PREFIX)
        val dataPackId = relativeBuiltInDataPackPath.substringBefore('/')
        val dataPackFilePath = relativeBuiltInDataPackPath.substringAfter('/', missingDelimiterValue = "")
        check(dataPackId.matches(PACK_ID_PATTERN) && dataPackFilePath.isNotEmpty()) {
            "Invalid built-in data-pack path in official JAR: $jarEntryPath"
        }
        return BuiltInDataPackFile(dataPackId, dataPackFilePath)
    }

    private fun validateJarEntryPath(jarEntryPath: String) {
        check(
            '\\' !in jarEntryPath && !jarEntryPath.startsWith('/') &&
                    jarEntryPath.split('/').none { it == "." || it == ".." },
        ) {
            "Unsafe data-pack path in official JAR: $jarEntryPath"
        }
    }

    companion object {
        private const val EXTRACTION_SCHEMA_VERSION = 1
        private const val CORE_DATA_PACK_ID = "vanilla"
        private const val VERSION_FILE = "version.json"
        private const val MANIFEST_FILE = "manifest.json"
        private const val DATA_PREFIX = "data/"
        private const val BUILT_IN_PREFIX = "data/minecraft/datapacks/"
        private val PACK_ID_PATTERN = Regex("[a-z0-9._-]+")
    }
}

private data class BuiltInDataPackFile(
    val dataPackId: String,
    val dataPackFilePath: String,
)
