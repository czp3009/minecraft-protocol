package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.LevelDat
import com.hiczp.minecraft.world.format.datapack.*
import okio.BufferedSource
import okio.FileSystem
import okio.Path

enum class DataPackContainerKind {
    DIRECTORY,
    ZIP,
}

data class DataPackFileInfo(
    val dataPackFilePath: DataPackFilePath,
    val sizeInBytes: Long,
)

/** Paths and declared sizes found while inspecting one on-disk data pack. */
class DataPackInspection(
    val dataPackId: DataPackId,
    val dataPackContainerPath: Path,
    val dataPackContainerKind: DataPackContainerKind,
    dataPackFileInfos: List<DataPackFileInfo>,
) {
    val dataPackFileInfos: List<DataPackFileInfo> = dataPackFileInfos.toList()
    val totalSizeInBytes: ULong = this.dataPackFileInfos.fold(0uL) { totalSizeInBytes, dataPackFileInfo ->
        totalSizeInBytes + dataPackFileInfo.sizeInBytes.toULong()
    }

    init {
        require(this.dataPackFileInfos.all { it.sizeInBytes >= 0L }) {
            "Inspected data-pack file sizes must be non-negative"
        }
        require(
            this.dataPackFileInfos.map(DataPackFileInfo::dataPackFilePath).distinct().size ==
                    this.dataPackFileInfos.size,
        ) {
            "A data-pack inspection cannot contain duplicate paths"
        }
    }

    fun dataPackFileInfo(dataPackFilePath: DataPackFilePath): DataPackFileInfo? =
        dataPackFileInfos.singleOrNull { it.dataPackFilePath == dataPackFilePath }
}

/**
 * Direct directory/ZIP reader for data packs stored below one world directory.
 *
 * On-disk data-pack containers are immutable inputs for as long as callers use this reader. The reader therefore adds
 * no data-pack read lock or mutation coordinator. Every returned archive and parsed data pack is detached from the
 * filesystem. The reader imposes no file-count, individual-size, or aggregate-size policy; callers can inspect sizes
 * before choosing whether to read or parse.
 */
