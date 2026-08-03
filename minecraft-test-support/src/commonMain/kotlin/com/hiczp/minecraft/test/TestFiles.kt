package com.hiczp.minecraft.test

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import org.kotlincrypto.core.digest.Digest
import org.kotlincrypto.hash.md.MD5
import org.kotlincrypto.hash.sha1.SHA1
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.random.Random
import kotlin.uuid.Uuid

// ── JSON ──────────────────────────────────────────────────────────

internal val testJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

internal fun ByteArray.decodeJsonObject(source: String): JsonObject =
    try {
        testJson.parseToJsonElement(decodeToString()).jsonObject
    } catch (failure: Throwable) {
        throw IllegalStateException("Source is not a UTF-8 JSON object: $source", failure)
    }

internal fun JsonObject.requiredObject(name: String): JsonObject =
    getValue(name).jsonObject

internal fun JsonObject.requiredArray(name: String): JsonArray =
    getValue(name).jsonArray

internal fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.let { value ->
        check(value.isString) { "JSON property '$name' is not a string" }
        value.content
    }

internal fun JsonObject.requiredInt(name: String): Int =
    getValue(name).jsonPrimitive.let { value ->
        check(!value.isString) { "JSON property '$name' is not an integer" }
        value.intOrNull ?: error("JSON property '$name' is not an integer")
    }

internal fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.let { value ->
        check(!value.isString) { "JSON property '$name' is not an integer" }
        value.longOrNull ?: error("JSON property '$name' is not an integer")
    }

internal fun jsonObjectOf(vararg entries: Pair<String, JsonElement>): JsonObject =
    JsonObject(linkedMapOf(*entries))

internal fun jsonString(value: String): JsonPrimitive = JsonPrimitive(value)

internal fun jsonNumber(value: Number): JsonPrimitive = JsonPrimitive(value)

// ── file system ──────────────────────────────────────────────────

internal fun Path.exists(): Boolean = SystemFileSystem.exists(this)

internal fun Path.isRegularFile(): Boolean =
    SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

internal fun Path.isDirectory(): Boolean =
    SystemFileSystem.metadataOrNull(this)?.isDirectory == true

internal fun Path.ensureDirectory() {
    try {
        SystemFileSystem.createDirectories(this)
    } catch (failure: Throwable) {
        val directoryExists = runCatching { isDirectory() }.getOrDefault(false)
        if (!directoryExists) throw failure
    }
}

internal fun Path.size(): Long =
    checkNotNull(SystemFileSystem.metadataOrNull(this)) {
        "Filesystem entry does not exist: $this"
    }.size

internal fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use(Source::readByteArray)

internal fun Path.readText(): String = readBytes().decodeToString()

internal fun Path.readJsonObject(): JsonObject =
    testJson.parseToJsonElement(readText()).jsonObject

// ── JSON I/O ─────────────────────────────────────────────────────

internal fun Path.writeJson(value: JsonElement) {
    atomicWriteText("${testJson.encodeToString(JsonElement.serializer(), value.withSortedObjectKeys())}\n")
}

private fun JsonElement.withSortedObjectKeys(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map(JsonElement::withSortedObjectKeys))
    is JsonObject -> JsonObject(
        entries.sortedBy { it.key }.associateTo(linkedMapOf()) { (k, v) -> k to v.withSortedObjectKeys() }
    )
    else -> this
}

fun Path.writeJsonReport(value: JsonElement) {
    writeJson(value)
}

// ── atomic writes ────────────────────────────────────────────────

internal fun Path.atomicWriteText(content: String) {
    atomicWrite(content.encodeToByteArray())
}

internal fun Path.atomicWrite(content: ByteArray) {
    val directory = requireNotNull(parent) { "Output path has no parent: $this" }
    directory.ensureDirectory()
    val temporary = uniqueSibling(".$name.", ".tmp")
    try {
        SystemFileSystem.sink(temporary).buffered().use { sink -> sink.write(content) }
        try {
            SystemFileSystem.atomicMove(temporary, this)
        } catch (_: Throwable) {
            if (hasContent(content)) return
            throw IllegalStateException("Atomic commit failed: $this")
        }
    } finally {
        SystemFileSystem.delete(temporary, mustExist = false)
    }
}

private fun Path.hasContent(expected: ByteArray): Boolean =
    runCatching { isRegularFile() && readBytes().contentEquals(expected) }.getOrDefault(false)

// ── digests ──────────────────────────────────────────────────────

internal fun Path.sha1(): String = digest(SHA1())

internal fun Path.sha256(): String = digest(SHA256())

private fun Path.digest(digest: Digest): String {
    SystemFileSystem.source(this).buffered().use { source ->
        val buffer = ByteArray(FILE_BUFFER_SIZE)
        while (true) {
            val count = source.readAtMostTo(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString()
}

internal fun ByteArray.md5(): ByteArray = MD5().digest(this)

internal fun ByteArray.sha1(): String = SHA1().digest(this).toHexString()

internal fun ByteArray.sha256(): String = SHA256().digest(this).toHexString()

// ── path helpers ─────────────────────────────────────────────────

internal fun Path.safeResolve(relative: String): Path {
    require(relative.isNotBlank()) { "Relative path is blank" }
    require(!Path(relative).isAbsolute) { "Path is absolute: $relative" }
    val components = relative.split('/', '\\')
    require(components.all { it.isNotEmpty() && it != "." && it != ".." && ':' !in it }) {
        "Path escapes $this: $relative"
    }
    return Path(this, *components.toTypedArray())
}

internal fun Path.deleteTree() {
    val metadata = SystemFileSystem.metadataOrNull(this) ?: return
    if (metadata.isDirectory) {
        SystemFileSystem.list(this).forEach(Path::deleteTree)
    }
    SystemFileSystem.delete(this)
}

internal fun createUniqueDirectory(parent: Path): Path {
    parent.ensureDirectory()
    repeat(UNIQUE_PATH_ATTEMPTS) {
        val candidate = Path(parent, Uuid.random().toString())
        val created = runCatching { SystemFileSystem.createDirectories(candidate, mustCreate = true) }.isSuccess
        if (created) return candidate
    }
    error("Could not create a unique directory under $parent")
}

private fun Path.uniqueSibling(prefix: String, suffix: String): Path {
    val directory = requireNotNull(parent)
    repeat(UNIQUE_PATH_ATTEMPTS) {
        val candidate = Path(directory, "$prefix${randomSuffix()}$suffix")
        if (!candidate.exists()) return candidate
    }
    error("Could not create a unique temporary path beside $this")
}

private fun randomSuffix(): String = Random.nextBytes(UNIQUE_SUFFIX_BYTES).toHexString()

// ── constants ────────────────────────────────────────────────────

private const val FILE_BUFFER_SIZE = 1024 * 1024
private const val UNIQUE_PATH_ATTEMPTS = 100
private const val UNIQUE_SUFFIX_BYTES = 12
