package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileBytes
import com.hiczp.minecraft.world.format.datapack.DataPackFilePath
import com.hiczp.minecraft.world.io.internal.admzip.AdmZipEntry
import com.hiczp.minecraft.world.io.internal.admzip.createAdmZip
import com.hiczp.minecraft.world.io.internal.admzip.toExactByteArray
import com.hiczp.minecraft.world.io.internal.admzip.toExactUint8Array
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

internal actual fun openDataPackZipReader(
    fileSystem: FileSystem,
    dataPackZipPath: Path,
): DataPackZipReader = AdmZipDataPackZipReader(fileSystem, dataPackZipPath)

/**
 * Okio does not publish its ZIP filesystem for Kotlin/JS. adm-zip therefore owns central-directory parsing, DEFLATE,
 * and CRC validation here; this adapter only maps its synchronous metadata and entry values onto world-io APIs.
 */
private class AdmZipDataPackZipReader(
    private val fileSystem: FileSystem,
    private val dataPackZipPath: Path,
) : DataPackZipReader {
    override fun inspectDataPackFileInfos(): List<DataPackFileInfo> {
        val dataPackFileInfos = effectiveDataPackZipEntries().map { (dataPackFilePath, admZipEntry) ->
            DataPackFileInfo(dataPackFilePath, admZipEntry.uncompressedSizeInBytes())
        }
        if (dataPackFileInfos.isEmpty()) {
            throw WorldIOException("Data-pack ZIP has no regular files: $dataPackZipPath")
        }
        return dataPackFileInfos
    }

    override fun readDataPackFiles(
        selectedDataPackFilePaths: Set<DataPackFilePath>?,
        block: (DataPackFilePath, DataPackFileBytes) -> Unit,
    ) {
        val dataPackZipEntries = effectiveDataPackZipEntries()
        val availableDataPackFilePaths = dataPackZipEntries.mapTo(mutableSetOf()) { it.first }
        dataPackZipEntries.forEach { (dataPackFilePath, admZipEntry) ->
            if (selectedDataPackFilePaths == null || dataPackFilePath in selectedDataPackFilePaths) {
                block(dataPackFilePath, DataPackFileBytes(admZipEntry.dataPackFileBytes()))
            }
        }
        if (availableDataPackFilePaths.isEmpty()) {
            throw WorldIOException("Data-pack ZIP has no regular files: $dataPackZipPath")
        }
        if (selectedDataPackFilePaths != null) {
            val missingDataPackFilePaths = selectedDataPackFilePaths - availableDataPackFilePaths
            if (missingDataPackFilePaths.isNotEmpty()) {
                throw WorldIOException(
                    "Data-pack ZIP $dataPackZipPath is missing ${missingDataPackFilePaths.joinToString()}",
                )
            }
        }
    }

    override fun <T> readDataPackFile(
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T {
        val admZipEntry = effectiveDataPackZipEntries().singleOrNull { it.first == dataPackFilePath }?.second
            ?: throw WorldIOException("Data-pack ZIP $dataPackZipPath is missing $dataPackFilePath")
        val dataPackFileSource = Buffer().apply { write(admZipEntry.dataPackFileBytes()) }
        val result = block(dataPackFileSource)
        if (!dataPackFileSource.exhausted()) {
            throw WorldIOException("Data-pack ZIP entry $dataPackFilePath was not fully consumed")
        }
        return result
    }

    private fun admZipEntries(): Array<AdmZipEntry> = zipOperation("Cannot read data-pack ZIP $dataPackZipPath") {
        val zipFileBytes = fileSystem.readFileBytes(dataPackZipPath)
        createAdmZip(zipFileBytes.toExactUint8Array()).getEntries()
    }

    private fun effectiveDataPackZipEntries(): List<Pair<DataPackFilePath, AdmZipEntry>> {
        val dataPackZipEntries = linkedMapOf<DataPackFilePath, AdmZipEntry>()
        admZipEntries().forEach { admZipEntry ->
            admZipEntry.dataPackFilePathOrNull()?.let { dataPackFilePath ->
                dataPackZipEntries[dataPackFilePath] = admZipEntry
            }
        }
        return dataPackZipEntries.entries.sortedBy { it.key.value }
            .map { (dataPackFilePath, admZipEntry) -> dataPackFilePath to admZipEntry }
    }

    private fun AdmZipEntry.dataPackFilePathOrNull(): DataPackFilePath? {
        if (isDirectory) return null
        return try {
            DataPackFilePath(entryName)
        } catch (failure: IllegalArgumentException) {
            throw WorldIOException("Data-pack ZIP contains an unsafe entry path: $entryName", failure)
        }
    }

    private fun AdmZipEntry.uncompressedSizeInBytes(): Long {
        val sizeInBytes = header.size
        if (!sizeInBytes.isFinite() || sizeInBytes < 0.0 || sizeInBytes.toLong().toDouble() != sizeInBytes) {
            throw WorldIOException("Data-pack ZIP entry $entryName has an invalid uncompressed size: $sizeInBytes")
        }
        return sizeInBytes.toLong()
    }

    private fun AdmZipEntry.dataPackFileBytes(): ByteArray = zipOperation(
        "Cannot read data-pack ZIP entry $entryName",
    ) {
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