class WorldDataPackReader(
    val fileSystem: FileSystem,
    val dataPacksDirectory: Path,
    val dataPackFormat: DataPackFormat = DataPackFormat(),
) {
    constructor(
        minecraftWorldPaths: MinecraftWorldPaths,
        dataPackFormat: DataPackFormat = DataPackFormat(),
    ) : this(systemFileSystem, minecraftWorldPaths.dataPacksDirectory, dataPackFormat)

    fun readDataPack(
        dataPackContainerPath: Path,
        dataPackId: DataPackId = DataPackId(dataPackContainerPath.name),
    ): DataPack = when (dataPackContainerKind(dataPackContainerPath)) {
        DataPackContainerKind.DIRECTORY -> readDataPack(inspectDataPack(dataPackContainerPath, dataPackId))
        DataPackContainerKind.ZIP -> readZipDataPack(dataPackContainerPath, dataPackId)
    }

    fun readDataPack(dataPackInspection: DataPackInspection): DataPack =
        when (dataPackInspection.dataPackContainerKind) {
            DataPackContainerKind.DIRECTORY -> dataPackFormat.decode(
                dataPackInspection.dataPackId,
                readDirectoryDataPackFiles(dataPackInspection),
            )

            DataPackContainerKind.ZIP -> readZipDataPack(
                dataPackInspection.dataPackContainerPath,
                dataPackInspection.dataPackId,
                dataPackInspection.dataPackFileInfos.mapTo(
                    linkedSetOf(),
                    DataPackFileInfo::dataPackFilePath,
                ),
            )
        }

    fun readDataPackArchive(
        dataPackContainerPath: Path,
        dataPackId: DataPackId = DataPackId(dataPackContainerPath.name),
    ): DataPackArchive = when (dataPackContainerKind(dataPackContainerPath)) {
        DataPackContainerKind.DIRECTORY -> readDataPackArchive(inspectDataPack(dataPackContainerPath, dataPackId))
        DataPackContainerKind.ZIP -> readZipDataPackArchive(dataPackContainerPath, dataPackId)
    }

    fun inspectDataPack(
        dataPackContainerPath: Path,
        dataPackId: DataPackId = DataPackId(dataPackContainerPath.name),
    ): DataPackInspection = when (dataPackContainerKind(dataPackContainerPath)) {
        DataPackContainerKind.DIRECTORY -> inspectDirectoryDataPack(
            dataPackRoot = dataPackContainerPath,
            dataPackId = dataPackId,
            dataPackContainerPath = dataPackContainerPath,
        )

        DataPackContainerKind.ZIP -> DataPackInspection(
            dataPackId = dataPackId,
            dataPackContainerPath = dataPackContainerPath,
            dataPackContainerKind = DataPackContainerKind.ZIP,
            dataPackFileInfos = openDataPackZipReader(fileSystem, dataPackContainerPath).inspectDataPackFileInfos(),
        )
    }

    fun inspectEnabledFileDataPacks(
        enabledDataPackIds: List<DataPackId>,
    ): List<DataPackInspection> = enabledDataPackIds.mapNotNull { dataPackId ->
        val dataPackFileName = dataPackFileNameOrNull(dataPackId) ?: return@mapNotNull null
        inspectDataPack(dataPacksDirectory / dataPackFileName, dataPackId)
    }

    fun inspectEnabledFileDataPacks(levelDat: LevelDat): List<DataPackInspection> =
        inspectEnabledFileDataPacks(
            levelDat.data.dataPackSelection.enabledDataPackReferences.map(::DataPackId),
        )

    fun readEnabledDataPacks(levelDat: LevelDat): WorldDataPackLoadResult {
        val levelData = levelDat.data
        return readEnabledDataPacks(
            enabledDataPackIds = levelData.dataPackSelection.enabledDataPackReferences.map(::DataPackId),
            disabledDataPackIds = levelData.dataPackSelection.disabledDataPackReferences.map(::DataPackId),
            enabledFeatureFlags = levelData.enabledFeatures.toSet(),
            removedFeatureFlags = levelData.removedFeatures.toSet(),
        )
    }

    fun readEnabledDataPacks(enabledDataPackIds: List<DataPackId>): WorldDataPackLoadResult =
        readEnabledDataPacks(enabledDataPackIds, emptyList(), emptySet(), emptySet())

    private fun readEnabledDataPacks(
        enabledDataPackIds: List<DataPackId>,
        disabledDataPackIds: List<DataPackId>,
        enabledFeatureFlags: Set<String>,
        removedFeatureFlags: Set<String>,
    ): WorldDataPackLoadResult {
        val dataPacks = mutableListOf<DataPack>()
        enabledDataPackIds.forEach { dataPackId ->
            val dataPackFileName = dataPackFileNameOrNull(dataPackId) ?: return@forEach
            dataPacks += readDataPack(dataPacksDirectory / dataPackFileName, dataPackId)
        }
        return WorldDataPackLoadResult(
            enabledDataPackIds = enabledDataPackIds,
            disabledDataPackIds = disabledDataPackIds,
            enabledFeatureFlags = enabledFeatureFlags,
            removedFeatureFlags = removedFeatureFlags,
            loadedDataPacks = dataPacks,
        )
    }

    fun readDataPackArchive(dataPackInspection: DataPackInspection): DataPackArchive =
        when (dataPackInspection.dataPackContainerKind) {
            DataPackContainerKind.DIRECTORY -> DataPackArchive(
                dataPackInspection.dataPackId,
                readDirectoryDataPackFiles(dataPackInspection).toMap(),
            )

            DataPackContainerKind.ZIP -> readZipDataPackArchive(
                dataPackInspection.dataPackContainerPath,
                dataPackInspection.dataPackId,
                dataPackInspection.dataPackFileInfos.mapTo(
                    linkedSetOf(),
                    DataPackFileInfo::dataPackFilePath,
                ),
            )
        }

    /** Reads one caller-selected file from an inspected data-pack container. */
    fun readDataPackFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = readDataPackFile(dataPackInspection, dataPackFilePath) { source ->
        DataPackFileBytes(source.readByteArray())
    }

    /** Lends one caller-selected file source for the duration of [block]. */
    fun <T> readDataPackFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T {
        requireNotNull(dataPackInspection.dataPackFileInfo(dataPackFilePath)) {
            "$dataPackFilePath was not present in the inspected data pack ${dataPackInspection.dataPackId}"
        }
        return when (dataPackInspection.dataPackContainerKind) {
            DataPackContainerKind.DIRECTORY -> {
                val absoluteDataPackFilePath = dataPackFilePath.resolveBelow(dataPackInspection.dataPackContainerPath)
                fileSystem.readFile(absoluteDataPackFilePath) { bufferedSource, _ -> block(bufferedSource) }
            }

            DataPackContainerKind.ZIP -> openDataPackZipReader(
                fileSystem,
                dataPackInspection.dataPackContainerPath,
            ).readDataPackFile(dataPackFilePath, block)
        }
    }

    private fun inspectDirectoryDataPack(
        dataPackRoot: Path,
        dataPackId: DataPackId,
        dataPackContainerPath: Path,
    ): DataPackInspection {
        val dataPackFileInfos = mutableListOf<DataPackFileInfo>()
        fileSystem.listRecursively(dataPackRoot, followSymlinks = false).forEach { absoluteDataPackFilePath ->
            val fileMetadata = fileSystem.metadataOrNull(absoluteDataPackFilePath) ?: return@forEach
            if (!fileMetadata.isRegularFile) return@forEach
            val sizeInBytes = fileMetadata.size
                ?: throw WorldIOException("Data-pack file has no size: $absoluteDataPackFilePath")
            if (sizeInBytes < 0L) {
                throw WorldIOException("Data-pack file has a negative size: $absoluteDataPackFilePath")
            }
            val relativeDataPackFilePath = DataPackFilePath(
                absoluteDataPackFilePath.relativeTo(dataPackRoot).segments.joinToString("/"),
            )
            dataPackFileInfos += DataPackFileInfo(relativeDataPackFilePath, sizeInBytes)
        }
        if (dataPackFileInfos.isEmpty()) throw WorldIOException("Data pack $dataPackId has no regular files")
        return DataPackInspection(
            dataPackId = dataPackId,
            dataPackContainerPath = dataPackContainerPath,
            dataPackContainerKind = DataPackContainerKind.DIRECTORY,
            dataPackFileInfos = dataPackFileInfos.sortedBy { it.dataPackFilePath.value },
        )
    }

    private fun readDirectoryDataPackFiles(
        dataPackInspection: DataPackInspection,
    ): Sequence<Pair<DataPackFilePath, DataPackFileBytes>> = sequence {
        dataPackInspection.dataPackFileInfos.forEach { dataPackFileInfo ->
            val dataPackFilePath = dataPackFileInfo.dataPackFilePath
            yield(
                dataPackFilePath to DataPackFileBytes(
                    fileSystem.readFileBytes(
                        dataPackFilePath.resolveBelow(dataPackInspection.dataPackContainerPath),
                    ),
                ),
            )
        }
    }

    private fun readZipDataPack(
        dataPackZipPath: Path,
        dataPackId: DataPackId,
        selectedDataPackFilePaths: Set<DataPackFilePath>? = null,
    ): DataPack {
        val dataPackDecoder = dataPackFormat.createDecoder(dataPackId)
        openDataPackZipReader(fileSystem, dataPackZipPath).readDataPackFiles(
            selectedDataPackFilePaths,
        ) { dataPackFilePath, dataPackFileBytes ->
            dataPackDecoder.accept(dataPackFilePath, dataPackFileBytes)
        }
        return dataPackDecoder.finish()
    }

    private fun readZipDataPackArchive(
        dataPackZipPath: Path,
        dataPackId: DataPackId,
        selectedDataPackFilePaths: Set<DataPackFilePath>? = null,
    ): DataPackArchive {
        val dataPackFileBytesByPath = linkedMapOf<DataPackFilePath, DataPackFileBytes>()
        openDataPackZipReader(fileSystem, dataPackZipPath).readDataPackFiles(
            selectedDataPackFilePaths,
        ) { dataPackFilePath, dataPackFileBytes ->
            dataPackFileBytesByPath[dataPackFilePath] = dataPackFileBytes
        }
        return DataPackArchive(dataPackId, dataPackFileBytesByPath)
    }

    private fun dataPackContainerKind(dataPackContainerPath: Path): DataPackContainerKind {
        val fileMetadata = fileSystem.metadataOrNull(dataPackContainerPath)
            ?: throw WorldIOException("Data-pack path does not exist: $dataPackContainerPath")
        return when {
            fileMetadata.isDirectory -> DataPackContainerKind.DIRECTORY
            fileMetadata.isRegularFile && dataPackContainerPath.name.endsWith(".zip", ignoreCase = true) ->
                DataPackContainerKind.ZIP

            else -> throw WorldIOException(
                "Data-pack path is neither a directory nor a ZIP file: $dataPackContainerPath",
            )
        }
    }

    private fun DataPackFilePath.resolveBelow(root: Path): Path =
        segments.fold(root) { parent, segment -> parent / segment }

    private fun dataPackFileNameOrNull(dataPackId: DataPackId): String? {
        if (!dataPackId.value.startsWith(FILE_REFERENCE_PREFIX)) return null
        val dataPackFileName = dataPackId.value.removePrefix(FILE_REFERENCE_PREFIX)
        if (
            dataPackFileName.isEmpty() || '/' in dataPackFileName || '\\' in dataPackFileName ||
            dataPackFileName == "." || dataPackFileName == ".."
        ) {
            throw WorldIOException("Invalid world data-pack ID: $dataPackId")
        }
        return dataPackFileName
    }

    companion object {
        private const val FILE_REFERENCE_PREFIX = "file/"
    }
}
