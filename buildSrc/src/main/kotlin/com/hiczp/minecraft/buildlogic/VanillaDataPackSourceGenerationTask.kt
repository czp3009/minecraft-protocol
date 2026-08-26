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

/** Generates a portable manifest and independently loaded source batches from extracted official data packs. */
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
        check(
            dataPackPayloads.sumOf(DataPackPayload::dataPackFileCount) ==
                    dataPackManifest.getValue("file_count").jsonPrimitive.int,
        ) {
            "Official data-pack file count differs from its extraction manifest"
        }
        val dataPackFormatVersion = dataPackManifest.getValue("data_pack_format").jsonArray.map {
            it.jsonPrimitive.int
        }
        check(dataPackFormatVersion.size == 2) { "Official data-pack format must have two components" }

        val outputDirectoryPath = outputDirectory.asFile.get().toPath()
        outputDirectoryPath.deleteTree()
        val packageDirectory = outputDirectoryPath.resolve(GENERATED_PACKAGE.replace('.', '/'))
        Files.createDirectories(packageDirectory)
        renderManifestSource(
            minecraftVersion = dataPackManifest.getValue("minecraft_version").jsonPrimitive.content,
            dataPackFormatVersion = dataPackFormatVersion,
            dataPackPayloads = dataPackPayloads,
        ).writeSource(packageDirectory.resolve("VanillaDataPackPayload.kt"))
        dataPackPayloads.forEachIndexed { dataPackIndex, dataPackPayload ->
            dataPackPayload.encodedBatches.forEachIndexed { batchIndex, encodedBatchChunks ->
                renderBatchSource(dataPackIndex, batchIndex, encodedBatchChunks).writeSource(
                    packageDirectory.resolve("VanillaDataPackPayloadBatch${dataPackIndex}_$batchIndex.kt"),
                )
            }
        }
        logger.lifecycle("Generated lazy vanilla data-pack sources: $outputDirectoryPath")
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
        val encodedBatches = dataPackFilePaths.chunked(PAYLOAD_BATCH_FILE_COUNT).map { dataPackFileBatch ->
            val dataPackPayloadJson = buildJsonObject {
                put(
                    "files",
                    buildJsonObject {
                        dataPackFileBatch.forEach { dataPackFile ->
                            val dataPackFilePath = dataPackDirectory.relativize(dataPackFile).joinToString("/")
                            put(
                                dataPackFilePath,
                                Base64.getEncoder().encodeToString(Files.readAllBytes(dataPackFile)),
                            )
                        }
                    },
                )
            }
            encodeDataPackPayload(dataPackPayloadJson).chunked(SOURCE_CHUNK_SIZE)
        }
        return DataPackPayload(dataPackId, dataPackFilePaths.size, encodedBatches)
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
        minecraftVersion: String,
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
                        "%T(dataPackId = %S, dataPackIndex = %L, batchCount = %L),\n",
                        dataPackPayloadDescriptor,
                        dataPackPayload.dataPackId,
                        dataPackIndex,
                        dataPackPayload.encodedBatches.size,
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
            .addKdoc("Official data-pack manifest and lazy batch dispatch; regenerated by Gradle.\n")
            .addProperty(
                PropertySpec.builder("schemaVersion", INT)
                    .initializer("%L", PAYLOAD_SCHEMA_VERSION)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("minecraftVersion", STRING)
                    .initializer("%S", minecraftVersion)
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
            .addFunction(renderBatchDispatcher(dataPackPayloads))
            .build()
        return FileSpec.builder(GENERATED_PACKAGE, "VanillaDataPackPayload")
            .addType(vanillaDataPackPayload)
            .build()
    }

    private fun renderBatchDispatcher(dataPackPayloads: List<DataPackPayload>): FunSpec {
        val dispatcherCode = CodeBlock.builder().beginControlFlow("return when (dataPackIndex)")
        dataPackPayloads.forEachIndexed { dataPackIndex, dataPackPayload ->
            dispatcherCode.beginControlFlow("%L -> when (batchIndex)", dataPackIndex)
            dataPackPayload.encodedBatches.indices.forEach { batchIndex ->
                dispatcherCode.addStatement(
                    "%L -> %M()",
                    batchIndex,
                    MemberName(GENERATED_PACKAGE, batchFunctionName(dataPackIndex, batchIndex)),
                )
            }
            dispatcherCode.addStatement(
                "else -> error(%S)",
                "Unknown batch index for vanilla data pack ${dataPackPayload.dataPackId}",
            )
            dispatcherCode.endControlFlow()
        }
        dispatcherCode.addStatement("else -> error(%S)", "Unknown vanilla data-pack index")
        dispatcherCode.endControlFlow()
        return FunSpec.builder("loadDataPackBatch")
            .addParameter("dataPackIndex", INT)
            .addParameter("batchIndex", INT)
            .returns(LIST.parameterizedBy(STRING))
            .addCode(dispatcherCode.build())
            .build()
    }

    private fun renderBatchSource(
        dataPackIndex: Int,
        batchIndex: Int,
        encodedBatchChunks: List<String>,
    ): FileSpec {
        val encodedBatchChunksInitializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply { encodedBatchChunks.forEach { encodedBatchChunk -> add("%S,\n", encodedBatchChunk) } }
            .unindent()
            .add(")")
            .build()
        val functionName = batchFunctionName(dataPackIndex, batchIndex)
        return FileSpec.builder(GENERATED_PACKAGE, "VanillaDataPackPayloadBatch${dataPackIndex}_$batchIndex")
            .addFunction(
                FunSpec.builder(functionName)
                    .addModifiers(INTERNAL)
                    .addKdoc("Loads one compressed official data-pack batch on demand.\n")
                    .returns(LIST.parameterizedBy(STRING))
                    .addStatement("return %L", encodedBatchChunksInitializer)
                    .build(),
            )
            .build()
    }

    private fun FileSpec.writeSource(path: Path) {
        path.atomicWriteText(toString())
    }

    private data class DataPackPayload(
        val dataPackId: String,
        val dataPackFileCount: Int,
        val encodedBatches: List<List<String>>,
    )

    companion object {
        private const val EXTRACTION_SCHEMA_VERSION = 1
        private const val PAYLOAD_SCHEMA_VERSION = 3
        private const val MANIFEST_FILE = "manifest.json"
        private const val GENERATED_PACKAGE = "com.hiczp.minecraft.protocol.datapack.vanilla"
        private const val PAYLOAD_BATCH_FILE_COUNT = 64
        private const val SOURCE_CHUNK_SIZE = 12_000
        private val LIST_OF = MemberName("kotlin.collections", "listOf")

        private fun batchFunctionName(dataPackIndex: Int, batchIndex: Int): String =
            "loadVanillaDataPackPayloadBatch${dataPackIndex}_$batchIndex"
    }
}
