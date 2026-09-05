package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.distribution.metadata.MinecraftDistributionMetadataApi
import com.hiczp.minecraft.distribution.metadata.download
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import okio.*

internal class ResourceDownloader(
    private val minecraftDistributionMetadataApi: MinecraftDistributionMetadataApi,
    private val fileSystem: FileSystem,
) {
    suspend fun download(downloadSpec: DownloadSpec, target: Path) {
        val minecraftDownload = downloadSpec.minecraftDownload
        val parent = checkNotNull(target.parent)
        fileSystem.createDirectories(parent)
        val temporary = parent / ".${target.name}.download"
        val hashingSink = HashingSink.sha1(fileSystem.sink(temporary, mustCreate = false))
        val byteCount = hashingSink.buffer().use { bufferedSink ->
            minecraftDistributionMetadataApi.download(minecraftDownload).execute { httpResponse ->
                httpResponse.bodyAsChannel().copyTo(bufferedSink)
            }
        }
        require(byteCount == minecraftDownload.size) { "Length mismatch for ${downloadSpec.relativePath}" }
        require(hashingSink.hash.hex().equals(minecraftDownload.sha1, ignoreCase = true)) {
            "SHA-1 mismatch for ${downloadSpec.relativePath}"
        }
        fileSystem.atomicMove(temporary, target)
    }

    fun isValid(path: Path, downloadSpec: DownloadSpec): Boolean {
        val minecraftDownload = downloadSpec.minecraftDownload
        val fileMetadata = fileSystem.metadataOrNull(path) ?: return false
        if (!fileMetadata.isRegularFile || fileMetadata.size != minecraftDownload.size) return false
        val hashingSource = HashingSource.sha1(fileSystem.source(path))
        hashingSource.use { source ->
            blackholeSink().buffer().use { bufferedSink -> bufferedSink.writeAll(source) }
        }
        return hashingSource.hash.hex().equals(minecraftDownload.sha1, ignoreCase = true)
    }
}

// Ktor exposes a kotlinx-io channel while launcher storage uses Okio FileSystem. Copying through a bounded byte array
// preserves streaming without relying on a sink adapter whose intermediate Okio buffer is unsafe under concurrent use.
private suspend fun ByteReadChannel.copyTo(bufferedSink: BufferedSink): Long {
    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = readAvailable(buffer)
        if (read == -1) return total
        if (read == 0) continue
        bufferedSink.write(buffer, 0, read)
        total += read
    }
}

private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
