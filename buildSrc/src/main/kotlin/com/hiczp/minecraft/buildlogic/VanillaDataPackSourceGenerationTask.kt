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
        val extracted = extractedDataPacksDirectory.asFile.get().toPath()
        val manifestPath = extracted.resolve(MANIFEST_FILE)
        check(manifestPath.isRegularFile()) { "Official data-pack manifest is missing: $manifestPath" }
        val manifest = protocolJson.decodeFromString<JsonObject>(manifestPath.readText())
        check(manifest.getValue("schema_version").jsonPrimitive.int == EXTRACTION_SCHEMA_VERSION) {
            "Unsupported official data-pack extraction schema"
        }
        val packIds = manifest.getValue("packs").jsonArray.map { it.jsonPrimitive.content }
        val packs = packIds.map { packId -> buildPackPayload(extracted, packId) }
        check(packs.sumOf(PackPayload::fileCount) == manifest.getValue("file_count").jsonPrimitive.int) {
            "Official data-pack file count differs from its extraction manifest"
        }
        val format = manifest.getValue("data_pack_format").jsonArray.map { it.jsonPrimitive.int }
        check(format.size == 2) { "Official data-pack format must have two components" }

        val output = outputDirectory.asFile.get().toPath()
        output.deleteTree()
        val packageDirectory = output.resolve(GENERATED_PACKAGE.replace('.', '/'))
        Files.createDirectories(packageDirectory)
        renderManifestSource(
            minecraftVersion = manifest.getValue("minecraft_version").jsonPrimitive.content,
            dataPackFormat = format,
            packs = packs,
        ).writeSource(packageDirectory.resolve("VanillaDataPackPayload.kt"))
        packs.forEachIndexed { packIndex, pack ->
            pack.batches.forEachIndexed { batchIndex, chunks ->
                renderBatchSource(packIndex, batchIndex, chunks).writeSource(
                    packageDirectory.resolve("VanillaDataPackPayloadBatch${packIndex}_$batchIndex.kt"),
                )
            }
        }
        logger.lifecycle("Generated lazy vanilla data-pack sources: $output")
    }

    private fun buildPackPayload(extracted: Path, packId: String): PackPayload {
        val root = extracted.resolve("packs").resolve(packId)
        check(root.isDirectory()) { "Official data-pack directory is missing: $root" }
        val files = Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() }.sorted().toList()
        }
        check(files.isNotEmpty()) { "Official data pack $packId has no files" }
        val batches = files.chunked(PAYLOAD_BATCH_FILE_COUNT).map { batch ->
            val payload = buildJsonObject {
                put(
                    "files",
                    buildJsonObject {
                        batch.forEach { file ->
                            val relative = root.relativize(file).joinToString("/")
                            put(relative, Base64.getEncoder().encodeToString(Files.readAllBytes(file)))
                        }
                    },
                )
            }
            encodePayload(payload).chunked(SOURCE_CHUNK_SIZE)
        }
        return PackPayload(packId, files.size, batches)
    }

    private fun encodePayload(payload: JsonObject): String {
        val encodedJson = Json.encodeToString(JsonElement.serializer(), payload).encodeToByteArray()
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { gzip -> gzip.write(encodedJson) }
            bytes.toByteArray()
        }
        return Base64.getEncoder().encodeToString(compressed)
    }

    private fun renderManifestSource(
        minecraftVersion: String,
        dataPackFormat: List<Int>,
        packs: List<PackPayload>,
    ): FileSpec {
        val descriptor = ClassName(GENERATED_PACKAGE, "VanillaDataPackPayloadDescriptor")
        val packsInitializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply {
                packs.forEachIndexed { index, pack ->
                    add(
                        "%T(id = %S, index = %L, batchCount = %L),\n",
                        descriptor,
                        pack.id,
                        index,
                        pack.batches.size,
                    )
                }
            }
            .unindent()
            .add(")")
            .build()
        val formatInitializer = CodeBlock.of("%M(%L, %L)", LIST_OF, dataPackFormat[0], dataPackFormat[1])
        val payload = TypeSpec.objectBuilder("VanillaDataPackPayload")
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
                PropertySpec.builder("dataPackFormat", LIST.parameterizedBy(INT))
                    .getter(FunSpec.getterBuilder().addStatement("return %L", formatInitializer).build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("packs", LIST.parameterizedBy(descriptor))
                    .getter(FunSpec.getterBuilder().addStatement("return %L", packsInitializer).build())
                    .build(),
            )
            .addFunction(renderBatchDispatcher(packs))
            .build()
        return FileSpec.builder(GENERATED_PACKAGE, "VanillaDataPackPayload")
            .addType(payload)
            .build()
    }

    private fun renderBatchDispatcher(packs: List<PackPayload>): FunSpec {
        val code = CodeBlock.builder().beginControlFlow("return when (packIndex)")
        packs.forEachIndexed { packIndex, pack ->
            code.beginControlFlow("%L -> when (batchIndex)", packIndex)
            pack.batches.indices.forEach { batchIndex ->
                code.addStatement(
                    "%L -> %M()",
                    batchIndex,
                    MemberName(GENERATED_PACKAGE, batchFunctionName(packIndex, batchIndex)),
                )
            }
            code.addStatement("else -> error(%S)", "Unknown batch index for vanilla data pack ${pack.id}")
            code.endControlFlow()
        }
        code.addStatement("else -> error(%S)", "Unknown vanilla data-pack index")
        code.endControlFlow()
        return FunSpec.builder("loadBatch")
            .addParameter("packIndex", INT)
            .addParameter("batchIndex", INT)
            .returns(LIST.parameterizedBy(STRING))
            .addCode(code.build())
            .build()
    }

    private fun renderBatchSource(
        packIndex: Int,
        batchIndex: Int,
        chunks: List<String>,
    ): FileSpec {
        val initializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply { chunks.forEach { chunk -> add("%S,\n", chunk) } }
            .unindent()
            .add(")")
            .build()
        val functionName = batchFunctionName(packIndex, batchIndex)
        return FileSpec.builder(GENERATED_PACKAGE, "VanillaDataPackPayloadBatch${packIndex}_$batchIndex")
            .addFunction(
                FunSpec.builder(functionName)
                    .addModifiers(INTERNAL)
                    .addKdoc("Loads one compressed official data-pack batch on demand.\n")
                    .returns(LIST.parameterizedBy(STRING))
                    .addStatement("return %L", initializer)
                    .build(),
            )
            .build()
    }

    private fun FileSpec.writeSource(path: Path) {
        path.atomicWriteText(toString())
    }

    private data class PackPayload(
        val id: String,
        val fileCount: Int,
        val batches: List<List<String>>,
    )

    companion object {
        private const val EXTRACTION_SCHEMA_VERSION = 1
        private const val PAYLOAD_SCHEMA_VERSION = 3
        private const val MANIFEST_FILE = "manifest.json"
        private const val GENERATED_PACKAGE = "com.hiczp.minecraft.protocol.datapack.vanilla"
        private const val PAYLOAD_BATCH_FILE_COUNT = 64
        private const val SOURCE_CHUNK_SIZE = 12_000
        private val LIST_OF = MemberName("kotlin.collections", "listOf")

        private fun batchFunctionName(packIndex: Int, batchIndex: Int): String =
            "loadVanillaDataPackPayloadBatch${packIndex}_$batchIndex"
    }
}
