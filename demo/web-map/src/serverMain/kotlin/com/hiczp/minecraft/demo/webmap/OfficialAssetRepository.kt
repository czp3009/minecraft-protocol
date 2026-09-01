package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaRegistryData
import com.hiczp.minecraft.protocol.model.type.Identifier
import io.github.oshai.kotlinlogging.KLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.openZip
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

data class LoadedOfficialAssets(
    val revision: String,
    val resources: Map<String, ByteArray>,
    val blockAssets: BlockAssetIndex,
) {
    val byteCount: Long = resources.values.sumOf { bytes -> bytes.size.toLong() }
}

class OfficialAssetRepository(
    minecraftVersion: String,
    parentCoroutineScope: CoroutineScope,
    private val logger: KLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val httpClient: HttpClient = createOfficialAssetHttpClient(),
) {
    private val repositoryJob = SupervisorJob(parentCoroutineScope.coroutineContext[Job])
    private val coroutineScope = CoroutineScope(parentCoroutineScope.coroutineContext + repositoryJob)
    private val loadedAssetsState = MutableStateFlow<LoadedOfficialAssets?>(null)

    val status: Flow<AssetLoadStatus>
        field = MutableStateFlow<AssetLoadStatus>(
            AssetLoadStatus.Loading(
                action = "Preparing the official asset loader",
                detail = "The server is starting the first asset-loading step",
                completedSteps = 0,
                totalSteps = ASSET_LOAD_STEPS,
            ),
        )

    init {
        coroutineScope.launch { loadUntilReady(minecraftVersion) }
    }

    suspend fun awaitLoaded(): LoadedOfficialAssets = loadedAssetsState.filterNotNull().first()

    suspend fun blockRenderResource(
        assetRevision: String,
        surfaceBlockState: SurfaceBlockState,
    ): BlockRenderResource? {
        val loadedOfficialAssets = loadedAssetsState.value
            ?.takeIf { candidate -> candidate.revision == assetRevision }
            ?: return null
        return loadedOfficialAssets.blockAssets.blockRenderResource(surfaceBlockState)
    }

    fun textureResource(assetRevision: String, texture: Identifier): TextureResource? {
        val loadedOfficialAssets = loadedAssetsState.value
            ?.takeIf { candidate -> candidate.revision == assetRevision }
            ?: return null
        val png = loadedOfficialAssets.resources[texture.textureEntryName()] ?: return null
        return TextureResource(png, loadedOfficialAssets.blockAssets.animationFrame(texture))
    }

    fun close() {
        coroutineScope.cancel()
        httpClient.close()
    }

    private suspend fun loadUntilReady(minecraftVersion: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val loadedOfficialAssets = load(minecraftVersion)
                loadedAssetsState.value = loadedOfficialAssets
                status.value = AssetLoadStatus.Ready(
                    assetRevision = loadedOfficialAssets.revision,
                    fileCount = loadedOfficialAssets.resources.size,
                    byteCount = loadedOfficialAssets.byteCount,
                )
                logger.info {
                    "Loaded ${loadedOfficialAssets.resources.size} official client asset files using ${loadedOfficialAssets.byteCount} bytes"
                }
                return
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (failure: Throwable) {
                val message = failure.message ?: failure::class.simpleName ?: "Official asset loading failed"
                logger.warn(failure) { "Official client assets could not be loaded; retrying in $ASSET_RETRY_DELAY_MILLISECONDS ms" }
                status.value = AssetLoadStatus.Failed(message, ASSET_RETRY_DELAY_MILLISECONDS)
                delay(ASSET_RETRY_DELAY_MILLISECONDS.milliseconds)
            }
        }
    }

    private suspend fun load(minecraftVersion: String): LoadedOfficialAssets {
        publishLoading(
            action = "Reading the official version manifest",
            detail = "Resolving the selected Minecraft client artifact",
            completedSteps = 0,
        )
        val manifestBytes = getBytes(OFFICIAL_VERSION_MANIFEST_URL, MAXIMUM_METADATA_BYTES)
        val pistonVersionManifest = ASSET_JSON.decodeFromString<PistonVersionManifest>(manifestBytes.decodeToString())
        val pistonVersionReference = pistonVersionManifest.versions.singleOrNull { pistonVersionReference ->
            pistonVersionReference.id == minecraftVersion
        } ?: error("The official version manifest does not contain $minecraftVersion")
        requireHttps(pistonVersionReference.url, "version metadata")

        publishLoading(
            action = "Verifying the official version metadata",
            detail = "Checking the metadata SHA-1 before reading the client download",
            completedSteps = 1,
        )
        val versionMetadataBytes = getBytes(pistonVersionReference.url, MAXIMUM_METADATA_BYTES)
        check(versionMetadataBytes.sha1Hex().equals(pistonVersionReference.sha1, ignoreCase = true)) {
            "The official version metadata SHA-1 does not match"
        }
        val pistonVersionMetadata = ASSET_JSON.decodeFromString<PistonVersionMetadata>(
            versionMetadataBytes.decodeToString(),
        )
        val clientDownload = pistonVersionMetadata.downloads.client
        requireHttps(clientDownload.url, "client JAR")
        check(clientDownload.size in 1..MAXIMUM_CLIENT_JAR_BYTES) {
            "The official client JAR size is outside the supported asset-loader range"
        }

        publishLoading(
            action = "Downloading the official client assets",
            detail = "Fetching the selected client JAR into server memory",
            completedSteps = 2,
            loadedBytes = 0L,
            totalBytes = clientDownload.size,
        )
        var lastReportedClientBytes = 0L
        val clientJarBytes = getBytes(clientDownload.url, MAXIMUM_CLIENT_JAR_BYTES) { loadedBytes ->
            val boundedLoadedBytes = loadedBytes.coerceAtMost(clientDownload.size)
            if (
                boundedLoadedBytes == clientDownload.size ||
                boundedLoadedBytes - lastReportedClientBytes >= ASSET_DOWNLOAD_PROGRESS_INTERVAL_BYTES
            ) {
                lastReportedClientBytes = boundedLoadedBytes
                publishLoading(
                    action = "Downloading the official client assets",
                    detail = "Streaming the selected client JAR into server memory",
                    completedSteps = 2,
                    loadedBytes = boundedLoadedBytes,
                    totalBytes = clientDownload.size,
                )
            }
        }
        check(clientJarBytes.size.toLong() == clientDownload.size) {
            "The official client JAR byte count does not match its metadata"
        }
        check(clientJarBytes.sha1Hex().equals(clientDownload.sha1, ignoreCase = true)) {
            "The official client JAR SHA-1 does not match"
        }

        publishLoading(
            action = "Indexing block assets",
            detail = "Reading block states, models, textures, and animation metadata",
            completedSteps = 3,
            loadedBytes = clientDownload.size,
            totalBytes = clientDownload.size,
        )
        val resources = readAssetResources(clientJarBytes)
        publishLoading(
            action = "Classifying transparent block surfaces",
            detail = "Resolving official models and PNG transparency for the vanilla block-state palette",
            completedSteps = 4,
            loadedFiles = resources.size,
            totalFiles = resources.size,
        )
        val blockAssetIndex = BlockAssetIndex.create(
            resources,
            VanillaRegistryData.vanillaBlockStateRegistry.vanillaBlockStates,
        )
        return LoadedOfficialAssets(
            revision = clientDownload.sha1.lowercase(),
            resources = resources,
            blockAssets = blockAssetIndex,
        )
    }

    private suspend fun getBytes(
        url: String,
        maximumBytes: Long,
        progressChanged: ((Long) -> Unit)? = null,
    ): ByteArray {
        val response = httpClient.get(url) {
            progressChanged?.let { listener ->
                onDownload { bytesSentTotal, _ -> listener(bytesSentTotal) }
            }
        }
        check(response.status.isSuccess()) { "Official asset request failed with HTTP ${response.status.value}" }
        return response.body<ByteArray>().also { bytes ->
            check(bytes.size.toLong() <= maximumBytes) { "Official asset response exceeds its supported size" }
        }
    }

    private suspend fun readAssetResources(clientJarBytes: ByteArray): Map<String, ByteArray> {
        val temporaryClientJar = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "minecraft-web-map-${Random.nextLong().toULong().toString(16)}.jar"
        fileSystem.write(temporaryClientJar, mustCreate = true) { write(clientJarBytes) }
        var primaryFailure: Throwable? = null
        try {
            val zipFileSystem = fileSystem.openZip(temporaryClientJar)
            val root = "/".toPath()
            val assetPaths = zipFileSystem.listRecursively(root, followSymlinks = false).mapNotNull { path ->
                val metadata = zipFileSystem.metadataOrNull(path) ?: return@mapNotNull null
                if (!metadata.isRegularFile) return@mapNotNull null
                val resourcePath = path.relativeTo(root).segments.joinToString("/")
                resourcePath.takeIf(String::isOfficialAssetResource)?.let { value ->
                    Triple(
                        path,
                        value,
                        checkNotNull(metadata.size) { "Official asset has no byte count: $resourcePath" })
                }
            }.toList()
            check(assetPaths.isNotEmpty()) { "The official client JAR contains no supported block assets" }
            val totalAssetBytes = assetPaths.sumOf { (_, _, byteCount) -> byteCount }
            check(totalAssetBytes <= MAXIMUM_UNCOMPRESSED_ASSET_BYTES) {
                "The official client JAR block assets exceed the in-memory resource limit"
            }
            val resources = linkedMapOf<String, ByteArray>()
            var loadedBytes = 0L
            assetPaths.forEachIndexed { index, (path, resourcePath, byteCount) ->
                currentCoroutineContext().ensureActive()
                resources[resourcePath] = zipFileSystem.read(path) { readByteArray() }
                loadedBytes += byteCount
                if ((index + 1) % ASSET_PROGRESS_FILE_INTERVAL == 0 || index == assetPaths.lastIndex) {
                    publishLoading(
                        action = "Indexing block assets",
                        detail = "Retaining validated asset files in server memory",
                        completedSteps = 3,
                        loadedFiles = index + 1,
                        totalFiles = assetPaths.size,
                        loadedBytes = loadedBytes,
                        totalBytes = totalAssetBytes,
                    )
                }
            }
            requireAssetCategories(resources.keys)
            return resources
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                fileSystem.delete(temporaryClientJar, mustExist = false)
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) throw cleanupFailure
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun publishLoading(
        action: String,
        detail: String,
        completedSteps: Int,
        loadedFiles: Int? = null,
        totalFiles: Int? = null,
        loadedBytes: Long? = null,
        totalBytes: Long? = null,
    ) {
        status.value = AssetLoadStatus.Loading(
            action = action,
            detail = detail,
            completedSteps = completedSteps,
            totalSteps = ASSET_LOAD_STEPS,
            loadedFiles = loadedFiles,
            totalFiles = totalFiles,
            loadedBytes = loadedBytes,
            totalBytes = totalBytes,
        )
    }
}

