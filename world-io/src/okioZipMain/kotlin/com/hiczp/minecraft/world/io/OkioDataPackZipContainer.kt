package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackBinary
import com.hiczp.minecraft.world.format.datapack.DataPackPath
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip

internal actual fun openDataPackZip(
    fileSystem: FileSystem,
    path: Path,
): DataPackZipContainer = OkioDataPackZipContainer(fileSystem.openZip(path), path)

private class OkioDataPackZipContainer(
    private val zipFileSystem: FileSystem,
    private val containerPath: Path,
) : DataPackZipContainer {
    override fun inspect(): List<DataPackFileInfo> {
        val files = zipFileSystem.listRecursively(ZIP_ROOT, followSymlinks = false).mapNotNull { path ->
            val metadata = zipFileSystem.metadataOrNull(path) ?: return@mapNotNull null
            if (!metadata.isRegularFile) return@mapNotNull null
            val size = metadata.size ?: throw WorldIOException("Data-pack ZIP entry has no size: $path")
            if (size < 0L) throw WorldIOException("Data-pack ZIP entry has a negative size: $path")
            DataPackFileInfo(
                DataPackPath(path.relativeTo(ZIP_ROOT).segments.joinToString("/")),
                size,
            )
        }.sortedBy { it.path.value }.toList()
        if (files.isEmpty()) throw WorldIOException("Data-pack ZIP has no regular files: $containerPath")
        return files
    }

    override fun readFiles(
        selectedPaths: Set<DataPackPath>?,
        block: (DataPackPath, DataPackBinary) -> Unit,
    ) {
        val available = inspect()
        if (selectedPaths != null) {
            val availablePaths = available.mapTo(mutableSetOf(), DataPackFileInfo::path)
            val missing = selectedPaths - availablePaths
            if (missing.isNotEmpty()) {
                throw WorldIOException("Data-pack ZIP $containerPath is missing ${missing.joinToString()}")
            }
        }
        available.forEach { file ->
            if (selectedPaths == null || file.path in selectedPaths) {
                block(file.path, DataPackBinary(zipFileSystem.readFileBytes(file.path.resolveBelow(ZIP_ROOT))))
            }
        }
    }

    override fun <T> readFile(
        path: DataPackPath,
        block: (Source) -> T,
    ): T {
        val filePath = path.resolveBelow(ZIP_ROOT)
        return zipFileSystem.readFile(filePath) { source, _ ->
            withOkioIoExceptions("Cannot read data-pack ZIP entry $filePath") {
                val converted = source.asKotlinxIoRawSource().buffered()
                val value = block(converted)
                if (!converted.exhausted()) {
                    throw WorldIOException("Data-pack ZIP entry $filePath was not fully consumed")
                }
                value
            }
        }
    }

    private fun DataPackPath.resolveBelow(root: Path): Path =
        segments.fold(root) { parent, segment -> parent / segment }

    companion object {
        private val ZIP_ROOT = "/".toPath()
    }
}
