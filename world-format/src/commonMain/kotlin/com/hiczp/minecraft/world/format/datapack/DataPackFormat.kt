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
        packId: DataPackId,
        path: DataPackPath,
        bytes: DataPackBinary,
    ): DataPackFileContent?
}

/** Parses complete in-memory data-pack files without introducing filesystem policy or resource limits. */
class DataPackFormat(
    val json: Json = Json,
    val compressedNbt: CompressedNbtFormat = CompressedNbtFormat(),
    val snbt: SnbtFormat = SnbtFormat,
    customDecoders: List<DataPackFileDecoder> = emptyList(),
) {
    val customDecoders: List<DataPackFileDecoder> = customDecoders.toList()

    fun decode(archive: DataPackArchive): DataPack = decode(
        archive.id,
        archive.files.entries.sortedBy { it.key.value }.asSequence().map { (path, bytes) -> path to bytes },
    )

    /**
     * Parses files in iteration order. This overload lets archive providers release their own intermediate batches as
     * parsing advances instead of first materializing a [DataPackArchive].
     */
    fun decode(
        id: DataPackId,
        files: Sequence<Pair<DataPackPath, DataPackBinary>>,
    ): DataPack = decoder(id).also { decoder ->
        files.forEach { (path, bytes) -> decoder.accept(path, bytes) }
    }.finish()

    /** Starts an incremental parse for archive providers whose iteration is callback-based. */
    fun decoder(id: DataPackId): DataPackDecoder = DataPackDecoder(id, this)

    internal fun finish(
        id: DataPackId,
        parsed: Map<DataPackPath, DataPackFileContent>,
    ): DataPack {
        val metadata = parsed[DataPackPath.PACK_METADATA]?.let { content ->
            val element = when (content) {
                is DataPackFileContent.JsonFile -> content.element
                else -> throw DataPackFormatException("Data pack $id has a non-JSON pack.mcmeta")
            }
            parseFile(id, DataPackPath.PACK_METADATA) { parseMetadata(id, element) }
        }
        return DataPack(id, metadata, parsed)
    }

    /** Decodes one file, including caller-defined extensions, without requiring a complete archive. */
    fun decodeFile(
        packId: DataPackId,
        path: DataPackPath,
        bytes: DataPackBinary,
    ): DataPackFileContent {
        return parseFile(packId, path) {
            customDecoders.forEach { decoder ->
                decoder.decode(packId, path, bytes)?.let { return it }
            }
            when {
                path == DataPackPath.PACK_METADATA || path.value.endsWith(".json") ->
                    DataPackFileContent.JsonFile(json.parseToJsonElement(decodeText(bytes)))

                path.value.endsWith(".nbt") -> DataPackFileContent.NbtFile {
                    parseFile(packId, path) {
                        val source = Buffer().apply { write(bytes.toByteArray()) }
                        compressedNbt.decodeDocumentFromSource(source, Compression.GZIP)
                    }
                }

                path.value.endsWith(".snbt") -> DataPackFileContent.SnbtFile(
                    snbt.decodeTagFromString(decodeText(bytes)),
                )

                TEXT_EXTENSIONS.any(path.value::endsWith) -> DataPackFileContent.TextFile(decodeText(bytes))
                else -> DataPackFileContent.BinaryFile(bytes)
            }
        }
    }

    private inline fun <T> parseFile(
        packId: DataPackId,
        path: DataPackPath,
        block: () -> T,
    ): T = try {
        block()
    } catch (failure: DataPackFormatException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw DataPackFormatException("Could not parse $path in data pack $packId", failure)
    }

    private fun parseMetadata(packId: DataPackId, element: JsonElement): DataPackMetadata {
        val root = element.jsonObject
        val pack = root.getValue("pack").jsonObject
        val description = pack.getValue("description")
        val formats = parsePackFormats(packId, pack)
        val enabledFeatures = root["features"]?.jsonObject?.get("enabled")?.jsonArray
            ?.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
            .orEmpty()
        val filters = root["filter"]?.jsonObject?.get("block")?.jsonArray.orEmpty().map { value ->
            val pattern = value.jsonObject
            DataPackFilterPattern(
                namespacePattern = pattern["namespace"]?.jsonPrimitive?.content,
                pathPattern = pattern["path"]?.jsonPrimitive?.content,
            )
        }
        val overlays = root["overlays"]?.jsonObject?.get("entries")?.jsonArray.orEmpty().map { value ->
            val overlay = value.jsonObject
            DataPackOverlay(
                formats = parseFormatRange(
                    packId,
                    overlay.getValue("formats"),
                ),
                directory = DataPackPath(overlay.getValue("directory").jsonPrimitive.content),
            )
        }
        return DataPackMetadata(
            description = description,
            formats = formats,
            enabledFeatures = enabledFeatures,
            filters = filters,
            overlays = overlays,
            raw = root,
        )
    }

    private fun parsePackFormats(packId: DataPackId, pack: JsonObject): DataPackFormatRange {
        val legacy = pack["pack_format"]
        val minimum = pack["min_format"]
        val maximum = pack["max_format"]
        if (minimum != null || maximum != null) {
            if (minimum == null || maximum == null) {
                throw DataPackFormatException("Data pack $packId must define both min_format and max_format")
            }
            return DataPackFormatRange(
                parseVersion(packId, minimum, defaultMinor = 0),
                parseVersion(packId, maximum, defaultMinor = Int.MAX_VALUE),
            )
        }
        pack["supported_formats"]?.let { return parseFormatRange(packId, it) }
        if (legacy != null) {
            val major = parseVersion(packId, legacy, defaultMinor = 0).major
            return DataPackFormatRange(DataPackFormatVersion(major), DataPackFormatVersion(major, Int.MAX_VALUE))
        }
        throw DataPackFormatException("Data pack $packId pack.mcmeta has no supported data-pack format")
    }

    private fun parseFormatRange(packId: DataPackId, element: JsonElement): DataPackFormatRange = when (element) {
        is JsonPrimitive -> {
            val major = parseVersion(packId, element, defaultMinor = 0).major
            DataPackFormatRange(DataPackFormatVersion(major), DataPackFormatVersion(major, Int.MAX_VALUE))
        }

        is JsonArray -> DataPackFormatRange.exact(parseVersion(packId, element, defaultMinor = 0))

        is JsonObject -> {
            DataPackFormatRange(
                parseVersion(packId, element.getValue("min_inclusive"), defaultMinor = 0),
                parseVersion(packId, element.getValue("max_inclusive"), defaultMinor = Int.MAX_VALUE),
            )
        }
    }

    private fun parseVersion(
        packId: DataPackId,
        element: JsonElement,
        defaultMinor: Int,
    ): DataPackFormatVersion {
        if (element is JsonPrimitive) {
            return DataPackFormatVersion(element.int, defaultMinor)
        }
        val parts = element.jsonArray
        if (parts.size !in 1..2) {
            throw DataPackFormatException("Data pack $packId format version must have one or two components")
        }
        val major = parts[0].jsonPrimitive.int
        val minor = parts.getOrNull(1)?.jsonPrimitive?.int ?: 0
        return DataPackFormatVersion(major, minor)
    }

    private fun decodeText(bytes: DataPackBinary): String =
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)

    companion object {
        private val TEXT_EXTENSIONS = listOf(".mcfunction", ".txt")
    }
}

/** Incremental per-file decoder. It retains parsed values, but never requires a complete raw archive copy. */
class DataPackDecoder internal constructor(
    val id: DataPackId,
    val format: DataPackFormat,
) {
    private val parsed = linkedMapOf<DataPackPath, DataPackFileContent>()
    private var finished = false

    fun accept(
        path: DataPackPath,
        bytes: DataPackBinary,
    ): DataPackFileContent {
        check(!finished) { "Data-pack decoder for $id is already finished" }
        if (path in parsed) throw DataPackFormatException("Data pack $id contains duplicate path $path")
        return format.decodeFile(id, path, bytes).also { content -> parsed[path] = content }
    }

    fun finish(): DataPack {
        check(!finished) { "Data-pack decoder for $id is already finished" }
        finished = true
        return format.finish(id, parsed)
    }
}