private fun ByteArray.sha1Hex(): String = toByteString().sha1().hex()

private fun requireHttps(url: String, role: String) {
    check(url.startsWith("https://")) { "The official $role URL is not HTTPS" }
}

private fun String.isOfficialAssetResource(): Boolean {
    val segments = split('/')
    return !(segments.size < 4 || segments[0] != "assets") && when (segments[2]) {
        "blockstates", "models" -> endsWith(".json")
        "textures" -> endsWith(".png") || endsWith(".png.mcmeta")
        else -> false
    }
}

private fun requireAssetCategories(resourcePaths: Set<String>) {
    check(resourcePaths.any { resourcePath -> resourcePath.contains("/blockstates/") }) {
        "The official client JAR contains no block-state definitions"
    }
    check(resourcePaths.any { resourcePath -> resourcePath.contains("/models/") }) {
        "The official client JAR contains no models"
    }
    check(resourcePaths.any { resourcePath -> resourcePath.contains("/textures/") && resourcePath.endsWith(".png") }) {
        "The official client JAR contains no textures"
    }
}

@Serializable
private data class PistonVersionManifest(
    val versions: List<PistonVersionReference>,
)

@Serializable
private data class PistonVersionReference(
    val id: String,
    val url: String,
    val sha1: String,
)

@Serializable
private data class PistonVersionMetadata(
    val downloads: PistonDownloads,
)

@Serializable
private data class PistonDownloads(
    val client: PistonDownload,
)

@Serializable
private data class PistonDownload(
    val sha1: String,
    val size: Long,
    val url: String,
)

private val ASSET_JSON: Json = Json { ignoreUnknownKeys = true }
private const val OFFICIAL_VERSION_MANIFEST_URL: String =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
private const val ASSET_LOAD_STEPS: Int = 5
private const val ASSET_PROGRESS_FILE_INTERVAL: Int = 128
private const val ASSET_DOWNLOAD_PROGRESS_INTERVAL_BYTES: Long = 256L * 1024L
private const val ASSET_RETRY_DELAY_MILLISECONDS: Long = 2_000L
private const val MAXIMUM_METADATA_BYTES: Long = 8L * 1024L * 1024L
private const val MAXIMUM_CLIENT_JAR_BYTES: Long = 128L * 1024L * 1024L
private const val MAXIMUM_UNCOMPRESSED_ASSET_BYTES: Long = 64L * 1024L * 1024L
