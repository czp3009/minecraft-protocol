package com.hiczp.minecraft.world.format.datapack

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtTag
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
value class DataPackPath(val value: String) {
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
        val PACK_METADATA: DataPackPath = DataPackPath("pack.mcmeta")
    }
}

/** Immutable bytes retained for unknown or mod-defined data-pack files. */
class DataPackBinary(bytes: ByteArray) {
    private val snapshot = bytes.copyOf()

    val size: Int
        get() = snapshot.size

    fun toByteArray(): ByteArray = snapshot.copyOf()

    override fun equals(other: Any?): Boolean = other is DataPackBinary && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "DataPackBinary(size=$size)"
}

/** Complete raw in-memory contents of one data pack, before file-type parsing. */
class DataPackArchive(
    val id: DataPackId,
    files: Map<DataPackPath, DataPackBinary>,
) {
    val files: Map<DataPackPath, DataPackBinary> = files.toMap()

    init {
        require(this.files.isNotEmpty()) { "Data pack $id has no files" }
    }

    constructor(id: DataPackId, files: Iterable<Pair<DataPackPath, ByteArray>>) : this(
        id,
        files.toDataPackBinaryMap(id),
    )
}

private fun Iterable<Pair<DataPackPath, ByteArray>>.toDataPackBinaryMap(
    id: DataPackId,
): Map<DataPackPath, DataPackBinary> = buildMap {
    this@toDataPackBinaryMap.forEach { (path, bytes) ->
        require(path !in this) { "Data pack $id contains duplicate path $path" }
        put(path, DataPackBinary(bytes))
    }
}

/** Parsed, filesystem-independent content of one data-pack file. Mods may implement their own strong variants. */
interface DataPackFileContent {
    data class JsonFile(val element: JsonElement) : DataPackFileContent {
        fun <T> decode(
            deserializer: DeserializationStrategy<T>,
            json: Json = Json,
        ): T = json.decodeFromJsonElement(deserializer, element)
    }

    /** GZIP NBT decoded on first [document] access so complete structure libraries need not expand eagerly. */
    class NbtFile : DataPackFileContent {
        private val parsedDocument: Lazy<NbtDocument>

        constructor(document: NbtDocument) {
            parsedDocument = lazyOf(document)
        }

        internal constructor(parser: () -> NbtDocument) {
            parsedDocument = lazy(LazyThreadSafetyMode.PUBLICATION, parser)
        }

        val document: NbtDocument
            get() = parsedDocument.value

        override fun equals(other: Any?): Boolean = other is NbtFile && document == other.document

        override fun hashCode(): Int = document.hashCode()

        override fun toString(): String = if (parsedDocument.isInitialized()) {
            "NbtFile(document=${parsedDocument.value})"
        } else {
            "NbtFile(deferred)"
        }
    }

    data class SnbtFile(val tag: NbtTag) : DataPackFileContent

    data class TextFile(val text: String) : DataPackFileContent

    class BinaryFile(val bytes: DataPackBinary) : DataPackFileContent {
        override fun equals(other: Any?): Boolean = other is BinaryFile && bytes == other.bytes

        override fun hashCode(): Int = bytes.hashCode()

        override fun toString(): String = "BinaryFile(bytes=$bytes)"
    }
}

/** One parsed file and its original path within the pack. */
data class DataPackFile(
    val path: DataPackPath,
    val content: DataPackFileContent,
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

data class DataPackFormatRange(
    val minimum: DataPackFormatVersion,
    val maximum: DataPackFormatVersion,
) {
    init {
        require(minimum <= maximum) { "Data-pack format range $minimum..$maximum is reversed" }
    }

    operator fun contains(version: DataPackFormatVersion): Boolean = version in minimum..maximum

    companion object {
        fun exact(version: DataPackFormatVersion): DataPackFormatRange = DataPackFormatRange(version, version)
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

    fun matches(resource: DataPackResourcePath): Boolean =
        namespaceRegex?.matches(resource.namespace) != false && pathRegex?.matches(resource.path) != false
}

data class DataPackOverlay(
    val formats: DataPackFormatRange,
    val directory: DataPackPath,
)

/** Strong views of selected-release `pack.mcmeta` fields plus the lossless source object. */
data class DataPackMetadata(
    val description: JsonElement,
    val formats: DataPackFormatRange,
    val enabledFeatures: Set<String> = emptySet(),
    val filters: List<DataPackFilterPattern> = emptyList(),
    val overlays: List<DataPackOverlay> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
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

    fun path(id: DataPackResourceId): DataPackResourcePath =
        DataPackResourcePath(id.namespace, "$directory/${id.path}.$extension")

    fun id(path: DataPackResourcePath): DataPackResourceId? {
        val prefix = "$directory/"
        val suffix = ".$extension"
        if (!path.path.startsWith(prefix) || !path.path.endsWith(suffix)) return null
        val identifierPath = path.path.removePrefix(prefix).removeSuffix(suffix)
        if (identifierPath.isEmpty()) return null
        return DataPackResourceId(path.namespace, identifierPath)
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

/** Fully parsed contents of one data pack. Every original file remains addressable in [files]. */
class DataPack(
    val id: DataPackId,
    val metadata: DataPackMetadata?,
    files: Map<DataPackPath, DataPackFileContent>,
) {
    val files: Map<DataPackPath, DataPackFileContent> = files.toMap()

    init {
        require(this.files.isNotEmpty()) { "Data pack $id has no files" }
    }

    fun file(path: DataPackPath): DataPackFileContent? = files[path]

    fun resources(format: DataPackFormatVersion? = null): Map<DataPackResourcePath, DataPackFileContent> =
        effectiveDataPackFiles(format).mapValues { (_, file) -> file.content }

    fun resource(
        type: DataPackResourceType,
        id: DataPackResourceId,
        format: DataPackFormatVersion? = null,
    ): DataPackFileContent? = resources(format)[type.path(id)]
}

internal val NAMESPACE_PATTERN = Regex("[a-z0-9._-]+")
internal val RESOURCE_PATH_PATTERN = Regex("[a-z0-9._/-]+")
private val EXTENSION_PATTERN = Regex("[a-z0-9._-]+")
