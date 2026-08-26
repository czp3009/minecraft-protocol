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
    private val mojangApi: MojangApi,
    private val fileSystem: FileSystem,
    private val launcherStore: LauncherStore,
    private val launcherPlatform: LauncherPlatform,
) {
    private val resourceDownloader = ResourceDownloader(mojangApi, fileSystem)
    private val progressMutex = Mutex()
    private val _progress = MutableStateFlow(InstallProgress())
    val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    suspend fun loadManifest(): VersionManifest = mojangApi.versionManifest()

    suspend fun loadInstalled(): InstalledState = launcherStore.reconcileInstalled(launcherPlatform)

    suspend fun install(
        versionEntry: VersionEntry,
        onDownloadsStarted: (InstalledState) -> Unit = {},
    ): CompletedInstallation {
        val preparedInstallation = prepareInstallation(versionEntry)
        val downloadingState = launcherStore.updateInstalled { installedState ->
            installedState.copy(
                installations = installedState.installations.filterNot {
                    it.versionId == versionEntry.id && it.platformKey == launcherPlatform.platformKey
                },
            )
        }
        onDownloadsStarted(downloadingState)
        downloadAll(preparedInstallation.downloads, preparedInstallation.gameRoot)

        val completedState = launcherStore.updateInstalled { installedState ->
            val installedVersion =
                InstalledVersion(preparedInstallation.versionMetadata.id, launcherPlatform.platformKey)
            installedState.copy(installations = installedState.installations.filterNot { it == installedVersion } + installedVersion)
        }
        return CompletedInstallation(preparedInstallation.versionMetadata, completedState)
    }

    private suspend fun prepareInstallation(versionEntry: VersionEntry): PreparedInstallation {
        val versionMetadata = loadVersionMetadata(versionEntry)
        val installPlan = MetadataPlanner.createInstallPlan(versionMetadata, launcherPlatform)
        val gameRoot = launcherStore.gameRoot(versionEntry.id)
        fileSystem.createDirectories(gameRoot)
        fileSystem.createDirectories(gameRoot / installPlan.nativeDirectory)

        resourceDownloader.download(installPlan.assetIndex, resolveSafe(gameRoot, installPlan.assetIndex.relativePath))

        val assetIndex = launcherJson.decodeFromString<AssetIndex>(
            fileSystem.read(resolveSafe(gameRoot, installPlan.assetIndex.relativePath)) { readUtf8() },
        )
        val contentDownloads =
            installPlan.downloads.filterNot { it.relativePath == installPlan.assetIndex.relativePath }
        val downloads = (contentDownloads + MetadataPlanner.createAssetDownloads(assetIndex))
            .distinctBy(DownloadSpec::relativePath)
        progressMutex.withLock {
            _progress.value = InstallProgress(totalFiles = downloads.size)
        }
        return PreparedInstallation(versionMetadata, gameRoot, downloads)
    }

    suspend fun validateInstallation(versionMetadata: VersionMetadata): InstallPlan {
        val installPlan = MetadataPlanner.createInstallPlan(versionMetadata, launcherPlatform)
        val gameRoot = launcherStore.gameRoot(versionMetadata.id)
        val assetIndex = launcherJson.decodeFromString<AssetIndex>(
            fileSystem.read(resolveSafe(gameRoot, installPlan.assetIndex.relativePath)) { readUtf8() },
        )
        val required = installPlan.downloads + MetadataPlanner.createAssetDownloads(assetIndex)
        required.forEach { downloadSpec ->
            require(resourceDownloader.isValid(resolveSafe(gameRoot, downloadSpec.relativePath), downloadSpec)) {
                "Installed resource is missing or corrupt: ${downloadSpec.relativePath}"
            }
        }
        return installPlan
    }

    suspend fun delete(versionId: String) {
        val gameRoot = launcherStore.gameRoot(versionId)
        if (fileSystem.exists(gameRoot)) fileSystem.deleteRecursively(gameRoot)
        launcherStore.updateInstalled { installedState ->
            installedState.copy(
                installations = installedState.installations.filterNot {
                    it.versionId == versionId && it.platformKey == launcherPlatform.platformKey
                },
            )
        }
    }

    suspend fun loadVersionMetadata(versionEntry: VersionEntry): VersionMetadata {
        val versionMetadata = mojangApi.versionMetadata(versionEntry.url)
        require(versionMetadata.id == versionEntry.id) { "Version metadata ID does not match ${versionEntry.id}" }
        return versionMetadata
    }

    private suspend fun downloadAll(specs: List<DownloadSpec>, gameRoot: Path) = coroutineScope {
        val semaphore = Semaphore(DOWNLOAD_CONCURRENCY)
        specs.map { downloadSpec ->
            async {
                val target = resolveSafe(gameRoot, downloadSpec.relativePath)
                while (true) {
                    try {
                        semaphore.withPermit {
                            resourceDownloader.download(downloadSpec, target)
                        }
                        break
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        delay(DOWNLOAD_RETRY_DELAY_MILLIS)
                    }
                }
                progressMutex.withLock {
                    _progress.value = _progress.value.copy(
                        completedFiles = _progress.value.completedFiles + 1,
                    )
                }
            }
        }.awaitAll()
    }
}

private data class PreparedInstallation(
    val versionMetadata: VersionMetadata,
    val gameRoot: Path,
    val downloads: List<DownloadSpec>,
)

internal data class CompletedInstallation(
    val versionMetadata: VersionMetadata,
    val installedState: InstalledState,
)

private const val DOWNLOAD_CONCURRENCY = 16
private const val DOWNLOAD_RETRY_DELAY_MILLIS = 1_000L
