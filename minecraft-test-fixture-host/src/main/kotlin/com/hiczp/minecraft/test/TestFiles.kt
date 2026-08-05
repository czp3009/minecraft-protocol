package com.hiczp.minecraft.test

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.hash.md.MD5
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

internal fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.let { value ->
        check(value.isString) { "JSON property '$name' is not a string" }
        value.content
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

internal fun Path.writeText(content: String) {
    writeBytes(content.encodeToByteArray())
}

internal fun Path.writeBytes(content: ByteArray) {
    val directory = requireNotNull(parent) { "Output path has no parent: $this" }
    directory.ensureDirectory()
    Files.write(toNioPath(), content)
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
