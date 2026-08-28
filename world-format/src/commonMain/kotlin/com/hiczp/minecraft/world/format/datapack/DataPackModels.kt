package com.hiczp.minecraft.world.format.datapack

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtTag
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline

/** Stable application identity for one data pack. It is independent of its filesystem name. */
@JvmInline
value class DataPackId(val value: String) {
    init {
        require(value.isNotBlank()) { "A data-pack identifier cannot be blank" }
        require(value.none(Char::isISOControl)) { "A data-pack identifier cannot contain control characters" }
    }

    override fun toString(): String = value
}

/** A normalized relative path inside one data-pack root. */
@JvmInline
value class DataPackFilePath(val value: String) {
    init {
        val segments = value.split('/')
        require(value.isNotEmpty() && !value.startsWith('/') && '\\' !in value) {
            "A data-pack path must be a relative slash-separated path: $value"
        }
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "A data-pack path must be normalized: $value"
        }
    }

    val segments: List<String>
        get() = value.split('/')

    val fileName: String
        get() = value.substringAfterLast('/')

    override fun toString(): String = value

    companion object {
        val PACK_METADATA: DataPackFilePath = DataPackFilePath("pack.mcmeta")
    }
}

/** Immutable bytes retained for unknown or mod-defined data-pack files. */
class DataPackFileBytes(bytes: ByteArray) {
    private val copiedBytes = bytes.copyOf()

    val sizeInBytes: Int
        get() = copiedBytes.size

    fun toByteArray(): ByteArray = copiedBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is DataPackFileBytes && copiedBytes.contentEquals(other.copiedBytes)

    override fun hashCode(): Int = copiedBytes.contentHashCode()

    override fun toString(): String = "DataPackFileBytes(sizeInBytes=$sizeInBytes)"
}

/** Complete raw in-memory contents of one data pack, before file-type parsing. */
class DataPackArchive(
    val dataPackId: DataPackId,
    dataPackFileBytesByPath: Map<DataPackFilePath, DataPackFileBytes>,
) {
    val dataPackFileBytesByPath: Map<DataPackFilePath, DataPackFileBytes> = dataPackFileBytesByPath.toMap()

    init {
        require(this.dataPackFileBytesByPath.isNotEmpty()) { "Data pack $dataPackId has no files" }
    }

    constructor(dataPackId: DataPackId, dataPackFileBytes: Iterable<Pair<DataPackFilePath, ByteArray>>) : this(
        dataPackId,
        dataPackFileBytes.toDataPackFileBytesMap(dataPackId),
    )
}

private fun Iterable<Pair<DataPackFilePath, ByteArray>>.toDataPackFileBytesMap(
    dataPackId: DataPackId,
): Map<DataPackFilePath, DataPackFileBytes> = buildMap {
    this@toDataPackFileBytesMap.forEach { (dataPackFilePath, byteArray) ->
        require(dataPackFilePath !in this) {
            "Data pack $dataPackId contains duplicate path $dataPackFilePath"
        }
        put(dataPackFilePath, DataPackFileBytes(byteArray))
    }
}

/** Typed, filesystem-independent content of one data-pack file. Mods may implement their own strong variants. */
interface DataPackFileContent {
    data class JsonFile(val jsonElement: JsonElement) : DataPackFileContent {
        fun <T> decode(
            deserializationStrategy: DeserializationStrategy<T>,
            json: Json = Json,
        ): T = json.decodeFromJsonElement(deserializationStrategy, jsonElement)

        inline fun <reified T> decode(json: Json = Json): T =
            decode(json.serializersModule.serializer(), json)
    }

    /**
     * A compressed NBT file whose document is decoded from retained in-memory bytes on first access. Decoding never
     * returns to the filesystem or acquires a data-pack read lock.
     */
    class NbtFile : DataPackFileContent {
        private val nbtDocumentDelegate: Lazy<NbtDocument>

        constructor(nbtDocument: NbtDocument) {
            nbtDocumentDelegate = lazyOf(nbtDocument)
        }

