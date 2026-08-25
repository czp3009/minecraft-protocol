package com.hiczp.minecraft.world.format.datapack

import com.hiczp.minecraft.nbt.serialization.SnbtFormat
import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.Compression
import kotlinx.io.Buffer
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

class DataPackFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A caller-defined parser for mod-specific file extensions. Returning null delegates to the next parser. */
fun interface DataPackFileDecoder {
    fun decode(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
        dataPackFileBytes: DataPackFileBytes,
    ): DataPackFileContent?
}

/** Classifies and parses in-memory data-pack files without introducing filesystem policy or resource limits. */
class DataPackFormat(
    val json: Json = Json,
    val compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    val snbtFormat: SnbtFormat = SnbtFormat,
    dataPackFileDecoders: List<DataPackFileDecoder> = emptyList(),
) {
    val dataPackFileDecoders: List<DataPackFileDecoder> = dataPackFileDecoders.toList()

    fun decode(dataPackArchive: DataPackArchive): DataPack = decode(
        dataPackArchive.dataPackId,
        dataPackArchive.dataPackFileBytesByPath.entries.sortedBy { it.key.value }.asSequence().map { entry ->
            entry.key to entry.value
        },
    )

    /**
     * Parses files in iteration order. This overload lets archive providers release their own intermediate batches as
     * parsing advances instead of first materializing a [DataPackArchive].
     */
    fun decode(
        dataPackId: DataPackId,
        dataPackFileByteEntries: Sequence<Pair<DataPackFilePath, DataPackFileBytes>>,
    ): DataPack = createDecoder(dataPackId).also { dataPackDecoder ->
        dataPackFileByteEntries.forEach { (dataPackFilePath, dataPackFileBytes) ->
            dataPackDecoder.accept(dataPackFilePath, dataPackFileBytes)
        }
    }.finish()

    /** Starts an incremental parse for archive providers whose iteration is callback-based. */
    fun createDecoder(dataPackId: DataPackId): DataPackDecoder = DataPackDecoder(dataPackId, this)

    internal fun finish(
        dataPackId: DataPackId,
        dataPackFileContentsByPath: Map<DataPackFilePath, DataPackFileContent>,
    ): DataPack {
        val dataPackMetadata = dataPackFileContentsByPath[DataPackFilePath.PACK_METADATA]?.let { dataPackFileContent ->
            val jsonElement = when (dataPackFileContent) {
                is DataPackFileContent.JsonFile -> dataPackFileContent.jsonElement
                else -> throw DataPackFormatException("Data pack $dataPackId has a non-JSON pack.mcmeta")
            }
            parseFile(dataPackId, DataPackFilePath.PACK_METADATA) {
                parseMetadata(dataPackId, jsonElement)
            }
        }
        return DataPack(dataPackId, dataPackMetadata, dataPackFileContentsByPath)
    }

    /** Decodes one file, including caller-defined extensions, without requiring a complete archive. */
    fun decodeFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
        dataPackFileBytes: DataPackFileBytes,
    ): DataPackFileContent {
        return parseFile(dataPackId, dataPackFilePath) {
            dataPackFileDecoders.forEach { dataPackFileDecoder ->
                dataPackFileDecoder.decode(dataPackId, dataPackFilePath, dataPackFileBytes)?.let { return it }
            }
            when {
                dataPackFilePath == DataPackFilePath.PACK_METADATA || dataPackFilePath.value.endsWith(".json") ->
                    DataPackFileContent.JsonFile(json.parseToJsonElement(decodeText(dataPackFileBytes)))

                dataPackFilePath.value.endsWith(".nbt") -> DataPackFileContent.NbtFile {
                    parseFile(dataPackId, dataPackFilePath) {
                        compressedNbtFormat.decodeDocumentFromSource(
                            Buffer().apply { write(dataPackFileBytes.toByteArray()) },
                            Compression.GZIP,
                        )
                    }
                }

                dataPackFilePath.value.endsWith(".snbt") -> DataPackFileContent.SnbtFile(
                    snbtFormat.decodeTagFromString(decodeText(dataPackFileBytes)),
                )

                TEXT_EXTENSIONS.any(dataPackFilePath.value::endsWith) ->
                    DataPackFileContent.TextFile(decodeText(dataPackFileBytes))

                else -> DataPackFileContent.BinaryFile(dataPackFileBytes)
            }
        }
    }

    private inline fun <T> parseFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
        block: () -> T,
    ): T = try {
        block()
    } catch (failure: DataPackFormatException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw DataPackFormatException("Could not parse $dataPackFilePath in data pack $dataPackId", failure)
    }

    private fun parseMetadata(dataPackId: DataPackId, jsonElement: JsonElement): DataPackMetadata {
        val rawDataPackMetadataJson = jsonElement.jsonObject
        val packMetadataJson = rawDataPackMetadataJson.getValue("pack").jsonObject
        val description = packMetadataJson.getValue("description")
        val supportedDataPackFormatVersionRange = parsePackFormats(dataPackId, packMetadataJson)
        val enabledFeatureFlags = rawDataPackMetadataJson["features"]?.jsonObject?.get("enabled")?.jsonArray
            ?.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
            .orEmpty()
        val dataPackFilterPatterns = rawDataPackMetadataJson["filter"]?.jsonObject?.get("block")?.jsonArray.orEmpty()
            .map { filterPatternElement ->
                val filterPatternJson = filterPatternElement.jsonObject
                DataPackFilterPattern(
                    namespacePattern = filterPatternJson["namespace"]?.jsonPrimitive?.content,
                    pathPattern = filterPatternJson["path"]?.jsonPrimitive?.content,
                )
            }
        val dataPackOverlays = rawDataPackMetadataJson["overlays"]?.jsonObject?.get("entries")?.jsonArray.orEmpty()
            .map { overlayElement ->
                val overlayJson = overlayElement.jsonObject
                DataPackOverlay(
                    supportedDataPackFormatVersionRange = parseFormatRange(
                        dataPackId,
                        overlayJson.getValue("formats"),
                    ),
                    overlayDirectory = DataPackFilePath(overlayJson.getValue("directory").jsonPrimitive.content),
                )
            }
        return DataPackMetadata(
            description = description,
            supportedDataPackFormatVersionRange = supportedDataPackFormatVersionRange,
            enabledFeatureFlags = enabledFeatureFlags,
            dataPackFilterPatterns = dataPackFilterPatterns,
            dataPackOverlays = dataPackOverlays,
            rawDataPackMetadataJson = rawDataPackMetadataJson,
        )
    }

    private fun parsePackFormats(dataPackId: DataPackId, packMetadataJson: JsonObject): DataPackFormatVersionRange {
        val legacyDataPackFormat = packMetadataJson["pack_format"]
        val minimumDataPackFormat = packMetadataJson["min_format"]
        val maximumDataPackFormat = packMetadataJson["max_format"]
        if (minimumDataPackFormat != null || maximumDataPackFormat != null) {
            if (minimumDataPackFormat == null || maximumDataPackFormat == null) {
                throw DataPackFormatException("Data pack $dataPackId must define both min_format and max_format")
            }
            return DataPackFormatVersionRange(
                parseVersion(dataPackId, minimumDataPackFormat, defaultMinor = 0),
                parseVersion(dataPackId, maximumDataPackFormat, defaultMinor = Int.MAX_VALUE),
            )
        }
        packMetadataJson["supported_formats"]?.let { return parseFormatRange(dataPackId, it) }
        if (legacyDataPackFormat != null) {
            val major = parseVersion(dataPackId, legacyDataPackFormat, defaultMinor = 0).major
            return DataPackFormatVersionRange(DataPackFormatVersion(major), DataPackFormatVersion(major, Int.MAX_VALUE))
        }
        throw DataPackFormatException("Data pack $dataPackId pack.mcmeta has no supported data-pack format")
    }

    private fun parseFormatRange(
        dataPackId: DataPackId,
        jsonElement: JsonElement,
    ): DataPackFormatVersionRange = when (jsonElement) {
        is JsonPrimitive -> {
            val major = parseVersion(dataPackId, jsonElement, defaultMinor = 0).major
            DataPackFormatVersionRange(DataPackFormatVersion(major), DataPackFormatVersion(major, Int.MAX_VALUE))
        }

        is JsonArray -> DataPackFormatVersionRange.exact(parseVersion(dataPackId, jsonElement, defaultMinor = 0))

        is JsonObject -> {
            DataPackFormatVersionRange(
                parseVersion(dataPackId, jsonElement.getValue("min_inclusive"), defaultMinor = 0),
                parseVersion(dataPackId, jsonElement.getValue("max_inclusive"), defaultMinor = Int.MAX_VALUE),
            )
        }
    }

    private fun parseVersion(
        dataPackId: DataPackId,
        jsonElement: JsonElement,
        defaultMinor: Int,
    ): DataPackFormatVersion {
        if (jsonElement is JsonPrimitive) {
            return DataPackFormatVersion(jsonElement.int, defaultMinor)
        }
        val versionComponents = jsonElement.jsonArray
        if (versionComponents.size !in 1..2) {
            throw DataPackFormatException("Data pack $dataPackId format version must have one or two components")
        }
        val major = versionComponents[0].jsonPrimitive.int
        val minor = versionComponents.getOrNull(1)?.jsonPrimitive?.int ?: 0
        return DataPackFormatVersion(major, minor)
    }

    private fun decodeText(dataPackFileBytes: DataPackFileBytes): String =
        dataPackFileBytes.toByteArray().decodeToString(throwOnInvalidSequence = true)

    companion object {
        private val TEXT_EXTENSIONS = listOf(".mcfunction", ".txt")
    }
}

/** Incremental per-file decoder. It retains parsed values, but never requires a complete raw archive copy. */
class DataPackDecoder internal constructor(
    val dataPackId: DataPackId,
    val dataPackFormat: DataPackFormat,
) {
    private val dataPackFileContentsByPath = linkedMapOf<DataPackFilePath, DataPackFileContent>()
    private var finished = false

    fun accept(
        dataPackFilePath: DataPackFilePath,
        dataPackFileBytes: DataPackFileBytes,
    ): DataPackFileContent {
        check(!finished) { "Data-pack decoder for $dataPackId is already finished" }
        if (dataPackFilePath in dataPackFileContentsByPath) {
            throw DataPackFormatException("Data pack $dataPackId contains duplicate path $dataPackFilePath")
        }
        return dataPackFormat.decodeFile(dataPackId, dataPackFilePath, dataPackFileBytes).also { dataPackFileContent ->
            dataPackFileContentsByPath[dataPackFilePath] = dataPackFileContent
        }
    }

    fun finish(): DataPack {
        check(!finished) { "Data-pack decoder for $dataPackId is already finished" }
        finished = true
        return dataPackFormat.finish(dataPackId, dataPackFileContentsByPath)
    }
}
