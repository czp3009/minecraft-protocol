package com.hiczp.minecraft.demo.launcher

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path

internal class InstallationService(
    private val api: MojangApi,
    private val fileSystem: FileSystem,
    private val store: LauncherStore,
    private val platform: LauncherPlatform,
) {
    private val downloader = ResourceDownloader(api, fileSystem)
    private val progressMutex = Mutex()
    private val _progress = MutableStateFlow(InstallProgress())
    val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    suspend fun loadManifest(): VersionManifest = api.versionManifest()

    suspend fun loadInstalled(): InstalledState = store.reconcileInstalled(platform)

    suspend fun install(
        entry: VersionEntry,
        onDownloadsStarted: (InstalledState) -> Unit = {},
    ): CompletedInstallation {
        val prepared = prepareInstallation(entry)
        val downloadingState = store.updateInstalled { installed ->
            installed.copy(
                installations = installed.installations.filterNot {
                    it.versionId == entry.id && it.platformKey == platform.platformKey
                },
            )
        }
        onDownloadsStarted(downloadingState)
        downloadAll(prepared.downloads, prepared.gameRoot)

        val completedState = store.updateInstalled { installed ->
            val record = InstalledVersion(prepared.metadata.id, platform.platformKey)
            installed.copy(installations = installed.installations.filterNot { it == record } + record)
        }
        return CompletedInstallation(prepared.metadata, completedState)
    }

    private suspend fun prepareInstallation(entry: VersionEntry): PreparedInstallation {
        val metadata = loadVersionMetadata(entry)
        val plan = MetadataPlanner.createInstallPlan(metadata, platform)
        val gameRoot = store.gameRoot(entry.id)
        fileSystem.createDirectories(gameRoot)
        fileSystem.createDirectories(gameRoot / plan.nativeDirectory)

        downloader.download(plan.assetIndex, resolveSafe(gameRoot, plan.assetIndex.relativePath))

        val assetIndex = launcherJson.decodeFromString<AssetIndex>(
            fileSystem.read(resolveSafe(gameRoot, plan.assetIndex.relativePath)) { readUtf8() },
        )
        val contentDownloads = plan.downloads.filterNot { it.relativePath == plan.assetIndex.relativePath }
        val downloads = (contentDownloads + MetadataPlanner.createAssetDownloads(assetIndex))
            .distinctBy(DownloadSpec::relativePath)
        resetProgress(downloads.size)
        return PreparedInstallation(metadata, gameRoot, downloads)
    }

    suspend fun validateInstallation(metadata: VersionMetadata): InstallPlan {
        val plan = MetadataPlanner.createInstallPlan(metadata, platform)
        val gameRoot = store.gameRoot(metadata.id)
        val assetIndex = launcherJson.decodeFromString<AssetIndex>(
            fileSystem.read(resolveSafe(gameRoot, plan.assetIndex.relativePath)) { readUtf8() },
        )
        val required = plan.downloads + MetadataPlanner.createAssetDownloads(assetIndex)
        required.forEach { spec ->
            require(downloader.isValid(resolveSafe(gameRoot, spec.relativePath), spec)) {
                "Installed resource is missing or corrupt: ${spec.relativePath}"
            }
        }
        return plan
    }

    suspend fun delete(versionId: String) {
        val gameRoot = store.gameRoot(versionId)
        if (fileSystem.exists(gameRoot)) fileSystem.deleteRecursively(gameRoot)
        store.updateInstalled { installed ->
            installed.copy(
                installations = installed.installations.filterNot {
                    it.versionId == versionId && it.platformKey == platform.platformKey
                },
            )
        }
    }

    suspend fun loadVersionMetadata(entry: VersionEntry): VersionMetadata {
        val metadata = api.versionMetadata(entry.url)
        require(metadata.id == entry.id) { "Version metadata ID does not match ${entry.id}" }
        return metadata
    }

    private suspend fun downloadAll(specs: List<DownloadSpec>, gameRoot: Path) = coroutineScope {
        val semaphore = Semaphore(DOWNLOAD_CONCURRENCY)
        specs.map { spec ->
            async {
                val target = resolveSafe(gameRoot, spec.relativePath)
                while (true) {
                    try {
                        semaphore.withPermit {
                            downloader.download(spec, target)
                        }
                        break
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        delay(DOWNLOAD_RETRY_DELAY_MILLIS)
                    }
                }
                markCompleted()
            }
        }.awaitAll()
    }

    private suspend fun resetProgress(totalFiles: Int) = progressMutex.withLock {
        _progress.value = InstallProgress(totalFiles = totalFiles)
    }

    private suspend fun markCompleted() = progressMutex.withLock {
        _progress.value = _progress.value.copy(
            completedFiles = _progress.value.completedFiles + 1,
        )
    }
}

private data class PreparedInstallation(
    val metadata: VersionMetadata,
    val gameRoot: Path,
    val downloads: List<DownloadSpec>,
)

internal data class CompletedInstallation(
    val metadata: VersionMetadata,
    val installed: InstalledState,
)

private const val DOWNLOAD_CONCURRENCY = 16
private const val DOWNLOAD_RETRY_DELAY_MILLIS = 1_000L