        internal constructor(decodeNbtDocument: () -> NbtDocument) {
            nbtDocumentDelegate = lazy(LazyThreadSafetyMode.PUBLICATION, decodeNbtDocument)
        }

        val nbtDocument: NbtDocument
            get() = nbtDocumentDelegate.value

        override fun equals(other: Any?): Boolean = other is NbtFile && nbtDocument == other.nbtDocument

        override fun hashCode(): Int = nbtDocument.hashCode()

        override fun toString(): String = if (nbtDocumentDelegate.isInitialized()) {
            "NbtFile(nbtDocument=${nbtDocumentDelegate.value})"
        } else {
            "NbtFile(deferred)"
        }
    }

    data class SnbtFile(val nbtTag: NbtTag) : DataPackFileContent

    data class TextFile(val text: String) : DataPackFileContent

    class BinaryFile(val dataPackFileBytes: DataPackFileBytes) : DataPackFileContent {
        override fun equals(other: Any?): Boolean =
            other is BinaryFile && dataPackFileBytes == other.dataPackFileBytes

        override fun hashCode(): Int = dataPackFileBytes.hashCode()

        override fun toString(): String = "BinaryFile(dataPackFileBytes=$dataPackFileBytes)"
    }
}

/** One effective file paired with its original path while resolving overlays and stack precedence. */
internal data class EffectiveDataPackFile(
    val dataPackFilePath: DataPackFilePath,
    val dataPackFileContent: DataPackFileContent,
)

/** Minecraft's major/minor data-pack format version. */
data class DataPackFormatVersion(
    val major: Int,
    val minor: Int = 0,
) : Comparable<DataPackFormatVersion> {
    init {
        require(major >= 0 && minor >= 0) { "Data-pack format components must be non-negative" }
    }

    override fun compareTo(other: DataPackFormatVersion): Int =
        compareValuesBy(this, other, DataPackFormatVersion::major, DataPackFormatVersion::minor)

    override fun toString(): String = when (minor) {
        0 -> "$major"
        Int.MAX_VALUE -> "$major.*"
        else -> "$major.$minor"
    }
}

data class DataPackFormatVersionRange(
    val minimum: DataPackFormatVersion,
    val maximum: DataPackFormatVersion,
) {
    init {
        require(minimum <= maximum) { "Data-pack format range $minimum..$maximum is reversed" }
    }

    operator fun contains(dataPackFormatVersion: DataPackFormatVersion): Boolean =
        dataPackFormatVersion in minimum..maximum

    companion object {
        fun exact(dataPackFormatVersion: DataPackFormatVersion): DataPackFormatVersionRange =
            DataPackFormatVersionRange(dataPackFormatVersion, dataPackFormatVersion)
    }
}

/** One `filter.block` rule applied to resources supplied by lower-priority packs. */
data class DataPackFilterPattern(
    val namespacePattern: String? = null,
    val pathPattern: String? = null,
) {
    private val namespaceRegex = namespacePattern?.let(::Regex)
    private val pathRegex = pathPattern?.let(::Regex)

    init {
        require(namespacePattern != null || pathPattern != null) {
            "A data-pack filter must constrain namespace or path"
        }
    }

    fun matches(dataPackResourcePath: DataPackResourcePath): Boolean =
        namespaceRegex?.matches(dataPackResourcePath.namespace) != false &&
                pathRegex?.matches(dataPackResourcePath.path) != false
}

data class DataPackOverlay(
    val supportedDataPackFormatVersionRange: DataPackFormatVersionRange,
    val overlayDirectory: DataPackFilePath,
)

/** Strong views of selected-release `pack.mcmeta` fields plus the lossless source object. */
data class DataPackMetadata(
    val description: JsonElement,
    val supportedDataPackFormatVersionRange: DataPackFormatVersionRange,
    val enabledFeatureFlags: Set<String> = emptySet(),
    val dataPackFilterPatterns: List<DataPackFilterPattern> = emptyList(),
    val dataPackOverlays: List<DataPackOverlay> = emptyList(),
    val rawDataPackMetadataJson: JsonObject = JsonObject(emptyMap()),
)

