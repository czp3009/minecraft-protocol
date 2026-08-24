package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.LevelDat
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import kotlinx.io.Source as KotlinxSource

enum class DataPackContainerKind {
    DIRECTORY,
    ZIP,
}

data class DataPackFileInfo(
    val path: DataPackPath,
    val size: Long,
)

/** Detached central-directory/directory listing. It is advisory and does not lock files against later changes. */
class DataPackInspection(
    val id: DataPackId,
    val path: Path,
    val containerKind: DataPackContainerKind,
    files: List<DataPackFileInfo>,
) {
    val files: List<DataPackFileInfo> = files.toList()
    val totalSize: ULong = this.files.fold(0uL) { size, file -> size + file.size.toULong() }

    init {
        require(this.files.all { it.size >= 0L }) { "Inspected data-pack file sizes must be non-negative" }
        require(this.files.map(DataPackFileInfo::path).distinct().size == this.files.size) {
            "A data-pack inspection cannot contain duplicate paths"
        }
    }

    fun file(path: DataPackPath): DataPackFileInfo? = files.singleOrNull { it.path == path }
}

/** Result of resolving the `DataPacks.Enabled` list against a world's `datapacks` directory. */
class LoadedWorldDataPacks(
    val enabledReferences: List<String>,
    packs: List<DataPack>,
    unresolvedReferences: List<String>,
) {
    val packs: List<DataPack> = packs.toList()
    val unresolvedReferences: List<String> = unresolvedReferences.toList()
    val stack: DataPackStack = DataPackStack(this.packs)
}

/**
 * Non-locking directory/ZIP reader for data packs stored below one world directory.
 *
 * The official server treats enabled packs as immutable for a running load. This store therefore takes neither the
 * world `session.lock` nor this library's logical-file locks. A concurrent external modification may surface as an
 * ordinary I/O or format failure. Every returned archive and parsed pack is detached from the filesystem. The reader
 * imposes no file-count, individual-size, or aggregate-size policy; callers can inspect sizes before choosing whether
 * to read or parse.
 */
