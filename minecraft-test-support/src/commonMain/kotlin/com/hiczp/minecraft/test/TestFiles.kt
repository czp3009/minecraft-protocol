package com.hiczp.minecraft.test

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

internal fun Path.isRegularFile(): Boolean =
    SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

internal fun Path.ensureDirectory() {
    SystemFileSystem.createDirectories(this)
}

internal fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use(Source::readByteArray)

internal fun Path.writeBytes(content: ByteArray) {
    val directory = requireNotNull(parent) { "Output path has no parent: $this" }
    directory.ensureDirectory()
    SystemFileSystem.sink(this).buffered().use { sink ->
        sink.write(content)
    }
}

internal fun Path.safeResolve(relative: String): Path {
    require(relative.isNotBlank()) { "Relative path is blank" }
    require(!Path(relative).isAbsolute) { "Path is absolute: $relative" }
    val components = relative.split('/', '\\')
    require(components.all { it.isNotEmpty() && it != "." && it != ".." }) {
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

internal fun createTestTemporaryDirectory(): Path {
    val directory = Path(
        SystemTemporaryDirectory,
        "minecraft-test-support-${Uuid.random()}",
    )
    SystemFileSystem.createDirectories(directory, mustCreate = true)
    return directory
}
