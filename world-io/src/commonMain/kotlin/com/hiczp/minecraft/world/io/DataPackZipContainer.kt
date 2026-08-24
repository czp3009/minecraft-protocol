package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackBinary
import com.hiczp.minecraft.world.format.datapack.DataPackPath
import kotlinx.io.Source
import okio.FileSystem
import okio.Path

/** Platform ZIP adapter. Okio owns random-access ZIP reads where available; Node uses adm-zip's central directory. */
internal interface DataPackZipContainer {
    fun inspect(): List<DataPackFileInfo>

    fun readFiles(
        selectedPaths: Set<DataPackPath>? = null,
        block: (DataPackPath, DataPackBinary) -> Unit,
    )

    fun <T> readFile(
        path: DataPackPath,
        block: (Source) -> T,
    ): T
}

internal expect fun openDataPackZip(
    fileSystem: FileSystem,
    path: Path,
): DataPackZipContainer
