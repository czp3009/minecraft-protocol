package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.distribution.metadata.*
import io.ktor.client.*
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
    httpClient: HttpClient,
    private val fileSystem: FileSystem,
    private val launcherStore: LauncherStore,
    private val launcherPlatform: LauncherPlatform,
) {
    private val minecraftDistributionMetadataApi = MinecraftDistributionMetadataApiClient(httpClient)
    private val resourceDownloader = ResourceDownloader(minecraftDistributionMetadataApi, fileSystem)
    private val progressMutex = Mutex()
    private val _progress = MutableStateFlow(InstallProgress())
    val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    suspend fun loadManifest(): MinecraftVersionManifest = minecraftDistributionMetadataApi.versionManifest()

    suspend fun loadInstalled(): InstalledState = launcherStore.reconcileInstalled()

    suspend fun install(
        minecraftVersionReference: MinecraftVersionReference,
        onDownloadsStarted: (InstalledState) -> Unit = {},
    ): CompletedInstallation {
        val preparedInstallation = prepareInstallation(minecraftVersionReference)
        val downloadingState = launcherStore.updateInstalled { installedState ->
            installedState.copy(
                installations = installedState.installations.filterNot {
                    it.versionId == minecraftVersionReference.id
                },
            )
        }
        onDownloadsStarted(downloadingState)
        downloadAll(preparedInstallation.downloads, preparedInstallation.gameRoot)

        val completedState = launcherStore.updateInstalled { installedState ->
            val installedVersion = InstalledVersion(preparedInstallation.minecraftVersionMetadata.id)
            installedState.copy(installations = installedState.installations.filterNot { it == installedVersion } + installedVersion)
        }
        return CompletedInstallation(preparedInstallation.minecraftVersionMetadata, completedState)
    }

    private suspend fun prepareInstallation(minecraftVersionReference: MinecraftVersionReference): PreparedInstallation {
        val minecraftVersionMetadata = loadVersionMetadata(minecraftVersionReference)
        val installPlan = MetadataPlanner.createInstallPlan(minecraftVersionMetadata, launcherPlatform)
        val gameRoot = launcherStore.gameRoot(minecraftVersionReference.id)
        fileSystem.createDirectories(gameRoot)
        fileSystem.createDirectories(gameRoot / installPlan.nativeDirectory)

        val minecraftAssetIndex = minecraftDistributionMetadataApi.assetIndex(minecraftVersionMetadata.assetIndex.url)
        writeJsonAtomically(
            fileSystem,
            resolveSafe(gameRoot, installPlan.assetIndexPath),
            launcherJson.encodeToString(minecraftAssetIndex),
        )
        val downloads = (installPlan.downloads + MetadataPlanner.createAssetDownloads(minecraftAssetIndex))
            .distinctBy(DownloadSpec::relativePath)
        progressMutex.withLock {
            _progress.value = InstallProgress(totalFiles = downloads.size)
        }
        return PreparedInstallation(minecraftVersionMetadata, gameRoot, downloads)
    }

    suspend fun validateInstallation(minecraftVersionMetadata: MinecraftVersionMetadata): InstallPlan {
        val installPlan = MetadataPlanner.createInstallPlan(minecraftVersionMetadata, launcherPlatform)
        val gameRoot = launcherStore.gameRoot(minecraftVersionMetadata.id)
        val minecraftAssetIndex = launcherJson.decodeFromString<MinecraftAssetIndex>(
            fileSystem.read(resolveSafe(gameRoot, installPlan.assetIndexPath)) { readUtf8() },
        )
        val required = installPlan.downloads + MetadataPlanner.createAssetDownloads(minecraftAssetIndex)
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
                    it.versionId == versionId
                },
            )
        }
    }

    suspend fun loadVersionMetadata(minecraftVersionReference: MinecraftVersionReference): MinecraftVersionMetadata =
        minecraftDistributionMetadataApi.versionMetadata(minecraftVersionReference.url)
            .copy(id = minecraftVersionReference.id)

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
    val minecraftVersionMetadata: MinecraftVersionMetadata,
    val gameRoot: Path,
    val downloads: List<DownloadSpec>,
)

internal data class CompletedInstallation(
    val minecraftVersionMetadata: MinecraftVersionMetadata,
    val installedState: InstalledState,
)

private const val DOWNLOAD_CONCURRENCY = 16
private const val DOWNLOAD_RETRY_DELAY_MILLIS = 1_000L
