package com.hiczp.minecraft.test.host

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
import java.nio.file.StandardCopyOption

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

internal fun Path.deleteFilesRecursively() {
    check(isDirectory()) { "Directory does not exist: $this" }
    SystemFileSystem.list(this).forEach { child ->
        if (child.isDirectory()) {
            child.deleteFilesRecursively()
        } else {
            SystemFileSystem.delete(child)
        }
    }
}

internal fun Path.copyTreeTo(
    destination: Path,
    excludedRelativePaths: Set<String> = emptySet(),
) {
    check(isDirectory()) { "Source directory does not exist: $this" }
    val source = toNioPath()
    val target = destination.toNioPath()
    val excludedPaths = excludedRelativePaths
        .map { relativePath -> safeResolve(relativePath).toNioPath() }
    Files.walk(source).use { paths ->
        paths.forEach { current ->
            if (excludedPaths.any(current::startsWith)) {
                return@forEach
            }
            val relative = source.relativize(current)
            val output = target.resolve(relative)
            if (Files.isDirectory(current)) {
                Files.createDirectories(output)
            } else {
                Files.createDirectories(checkNotNull(output.parent))
                Files.copy(
                    current,
                    output,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
        }
    }
}

internal fun Path.linkTreeTo(destination: Path) {
    check(isDirectory()) { "Source directory does not exist: $this" }
    val source = toNioPath()
    val target = destination.toNioPath()
    Files.walk(source).use { paths ->
        paths.forEach { current ->
            val relative = source.relativize(current)
            val output = target.resolve(relative)
            if (Files.isDirectory(current)) {
                Files.createDirectories(output)
            } else {
                Files.createDirectories(checkNotNull(output.parent))
                linkFileOrCopy(current, output)
            }
        }
    }
}

internal fun Path.linkFileTo(destination: Path): Boolean {
    check(isRegularFile()) { "Source file does not exist: $this" }
    destination.parent?.ensureDirectory()
    return linkFileOrCopy(toNioPath(), destination.toNioPath())
}

internal fun Path.copyFileTo(destination: Path) {
    check(isRegularFile()) { "Source file does not exist: $this" }
    destination.parent?.ensureDirectory()
    Files.copy(
        toNioPath(),
        destination.toNioPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )
}

private fun linkFileOrCopy(
    source: java.nio.file.Path,
    destination: java.nio.file.Path,
): Boolean = runCatching {
    Files.createLink(destination, source)
    true
}.getOrElse {
    Files.copy(
        source,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )
    false
}

internal fun createUniqueDirectory(parent: Path): Path {
    parent.ensureDirectory()
    return Path(Files.createTempDirectory(parent.toNioPath(), "run-").toString())
}
