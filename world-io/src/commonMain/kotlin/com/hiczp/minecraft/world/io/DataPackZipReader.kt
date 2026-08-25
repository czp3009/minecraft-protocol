package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileBytes
import com.hiczp.minecraft.world.format.datapack.DataPackFilePath
import kotlinx.io.Source
import okio.FileSystem
import okio.Path

/** Platform ZIP adapter. Okio owns random-access ZIP reads where available; Node uses adm-zip's central directory. */
internal interface DataPackZipReader {
    fun inspectDataPackFileInfos(): List<DataPackFileInfo>

    fun readDataPackFiles(
        selectedDataPackFilePaths: Set<DataPackFilePath>? = null,
        block: (DataPackFilePath, DataPackFileBytes) -> Unit,
    )

    fun <T> readDataPackFile(
        dataPackFilePath: DataPackFilePath,
        block: (Source) -> T,
    ): T
}

internal expect fun openDataPackZipReader(
    fileSystem: FileSystem,
    dataPackZipPath: Path,
): DataPackZipReader
