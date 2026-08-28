package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileBytes
import com.hiczp.minecraft.world.format.datapack.DataPackFilePath
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip

internal actual fun openDataPackZipReader(
    fileSystem: FileSystem,
    dataPackZipPath: Path,
): DataPackZipReader = OkioDataPackZipReader(fileSystem.openZip(dataPackZipPath), dataPackZipPath)

private class OkioDataPackZipReader(
    private val zipFileSystem: FileSystem,
    private val dataPackZipPath: Path,
) : DataPackZipReader {
    override fun inspectDataPackFileInfos(): List<DataPackFileInfo> {
        val dataPackFileInfos = zipFileSystem.listRecursively(ZIP_ROOT, followSymlinks = false)
            .mapNotNull { zipEntryPath ->
                val fileMetadata = zipFileSystem.metadataOrNull(zipEntryPath) ?: return@mapNotNull null
                if (!fileMetadata.isRegularFile) return@mapNotNull null
                val sizeInBytes = fileMetadata.size
                    ?: throw WorldIOException("Data-pack ZIP entry has no size: $zipEntryPath")
                if (sizeInBytes < 0L) {
                    throw WorldIOException("Data-pack ZIP entry has a negative size: $zipEntryPath")
                }
                DataPackFileInfo(
                    DataPackFilePath(zipEntryPath.relativeTo(ZIP_ROOT).segments.joinToString("/")),
                    sizeInBytes,
                )
            }.sortedBy { it.dataPackFilePath.value }.toList()
        if (dataPackFileInfos.isEmpty()) {
            throw WorldIOException("Data-pack ZIP has no regular files: $dataPackZipPath")
        }
        return dataPackFileInfos
    }

    override fun readDataPackFiles(
        selectedDataPackFilePaths: Set<DataPackFilePath>?,
        block: (DataPackFilePath, DataPackFileBytes) -> Unit,
    ) {
        val availableDataPackFileInfos = inspectDataPackFileInfos()
        if (selectedDataPackFilePaths != null) {
            val availableDataPackFilePaths = availableDataPackFileInfos.mapTo(
                mutableSetOf(),
                DataPackFileInfo::dataPackFilePath,
            )
            val missingDataPackFilePaths = selectedDataPackFilePaths - availableDataPackFilePaths
            if (missingDataPackFilePaths.isNotEmpty()) {
                throw WorldIOException(
                    "Data-pack ZIP $dataPackZipPath is missing ${missingDataPackFilePaths.joinToString()}",
                )
            }
        }
        availableDataPackFileInfos.forEach { dataPackFileInfo ->
            val dataPackFilePath = dataPackFileInfo.dataPackFilePath
            if (selectedDataPackFilePaths == null || dataPackFilePath in selectedDataPackFilePaths) {
                block(
                    dataPackFilePath,
                    DataPackFileBytes(zipFileSystem.readFileBytes(dataPackFilePath.resolveBelow(ZIP_ROOT))),
                )
            }
        }
    }

    override fun <T> readDataPackFile(
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T {
        val zipEntryPath = dataPackFilePath.resolveBelow(ZIP_ROOT)
        return zipFileSystem.readFile(zipEntryPath) { bufferedSource, _ -> block(bufferedSource) }
    }

    private fun DataPackFilePath.resolveBelow(root: Path): Path =
        segments.fold(root) { parent, segment -> parent / segment }

    companion object {
        private val ZIP_ROOT = "/".toPath()
    }
}
