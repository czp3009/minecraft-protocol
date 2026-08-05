package com.hiczp.minecraft.test

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import org.kotlincrypto.core.digest.Digest
import org.kotlincrypto.hash.md.MD5
import org.kotlincrypto.hash.sha2.SHA256
import java.nio.file.Files

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

internal fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use(Source::readByteArray)

internal fun Path.readText(): String = readBytes().decodeToString()

internal fun Path.readJsonObject(): JsonObject =
    testJson.parseToJsonElement(readText()).jsonObject

internal fun Path.writeJson(value: JsonElement) {
    writeText("${testJson.encodeToString(JsonElement.serializer(), value.withSortedObjectKeys())}\n")
}

private fun JsonElement.withSortedObjectKeys(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map(JsonElement::withSortedObjectKeys))
    is JsonObject -> JsonObject(
        entries.sortedBy { it.key }.associateTo(linkedMapOf()) { (k, v) -> k to v.withSortedObjectKeys() }
    )

    else -> this
}

internal fun Path.writeText(content: String) {
    writeBytes(content.encodeToByteArray())
}

internal fun Path.writeBytes(content: ByteArray) {
    val directory = requireNotNull(parent) { "Output path has no parent: $this" }
    directory.ensureDirectory()
    Files.write(toNioPath(), content)
}

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
    return Path(Files.createTempDirectory(parent.toNioPath(), "run-").toString())
}

private const val FILE_BUFFER_SIZE = 1024 * 1024
