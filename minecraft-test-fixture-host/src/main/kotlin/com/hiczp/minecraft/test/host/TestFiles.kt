package com.hiczp.minecraft.test.host

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal val testJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

internal fun Path.safeResolve(relative: String): Path {
    require(relative.isNotBlank()) { "Relative path is blank" }
    require(!Path.of(relative).isAbsolute) { "Path is absolute: $relative" }
    val components = relative.split('/', '\\')
    require(components.all { it.isNotEmpty() && it != "." && it != ".." && ':' !in it }) {
        "Path escapes $this: $relative"
    }
    return components.fold(this) { path, component -> path.resolve(component) }
}

internal fun Path.deleteTree() {
    if (!Files.exists(this, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

internal fun deleteTreesPreserving(
    primaryFailure: Throwable?,
    vararg paths: Path,
) {
    var cleanupFailure: Throwable? = null
    paths.forEach { path ->
        try {
            path.deleteTree()
        } catch (failure: Throwable) {
            cleanupFailure?.addSuppressed(failure)
                ?: run { cleanupFailure = failure }
        }
    }
    cleanupFailure?.let { failure ->
        primaryFailure?.addSuppressed(failure) ?: throw failure
    }
}

internal fun Path.deleteFilesRecursively() {
    check(Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS)) {
        "Directory does not exist: $this"
    }
    Files.list(this).use { children ->
        children.forEach { child ->
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                child.deleteFilesRecursively()
            } else {
                Files.delete(child)
            }
        }
    }
}

internal fun Path.copyTreeTo(
    destination: Path,
    excludedRelativePaths: Set<String> = emptySet(),
) {
    check(isDirectory()) { "Source directory does not exist: $this" }
    val excludedPaths = excludedRelativePaths.map(::safeResolve)
    Files.walk(this).use { paths ->
        paths.forEach { current ->
            if (excludedPaths.any(current::startsWith)) {
                return@forEach
            }
            val relative = relativize(current)
            val output = destination.resolve(relative)
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

internal fun Path.linkTreeTo(
    destination: Path,
    excludedRelativePaths: Set<String> = emptySet(),
) {
    check(isDirectory()) { "Source directory does not exist: $this" }
    val excludedPaths = excludedRelativePaths.map(::safeResolve)
    Files.walk(this).use { paths ->
        paths.forEach { current ->
            if (excludedPaths.any(current::startsWith)) {
                return@forEach
            }
            val relative = relativize(current)
            val output = destination.resolve(relative)
            if (Files.isDirectory(current)) {
                Files.createDirectories(output)
            } else {
                Files.createDirectories(checkNotNull(output.parent))
                linkFileOrCopy(current, output)
            }
        }
    }
}

/**
 * Materializes this immutable directory with one symbolic link. If the host
 * cannot create directory symbolic links, files use the normal hard-link or
 * copy fallback inside private directory entries.
 */
internal fun Path.linkDirectoryTo(destination: Path): Boolean {
    check(isDirectory()) { "Source directory does not exist: $this" }
    destination.parent?.createDirectories()
    val failure = try {
        Files.createSymbolicLink(
            destination,
            toAbsolutePath().normalize(),
        )
        return true
    } catch (failure: IOException) {
        failure
    } catch (failure: UnsupportedOperationException) {
        failure
    } catch (failure: SecurityException) {
        failure
    }
    try {
        linkTreeTo(destination)
    } catch (fallbackFailure: Throwable) {
        fallbackFailure.addSuppressed(failure)
        throw fallbackFailure
    }
    return false
}

internal fun Path.linkFileTo(destination: Path): Boolean {
    check(isRegularFile()) { "Source file does not exist: $this" }
    destination.parent?.createDirectories()
    return linkFileOrCopy(this, destination)
}

internal fun Path.copyFileTo(destination: Path) {
    check(isRegularFile()) { "Source file does not exist: $this" }
    destination.parent?.createDirectories()
    Files.copy(
        this,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )
}

private fun linkFileOrCopy(
    source: java.nio.file.Path,
    destination: java.nio.file.Path,
): Boolean {
    val linkFailure = try {
        Files.createLink(destination, source)
        return true
    } catch (failure: IOException) {
        failure
    } catch (failure: UnsupportedOperationException) {
        failure
    } catch (failure: SecurityException) {
        failure
    }
    try {
        Files.copy(
            source,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
    } catch (fallbackFailure: Throwable) {
        fallbackFailure.addSuppressed(linkFailure)
        throw fallbackFailure
    }
    return false
}

internal fun createUniqueDirectory(parent: Path): Path {
    parent.createDirectories()
    return Files.createTempDirectory(parent, "run-")
}