class WorldDataPackStore(
    val fileSystem: FileSystem,
    val directory: Path,
    val format: DataPackFormat = DataPackFormat(),
) {
    constructor(
        paths: MinecraftWorldPaths,
        format: DataPackFormat = DataPackFormat(),
    ) : this(systemFileSystem, paths.dataPacks, format)

    fun readPack(path: Path, id: DataPackId = DataPackId(path.name)): DataPack = when (containerKind(path)) {
        DataPackContainerKind.DIRECTORY -> readPack(inspectPack(path, id))
        DataPackContainerKind.ZIP -> readZipPack(path, id)
    }

    fun readPack(inspection: DataPackInspection): DataPack = when (inspection.containerKind) {
        DataPackContainerKind.DIRECTORY -> format.decode(inspection.id, readDirectoryFiles(inspection))
        DataPackContainerKind.ZIP -> readZipPack(
            inspection.path,
            inspection.id,
            inspection.files.mapTo(linkedSetOf(), DataPackFileInfo::path),
        )
    }

    fun readArchive(path: Path, id: DataPackId = DataPackId(path.name)): DataPackArchive = when (containerKind(path)) {
        DataPackContainerKind.DIRECTORY -> readArchive(inspectPack(path, id))
        DataPackContainerKind.ZIP -> readZipArchive(path, id)
    }

    fun inspectPack(path: Path, id: DataPackId = DataPackId(path.name)): DataPackInspection {
        return when (containerKind(path)) {
            DataPackContainerKind.DIRECTORY ->
                inspect(fileSystem, path, id, path, DataPackContainerKind.DIRECTORY)

            DataPackContainerKind.ZIP -> DataPackInspection(
                id = id,
                path = path,
                containerKind = DataPackContainerKind.ZIP,
                files = openDataPackZip(fileSystem, path).inspect(),
            )
        }
    }

    fun inspectEnabled(enabledReferences: List<String>): List<DataPackInspection> =
        enabledReferences.mapNotNull { reference ->
            val fileName = fileNameOrNull(reference) ?: return@mapNotNull null
            inspectPack(directory / fileName, DataPackId(reference))
        }

    fun inspectEnabled(levelData: LevelDat): List<DataPackInspection> =
        inspectEnabled(levelData.data.dataPacks.enabled)

    fun readEnabled(levelData: LevelDat): LoadedWorldDataPacks = readEnabled(levelData.data.dataPacks.enabled)

    fun readEnabled(enabledReferences: List<String>): LoadedWorldDataPacks {
        val packs = mutableListOf<DataPack>()
        val unresolved = mutableListOf<String>()
        enabledReferences.forEach { reference ->
            val fileName = fileNameOrNull(reference)
            if (fileName == null) {
                unresolved += reference
            } else {
                packs += readPack(directory / fileName, DataPackId(reference))
            }
        }
        return LoadedWorldDataPacks(enabledReferences.toList(), packs, unresolved)
    }

    fun readArchive(inspection: DataPackInspection): DataPackArchive {
        return when (inspection.containerKind) {
            DataPackContainerKind.DIRECTORY -> DataPackArchive(inspection.id, readDirectoryFiles(inspection).toMap())
            DataPackContainerKind.ZIP -> readZipArchive(
                inspection.path,
                inspection.id,
                inspection.files.mapTo(linkedSetOf(), DataPackFileInfo::path),
            )
        }
    }

    /** Reads one caller-selected file after inspection; the listing remains advisory if the container changes. */
    fun readFile(
        inspection: DataPackInspection,
        path: DataPackPath,
    ): DataPackBinary = readFile(inspection, path) { source -> DataPackBinary(source.readByteArray()) }

    /** Lends one caller-selected file source for the duration of [block]. */
    fun <T> readFile(
        inspection: DataPackInspection,
        path: DataPackPath,
        block: (KotlinxSource) -> T,
    ): T {
        requireNotNull(inspection.file(path)) { "$path was not present in the inspected data pack ${inspection.id}" }
        return when (inspection.containerKind) {
            DataPackContainerKind.DIRECTORY -> {
                val filePath = path.resolveBelow(inspection.path)
                fileSystem.readFile(filePath) { source, _ ->
                    withOkioIoExceptions("Cannot read data-pack file $filePath") {
                        val converted = source.asKotlinxIoRawSource().buffered()
                        val value = block(converted)
                        if (!converted.exhausted()) {
                            throw WorldIOException("Data-pack file $filePath was not fully consumed")
                        }
                        value
                    }
                }
            }

            DataPackContainerKind.ZIP -> openDataPackZip(fileSystem, inspection.path).readFile(path, block)
        }
    }

    private fun inspect(
        packFileSystem: FileSystem,
        root: Path,
        id: DataPackId,
        containerPath: Path,
        kind: DataPackContainerKind,
    ): DataPackInspection {
        val files = mutableListOf<DataPackFileInfo>()
        packFileSystem.listRecursively(root, followSymlinks = false).forEach { path ->
            val metadata = packFileSystem.metadataOrNull(path) ?: return@forEach
            if (!metadata.isRegularFile) return@forEach
            val size = metadata.size ?: throw WorldIOException("Data-pack file has no size: $path")
            if (size < 0L) throw WorldIOException("Data-pack file has a negative size: $path")
            val relativePath = path.relativeTo(root).segments.joinToString("/")
            val packPath = DataPackPath(relativePath)
            files += DataPackFileInfo(packPath, size)
        }
        if (files.isEmpty()) throw WorldIOException("Data pack $id has no regular files")
        return DataPackInspection(id, containerPath, kind, files.sortedBy { it.path.value })
    }

    private fun readDirectoryFiles(
        inspection: DataPackInspection,
    ): Sequence<Pair<DataPackPath, DataPackBinary>> = sequence {
        inspection.files.forEach { file ->
            yield(file.path to DataPackBinary(fileSystem.readFileBytes(file.path.resolveBelow(inspection.path))))
        }
    }

    private fun readZipPack(
        path: Path,
        id: DataPackId,
        selectedPaths: Set<DataPackPath>? = null,
    ): DataPack {
        val decoder = format.decoder(id)
        openDataPackZip(fileSystem, path).readFiles(selectedPaths) { filePath, bytes ->
            decoder.accept(filePath, bytes)
        }
        return decoder.finish()
    }

    private fun readZipArchive(
        path: Path,
        id: DataPackId,
        selectedPaths: Set<DataPackPath>? = null,
    ): DataPackArchive {
        val files = linkedMapOf<DataPackPath, DataPackBinary>()
        openDataPackZip(fileSystem, path).readFiles(selectedPaths) { filePath, bytes ->
            files[filePath] = bytes
        }
        return DataPackArchive(id, files)
    }

    private fun containerKind(path: Path): DataPackContainerKind {
        val metadata = fileSystem.metadataOrNull(path)
            ?: throw WorldIOException("Data-pack path does not exist: $path")
        return when {
            metadata.isDirectory -> DataPackContainerKind.DIRECTORY
            metadata.isRegularFile && path.name.endsWith(".zip", ignoreCase = true) -> DataPackContainerKind.ZIP
            else -> throw WorldIOException("Data-pack path is neither a directory nor a ZIP file: $path")
        }
    }

    private fun DataPackPath.resolveBelow(root: Path): Path =
        segments.fold(root) { parent, segment -> parent / segment }

    private fun fileNameOrNull(reference: String): String? {
        if (!reference.startsWith(FILE_REFERENCE_PREFIX)) return null
        val fileName = reference.removePrefix(FILE_REFERENCE_PREFIX)
        if (fileName.isEmpty() || '/' in fileName || '\\' in fileName || fileName == "." || fileName == "..") {
            throw WorldIOException("Invalid world data-pack reference: $reference")
        }
        return fileName
    }

    companion object {
        private const val FILE_REFERENCE_PREFIX = "file/"
    }
}
