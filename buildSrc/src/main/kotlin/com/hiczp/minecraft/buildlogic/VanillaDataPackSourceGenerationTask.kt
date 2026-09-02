package com.hiczp.minecraft.buildlogic

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.GZIPOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Generates a portable manifest and independently loaded per-pack payloads from extracted official data packs. */
@CacheableTask
abstract class GenerateVanillaDataPackSourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extractedDataPacksDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val extractedDataPacksDirectoryPath = extractedDataPacksDirectory.asFile.get().toPath()
        val manifestPath = extractedDataPacksDirectoryPath.resolve(MANIFEST_FILE)
        check(manifestPath.isRegularFile()) { "Official data-pack manifest is missing: $manifestPath" }
        val dataPackManifest = protocolJson.decodeFromString<JsonObject>(manifestPath.readText())
        check(dataPackManifest.getValue("schema_version").jsonPrimitive.int == EXTRACTION_SCHEMA_VERSION) {
            "Unsupported official data-pack extraction schema"
        }
        val dataPackIds = dataPackManifest.getValue("packs").jsonArray.map { it.jsonPrimitive.content }
        val dataPackPayloads = dataPackIds.map { dataPackId ->
            buildDataPackPayload(extractedDataPacksDirectoryPath, dataPackId)
        }
        val dataPackFormatVersion = dataPackManifest.getValue("data_pack_format").jsonArray.map {
            it.jsonPrimitive.int
        }
        check(dataPackFormatVersion.size == 2) { "Official data-pack format must have two components" }

        val outputDirectoryPath = outputDirectory.asFile.get().toPath()
        outputDirectoryPath.deleteTree()
        val packageDirectory = outputDirectoryPath.resolve(GENERATED_PACKAGE.replace('.', '/'))
        Files.createDirectories(packageDirectory)
        renderManifestSource(dataPackFormatVersion, dataPackPayloads)
            .writeSource(packageDirectory.resolve("VanillaDataPackPayload.kt"))
        dataPackPayloads.forEachIndexed { dataPackIndex, dataPackPayload ->
            renderPayloadSource(dataPackIndex, dataPackPayload.encodedPayloadChunks).writeSource(
                packageDirectory.resolve("VanillaDataPackPayload$dataPackIndex.kt"),
            )
        }
        logger.lifecycle("Generated selectable vanilla data-pack sources: $outputDirectoryPath")
    }

    private fun buildDataPackPayload(
        extractedDataPacksDirectoryPath: Path,
        dataPackId: String,
    ): DataPackPayload {
        val dataPackDirectory = extractedDataPacksDirectoryPath.resolve("packs").resolve(dataPackId)
        check(dataPackDirectory.isDirectory()) {
            "Official data-pack directory is missing: $dataPackDirectory"
        }
        val dataPackFilePaths = Files.walk(dataPackDirectory).use { dataPackPaths ->
            dataPackPaths.filter { it.isRegularFile() }.sorted().toList()
        }
        check(dataPackFilePaths.isNotEmpty()) { "Official data pack $dataPackId has no files" }
        val dataPackPayloadJson = buildJsonObject {
            put(
                "files",
                buildJsonObject {
                    dataPackFilePaths.forEach { dataPackFile ->
                        val dataPackFilePath = dataPackDirectory.relativize(dataPackFile).joinToString("/")
                        put(
                            dataPackFilePath,
                            Base64.getEncoder().encodeToString(Files.readAllBytes(dataPackFile)),
                        )
                    }
                },
            )
        }
        val encodedPayloadChunks = encodeDataPackPayload(dataPackPayloadJson).chunked(SOURCE_CHUNK_SIZE)
        return DataPackPayload(dataPackId, encodedPayloadChunks)
    }

    private fun encodeDataPackPayload(dataPackPayloadJson: JsonObject): String {
        val dataPackPayloadBytes = Json.encodeToString(dataPackPayloadJson).encodeToByteArray()
        val compressedDataPackPayloadBytes = ByteArrayOutputStream().use { byteArrayOutputStream ->
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
                gzipOutputStream.write(dataPackPayloadBytes)
            }
            byteArrayOutputStream.toByteArray()
        }
        return Base64.getEncoder().encodeToString(compressedDataPackPayloadBytes)
    }

    private fun renderManifestSource(
        dataPackFormatVersion: List<Int>,
        dataPackPayloads: List<DataPackPayload>,
    ): FileSpec {
        val dataPackPayloadDescriptor = ClassName(GENERATED_PACKAGE, "VanillaDataPackPayloadDescriptor")
        val dataPackPayloadDescriptorsInitializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply {
                dataPackPayloads.forEachIndexed { dataPackIndex, dataPackPayload ->
                    add(
                        "%T(dataPackId = %S, dataPackIndex = %L),\n",
                        dataPackPayloadDescriptor,
                        dataPackPayload.dataPackId,
                        dataPackIndex,
                    )
                }
            }
            .unindent()
            .add(")")
            .build()
        val dataPackFormatVersionInitializer = CodeBlock.of(
            "%M(%L, %L)",
            LIST_OF,
            dataPackFormatVersion[0],
            dataPackFormatVersion[1],
        )
        val vanillaDataPackPayload = TypeSpec.objectBuilder("VanillaDataPackPayload")
            .addModifiers(INTERNAL)
            .addKdoc("Official data-pack manifest and per-pack payload dispatch; regenerated by Gradle.\n")
            .addProperty(
                PropertySpec.builder("schemaVersion", INT)
                    .initializer("%L", PAYLOAD_SCHEMA_VERSION)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("dataPackFormatVersion", LIST.parameterizedBy(INT))
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return %L", dataPackFormatVersionInitializer)
                            .build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "dataPackPayloadDescriptors",
                    LIST.parameterizedBy(dataPackPayloadDescriptor),
                )
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return %L", dataPackPayloadDescriptorsInitializer)
                            .build(),
                    )
                    .build(),
            )
            .addFunction(renderPayloadDispatcher(dataPackPayloads))
            .build()
        return FileSpec.builder(GENERATED_PACKAGE, "VanillaDataPackPayload")
            .addType(vanillaDataPackPayload)
            .build()
    }

    private fun renderPayloadDispatcher(dataPackPayloads: List<DataPackPayload>): FunSpec {
        val dispatcherCode = CodeBlock.builder().beginControlFlow("return when (dataPackIndex)")
        dataPackPayloads.indices.forEach { dataPackIndex ->
            dispatcherCode.addStatement(
                "%L -> %M()",
                dataPackIndex,
                MemberName(GENERATED_PACKAGE, payloadFunctionName(dataPackIndex)),
            )
        }
        dispatcherCode.addStatement("else -> error(%S)", "Unknown vanilla data-pack index")
        dispatcherCode.endControlFlow()
        return FunSpec.builder("loadDataPackPayload")
            .addParameter("dataPackIndex", INT)
            .returns(LIST.parameterizedBy(STRING))
            .addCode(dispatcherCode.build())
            .build()
    }

    private fun renderPayloadSource(
        dataPackIndex: Int,
        encodedPayloadChunks: List<String>,
    ): FileSpec {
        val encodedPayloadChunksInitializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply { encodedPayloadChunks.forEach { encodedPayloadChunk -> add("%S,\n", encodedPayloadChunk) } }
            .unindent()
            .add(")")
            .build()
        val functionName = payloadFunctionName(dataPackIndex)
        return FileSpec.builder(GENERATED_PACKAGE, "VanillaDataPackPayload$dataPackIndex")
            .addFunction(
                FunSpec.builder(functionName)
                    .addModifiers(INTERNAL)
                    .addKdoc("Loads one complete compressed official data pack on demand.\n")
                    .returns(LIST.parameterizedBy(STRING))
                    .addStatement("return %L", encodedPayloadChunksInitializer)
                    .build(),
            )
            .build()
    }

    private fun FileSpec.writeSource(path: Path) {
        path.atomicWriteText(toString())
    }

    private data class DataPackPayload(
        val dataPackId: String,
        val encodedPayloadChunks: List<String>,
    )

    companion object {
        private const val EXTRACTION_SCHEMA_VERSION = 1
        private const val PAYLOAD_SCHEMA_VERSION = 4
        private const val MANIFEST_FILE = "manifest.json"
        private const val GENERATED_PACKAGE = "com.hiczp.minecraft.protocol.datapack.vanilla"
        private const val SOURCE_CHUNK_SIZE = 12_000
        private val LIST_OF = MemberName("kotlin.collections", "listOf")

        private fun payloadFunctionName(dataPackIndex: Int): String =
            "loadVanillaDataPackPayload$dataPackIndex"
    }
}
