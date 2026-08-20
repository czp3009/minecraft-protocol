package com.hiczp.minecraft.demo.launcher

import io.ktor.client.statement.*
import io.ktor.utils.io.*
import okio.*

internal class ResourceDownloader(
    private val api: MojangApi,
    private val fileSystem: FileSystem,
) {
    suspend fun download(spec: DownloadSpec, target: Path) {
        val parent = checkNotNull(target.parent)
        fileSystem.createDirectories(parent)
        val temporary = parent / ".${target.name}.download"
        val hashingSink = HashingSink.sha1(fileSystem.sink(temporary, mustCreate = false))
        val byteCount = hashingSink.buffer().use { sink ->
            api.download(spec.url).execute { response ->
                response.bodyAsChannel().copyTo(sink)
            }
        }
        require(byteCount == spec.size) { "Length mismatch for ${spec.relativePath}" }
        require(hashingSink.hash.hex() == validateSha1(spec.sha1)) {
            "SHA-1 mismatch for ${spec.relativePath}"
        }
        fileSystem.atomicMove(temporary, target)
    }

    fun isValid(path: Path, spec: DownloadSpec): Boolean {
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        if (!metadata.isRegularFile || metadata.size != spec.size) return false
        val hashingSource = HashingSource.sha1(fileSystem.source(path))
        hashingSource.use { source ->
            blackholeSink().buffer().use { sink -> sink.writeAll(source) }
        }
        return hashingSource.hash.hex() == validateSha1(spec.sha1)
    }
}

// Ktor exposes a kotlinx-io channel while launcher storage uses Okio FileSystem. Copying through a bounded byte array
// preserves streaming without relying on a sink adapter whose intermediate Okio buffer is unsafe under concurrent use.
private suspend fun ByteReadChannel.copyTo(sink: BufferedSink): Long {
    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = readAvailable(buffer)
        if (read == -1) return total
        if (read == 0) continue
        sink.write(buffer, 0, read)
        total += read
    }
}

private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