/** A namespaced path below `data/<namespace>/`. */
data class DataPackResourcePath(
    val namespace: String,
    val path: String,
) {
    init {
        require(namespace.matches(NAMESPACE_PATTERN)) { "Invalid data-pack resource namespace: $namespace" }
        require(path.matches(RESOURCE_PATH_PATTERN)) { "Invalid data-pack resource path: $path" }
    }

    override fun toString(): String = "$namespace:$path"
}

/** A logical data-pack resource type such as `worldgen/biome` or `tags/block`. */
data class DataPackResourceType(
    val directory: String,
    val extension: String = "json",
) {
    init {
        require(directory.matches(RESOURCE_PATH_PATTERN)) { "Invalid data-pack resource directory: $directory" }
        require(extension.matches(EXTENSION_PATTERN)) { "Invalid data-pack resource extension: $extension" }
    }

    fun path(dataPackResourceId: DataPackResourceId): DataPackResourcePath =
        DataPackResourcePath(
            dataPackResourceId.namespace,
            "$directory/${dataPackResourceId.path}.$extension",
        )

    fun id(dataPackResourcePath: DataPackResourcePath): DataPackResourceId? {
        val prefix = "$directory/"
        val suffix = ".$extension"
        if (!dataPackResourcePath.path.startsWith(prefix) || !dataPackResourcePath.path.endsWith(suffix)) return null
        val identifierPath = dataPackResourcePath.path.removePrefix(prefix).removeSuffix(suffix)
        if (identifierPath.isEmpty()) return null
        return DataPackResourceId(dataPackResourcePath.namespace, identifierPath)
    }
}

/** A resource location after its type directory and extension have been removed. */
data class DataPackResourceId(
    val namespace: String,
    val path: String,
) {
    init {
        require(namespace.matches(NAMESPACE_PATTERN)) { "Invalid data-pack resource namespace: $namespace" }
        require(path.matches(RESOURCE_PATH_PATTERN)) { "Invalid data-pack resource identifier path: $path" }
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        operator fun invoke(value: String): DataPackResourceId {
            val separator = value.indexOf(':')
            return if (separator < 0) {
                DataPackResourceId("minecraft", value)
            } else {
                DataPackResourceId(value.substring(0, separator), value.substring(separator + 1))
            }
        }
    }
}

/** Typed contents of one data pack. Every original file remains addressable in [dataPackFileContentsByPath]. */
class DataPack(
    val dataPackId: DataPackId,
    val dataPackMetadata: DataPackMetadata?,
    dataPackFileContentsByPath: Map<DataPackFilePath, DataPackFileContent>,
) {
    val dataPackFileContentsByPath: Map<DataPackFilePath, DataPackFileContent> = dataPackFileContentsByPath.toMap()

    init {
        require(this.dataPackFileContentsByPath.isNotEmpty()) { "Data pack $dataPackId has no files" }
    }

    fun dataPackFileContent(dataPackFilePath: DataPackFilePath): DataPackFileContent? =
        dataPackFileContentsByPath[dataPackFilePath]

    fun resources(
        dataPackFormatVersion: DataPackFormatVersion? = null,
    ): Map<DataPackResourcePath, DataPackFileContent> =
        effectiveDataPackFiles(dataPackFormatVersion).mapValues { (_, effectiveDataPackFile) ->
            effectiveDataPackFile.dataPackFileContent
        }

    fun resource(
        dataPackResourceType: DataPackResourceType,
        dataPackResourceId: DataPackResourceId,
        dataPackFormatVersion: DataPackFormatVersion? = null,
    ): DataPackFileContent? = resources(dataPackFormatVersion)[dataPackResourceType.path(dataPackResourceId)]
}

internal val NAMESPACE_PATTERN = Regex("[a-z0-9._-]+")
internal val RESOURCE_PATH_PATTERN = Regex("[a-z0-9._/-]+")
private val EXTENSION_PATTERN = Regex("[a-z0-9._-]+")
