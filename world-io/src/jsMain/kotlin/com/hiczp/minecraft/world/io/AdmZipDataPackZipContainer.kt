package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackBinary
import com.hiczp.minecraft.world.format.datapack.DataPackPath
import com.hiczp.minecraft.world.io.internal.admzip.AdmZipEntry
import com.hiczp.minecraft.world.io.internal.admzip.createAdmZip
import com.hiczp.minecraft.world.io.internal.admzip.toExactByteArray
import com.hiczp.minecraft.world.io.internal.admzip.toExactUint8Array
import kotlinx.io.Buffer
import kotlinx.io.Source
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

internal actual fun openDataPackZip(
    fileSystem: FileSystem,
    path: Path,
): DataPackZipContainer = AdmZipDataPackZipContainer(fileSystem, path)

/**
 * Okio does not publish its ZIP filesystem for Kotlin/JS. adm-zip therefore owns central-directory parsing, DEFLATE,
 * and CRC validation here; this adapter only maps its synchronous metadata and entry values onto world-io APIs.
 */
private class AdmZipDataPackZipContainer(
    private val fileSystem: FileSystem,
    private val containerPath: Path,
) : DataPackZipContainer {
    override fun inspect(): List<DataPackFileInfo> {
        val files = effectiveEntries().map { (path, entry) ->
            DataPackFileInfo(path, entry.uncompressedSize())
        }
        if (files.isEmpty()) throw WorldIOException("Data-pack ZIP has no regular files: $containerPath")
        return files
    }

    override fun readFiles(
        selectedPaths: Set<DataPackPath>?,
        block: (DataPackPath, DataPackBinary) -> Unit,
    ) {
        val entries = effectiveEntries()
        val found = entries.mapTo(mutableSetOf()) { it.first }
        entries.forEach { (path, entry) ->
            if (selectedPaths == null || path in selectedPaths) {
                block(path, DataPackBinary(entry.data()))
            }
        }
        if (found.isEmpty()) throw WorldIOException("Data-pack ZIP has no regular files: $containerPath")
        if (selectedPaths != null) {
            val missing = selectedPaths - found
            if (missing.isNotEmpty()) {
                throw WorldIOException("Data-pack ZIP $containerPath is missing ${missing.joinToString()}")
            }
        }
    }

    override fun <T> readFile(
        path: DataPackPath,
        block: (Source) -> T,
    ): T {
        val entry = effectiveEntries().singleOrNull { it.first == path }?.second
            ?: throw WorldIOException("Data-pack ZIP $containerPath is missing $path")
        val source = Buffer().apply { write(entry.data()) }
        val value = block(source)
        if (!source.exhausted()) throw WorldIOException("Data-pack ZIP entry $path was not fully consumed")
        return value
    }

    private fun zipEntries(): Array<AdmZipEntry> = zipOperation("Cannot read data-pack ZIP $containerPath") {
        val bytes = fileSystem.readFileBytes(containerPath)
        createAdmZip(bytes.toExactUint8Array()).getEntries()
    }

    private fun effectiveEntries(): List<Pair<DataPackPath, AdmZipEntry>> {
        val entries = linkedMapOf<DataPackPath, AdmZipEntry>()
        zipEntries().forEach { entry ->
            entry.dataPackPathOrNull()?.let { path -> entries[path] = entry }
        }
        return entries.entries.sortedBy { it.key.value }.map { (path, entry) -> path to entry }
    }

    private fun AdmZipEntry.dataPackPathOrNull(): DataPackPath? {
        if (isDirectory) return null
        return try {
            DataPackPath(entryName)
        } catch (failure: IllegalArgumentException) {
            throw WorldIOException("Data-pack ZIP contains an unsafe entry path: $entryName", failure)
        }
    }

    private fun AdmZipEntry.uncompressedSize(): Long {
        val value = header.size
        if (!value.isFinite() || value < 0.0 || value.toLong().toDouble() != value) {
            throw WorldIOException("Data-pack ZIP entry $entryName has an invalid uncompressed size: $value")
        }
        return value.toLong()
    }

    private fun AdmZipEntry.data(): ByteArray = zipOperation("Cannot read data-pack ZIP entry $entryName") {
        getData().toExactByteArray()
    }
}

// External JavaScript may throw values outside Kotlin's Exception hierarchy. Keep caller callbacks outside this
// boundary so their own failures remain unchanged.
private inline fun <T> zipOperation(
    message: String,
    block: () -> T,
): T = try {
    block()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: WorldIOException) {
    throw failure
} catch (failure: Throwable) {
    throw WorldIOException(message, failure)
}
