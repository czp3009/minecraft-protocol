package com.hiczp.minecraft.test

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import org.kotlincrypto.core.digest.Digest
import org.kotlincrypto.hash.md.MD5
import org.kotlincrypto.hash.sha1.SHA1
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal val testJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

internal fun Path.exists(): Boolean = SystemFileSystem.exists(this)

internal fun Path.isRegularFile(): Boolean =
    SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

internal fun Path.isDirectory(): Boolean =
    SystemFileSystem.metadataOrNull(this)?.isDirectory == true

internal fun Path.ensureDirectory() {
    try {
        SystemFileSystem.createDirectories(this)
    } catch (failure: Throwable) {
        val directoryExists = runCatching { isDirectory() }
            .getOrDefault(false)
        if (!directoryExists) throw failure
    }
}

internal fun Path.size(): Long =
    checkNotNull(SystemFileSystem.metadataOrNull(this)) {
        "Filesystem entry does not exist: $this"
    }.size

internal fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use(Source::readByteArray)

internal fun Path.readText(): String = readBytes().decodeToString()

internal fun Path.readJsonObject(): JsonObject =
    testJson.parseToJsonElement(readText()).jsonObject

internal fun ByteArray.decodeJsonObject(source: String): JsonObject =
    try {
        testJson.parseToJsonElement(decodeToString()).jsonObject
    } catch (failure: Throwable) {
        throw IllegalStateException(
            "Source is not a UTF-8 JSON object: $source",
            failure,
        )
    }

internal fun JsonObject.requiredObject(name: String): JsonObject =
    getValue(name).jsonObject

internal fun JsonObject.requiredArray(name: String): JsonArray =
    getValue(name).jsonArray

internal fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.let { value ->
        check(value.isString) { "JSON property '$name' is not a string" }
        value.content
    }

internal fun JsonObject.requiredInt(name: String): Int =
    getValue(name).jsonPrimitive.let { value ->
        check(!value.isString) { "JSON property '$name' is not an integer" }
        value.intOrNull
            ?: error("JSON property '$name' is not an integer")
    }

internal fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.let { value ->
        check(!value.isString) { "JSON property '$name' is not an integer" }
        value.longOrNull
            ?: error("JSON property '$name' is not an integer")
    }

internal fun Path.writeJson(value: JsonElement) {
    atomicWriteText(
        testJson.encodeToString(
            JsonElement.serializer(),
            value.withSortedObjectKeys(),
        ) + "\n",
    )
}

private fun JsonElement.withSortedObjectKeys(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map(JsonElement::withSortedObjectKeys))
    is JsonObject -> JsonObject(
        entries.sortedBy { it.key }.associateTo(linkedMapOf()) { (key, value) ->
            key to value.withSortedObjectKeys()
        },
    )

    else -> this
}

fun Path.writeJsonReport(value: JsonElement) {
    writeJson(value)
}

internal fun Path.atomicWriteText(content: String) {
    atomicWrite(content.encodeToByteArray())
}

internal fun Path.atomicWrite(content: ByteArray) {
    val directory = requireNotNull(parent) {
        "Output path has no parent directory: $this"
    }
    directory.ensureDirectory()
    val temporary = uniqueSibling(".$name.", ".tmp")
    try {
        SystemFileSystem.sink(temporary).buffered().use { sink ->
            sink.write(content)
        }
        try {
            SystemFileSystem.atomicMove(temporary, this)
        } catch (firstFailure: Throwable) {
            if (hasContent(content)) return
            throw firstFailure
        }
    } finally {
        SystemFileSystem.delete(temporary, mustExist = false)
    }
}

private fun Path.hasContent(expected: ByteArray): Boolean =
    runCatching {
        isRegularFile() && readBytes().contentEquals(expected)
    }.getOrDefault(false)

internal fun Path.sha1(): String = digest(SHA1())

internal fun Path.sha256(): String = digest(SHA256())

private fun Path.digest(digest: Digest): String {
    SystemFileSystem.source(this).buffered().use { source ->
        val buffer = ByteArray(FILE_BUFFER_SIZE)
        while (true) {
            val count = source.readAtMostTo(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString()
}

internal fun ByteArray.md5(): ByteArray = MD5().digest(this)

internal fun ByteArray.sha1(): String = SHA1().digest(this).toHexString()

internal fun ByteArray.sha256(): String = SHA256().digest(this).toHexString()

internal fun Path.safeResolve(relative: String): Path {
    require(relative.isNotBlank()) { "Relative path is blank" }
    require(!Path(relative).isAbsolute) { "Path is absolute: $relative" }
    val components = relative.split('/', '\\')
    require(
        components.all {
            it.isNotEmpty() && it != "." && it != ".." && ':' !in it
        },
    ) {
        "Path escapes $this: $relative"
    }
    return Path(this, *components.toTypedArray())
}

internal fun Path.deleteTree() {
    val metadata = SystemFileSystem.metadataOrNull(this) ?: return
    if (metadata.isDirectory) {
        SystemFileSystem.list(this).forEach(Path::deleteTree)
    }
    SystemFileSystem.delete(this)
}

internal fun createUniqueDirectory(
    parent: Path,
    prefix: String,
): Path {
    require(prefix.isNotEmpty()) { "Directory prefix is empty" }
    parent.ensureDirectory()
    repeat(UNIQUE_PATH_ATTEMPTS) {
        val candidate = Path(parent, "$prefix${randomSuffix()}")
        val created = runCatching {
            SystemFileSystem.createDirectories(candidate, mustCreate = true)
        }.isSuccess
        if (created) return candidate
    }
    error("Could not create a unique directory under $parent")
}

private fun Path.uniqueSibling(prefix: String, suffix: String): Path {
    val directory = requireNotNull(parent)
    repeat(UNIQUE_PATH_ATTEMPTS) {
        val candidate = Path(directory, "$prefix${randomSuffix()}$suffix")
        if (!candidate.exists()) return candidate
    }
    error("Could not create a unique temporary path beside $this")
}

private fun randomSuffix(): String =
    Random.nextBytes(UNIQUE_SUFFIX_BYTES).toHexString()

private const val HTTP_MAX_RETRIES = 3
private const val HTTP_STREAM_BUFFER_SIZE = 1024L * 1024L

private class RetryableResponseBody(
    val consume: suspend (HttpResponse) -> Unit,
)

private val retryableResponseBodyKey =
    AttributeKey<RetryableResponseBody>("RetryableResponseBody")

private val RetryableResponseBodyPlugin = createClientPlugin(
    "RetryableResponseBody",
) {
    on(Send) { request ->
        val call: HttpClientCall = proceed(request)
        request.attributes.getOrNull(retryableResponseBodyKey)
            ?.consume
            ?.invoke(call.response)
        call
    }
}

internal fun HttpClientConfig<*>.configureVerifiedDownloads(
    userAgent: String,
) {
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = HTTP_MAX_RETRIES)
        retryOnException(
            maxRetries = HTTP_MAX_RETRIES,
            retryOnTimeout = true,
        )
        exponentialDelay()
    }
    install(RetryableResponseBodyPlugin)
    install(HttpTimeout)
    install(UserAgent) {
        agent = userAgent
    }
}

internal class KtorArtifactDownloader(
    private val client: HttpClient,
) {
    suspend fun getBytes(
        url: String,
        timeout: Duration = 60.seconds,
    ): ByteArray {
        var content: ByteArray? = null
        val response = client.get(url) {
            configureTimeout(timeout)
            attributes.put(
                retryableResponseBodyKey,
                RetryableResponseBody { current ->
                    content = current.bodyAsChannel()
                        .readRemaining()
                        .readByteArray()
                },
            )
        }
        check(response.status.isSuccess()) {
            "HTTP ${response.status.value} for $url"
        }
        return checkNotNull(content) {
            "HTTP response body was not consumed: $url"
        }
    }

    suspend fun getJson(url: String): JsonObject =
        getBytes(url).decodeJsonObject(url)

    suspend fun ensureDownload(
        url: String,
        destination: Path,
        expectedSize: Long,
        digestAlgorithm: String,
        expectedDigest: String,
        timeout: Duration = 60.seconds,
    ): Boolean {
        if (
            verification(
                destination,
                expectedSize,
                digestAlgorithm,
                expectedDigest,
            ) == DownloadVerification.VERIFIED
        ) {
            return false
        }
        val temporary = destination.uniqueSibling(
            prefix = ".${destination.name}.",
            suffix = ".download",
        )
        requireNotNull(destination.parent).ensureDirectory()
        try {
            try {
                val response = client.get(url) {
                    configureTimeout(timeout)
                    attributes.put(
                        retryableResponseBodyKey,
                        RetryableResponseBody { current ->
                            SystemFileSystem.sink(temporary).buffered().use { sink ->
                                val channel = current.bodyAsChannel()
                                while (!channel.exhausted()) {
                                    channel.readRemaining(HTTP_STREAM_BUFFER_SIZE)
                                        .transferTo(sink)
                                }
                            }
                        },
                    )
                }
                check(response.status.isSuccess()) {
                    "HTTP ${response.status.value} for $url"
                }
                verifyAndCommitDownload(
                    url = url,
                    temporary = temporary,
                    destination = destination,
                    expectedSize = expectedSize,
                    digestAlgorithm = digestAlgorithm,
                    expectedDigest = expectedDigest,
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (
                    verification(
                        destination,
                        expectedSize,
                        digestAlgorithm,
                        expectedDigest,
                    ) == DownloadVerification.VERIFIED
                ) {
                    return false
                }
                throw failure
            }
        } finally {
            SystemFileSystem.delete(temporary, mustExist = false)
        }
        return true
    }

    private fun io.ktor.client.request.HttpRequestBuilder.configureTimeout(
        timeout: Duration,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "HTTP timeout must be positive and finite: $timeout"
        }
        val timeoutMillis = timeout.inWholeMilliseconds
        timeout {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }

    private fun Path.digestForDownload(algorithm: String): String =
        when (algorithm) {
            "SHA-1" -> sha1()
            "SHA-256" -> sha256()
            else -> error("Unsupported digest algorithm: $algorithm")
        }

    private fun verification(
        destination: Path,
        expectedSize: Long,
        digestAlgorithm: String,
        expectedDigest: String,
    ): DownloadVerification = try {
        val metadata = SystemFileSystem.metadataOrNull(destination)
            ?: return DownloadVerification.INVALID
        if (!metadata.isRegularFile || metadata.size != expectedSize) {
            DownloadVerification.INVALID
        } else if (
            destination.digestForDownload(digestAlgorithm) == expectedDigest
        ) {
            DownloadVerification.VERIFIED
        } else {
            DownloadVerification.INVALID
        }
    } catch (_: IOException) {
        DownloadVerification.UNAVAILABLE
    }

    private suspend fun verifyAndCommitDownload(
        url: String,
        temporary: Path,
        destination: Path,
        expectedSize: Long,
        digestAlgorithm: String,
        expectedDigest: String,
    ) {
        check(temporary.size() == expectedSize) {
            "Downloaded artifact has the wrong size: $url"
        }
        check(temporary.digestForDownload(digestAlgorithm) == expectedDigest) {
            "Downloaded artifact failed $digestAlgorithm: $url"
        }
        val commitLock = acquireCommitLock(
            destination = destination,
            expectedSize = expectedSize,
            digestAlgorithm = digestAlgorithm,
            expectedDigest = expectedDigest,
        ) ?: return
        try {
            val started = TimeSource.Monotonic.markNow()
            var lastFailure: Throwable? = null
            while (true) {
                when (
                    verification(
                        destination,
                        expectedSize,
                        digestAlgorithm,
                        expectedDigest,
                    )
                ) {
                    DownloadVerification.VERIFIED -> return
                    DownloadVerification.INVALID -> try {
                        SystemFileSystem.atomicMove(temporary, destination)
                    } catch (failure: Throwable) {
                        lastFailure = failure
                    }

                    DownloadVerification.UNAVAILABLE -> Unit
                }
                if (
                    verification(
                        destination,
                        expectedSize,
                        digestAlgorithm,
                        expectedDigest,
                    ) == DownloadVerification.VERIFIED
                ) {
                    return
                }
                if (started.elapsedNow() >= COMMIT_TIMEOUT) {
                    throw IllegalStateException(
                        "Could not atomically commit verified artifact: " +
                                destination,
                        lastFailure,
                    )
                }
                awaitCommitTurn()
            }
        } finally {
            runCatching {
                SystemFileSystem.delete(commitLock, mustExist = false)
            }
        }
    }

    private suspend fun acquireCommitLock(
        destination: Path,
        expectedSize: Long,
        digestAlgorithm: String,
        expectedDigest: String,
    ): Path? {
        val commitLock = destination.commitLock()
        var waitingSince = TimeSource.Monotonic.markNow()
        var staleLockRecoveryAttempted = false
        while (true) {
            if (
                verification(
                    destination,
                    expectedSize,
                    digestAlgorithm,
                    expectedDigest,
                ) == DownloadVerification.VERIFIED
            ) {
                return null
            }
            try {
                SystemFileSystem.createDirectories(
                    commitLock,
                    mustCreate = true,
                )
                return commitLock
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (waitingSince.elapsedNow() >= STALE_COMMIT_LOCK_AFTER) {
                    if (staleLockRecoveryAttempted) {
                        throw IllegalStateException(
                            "Could not acquire artifact commit lock: $commitLock",
                            failure,
                        )
                    }
                    runCatching {
                        SystemFileSystem.delete(commitLock, mustExist = false)
                    }.getOrElse { deletionFailure ->
                        throw IllegalStateException(
                            "Could not recover stale artifact commit lock: " +
                                    commitLock,
                            deletionFailure,
                        )
                    }
                    staleLockRecoveryAttempted = true
                    waitingSince = TimeSource.Monotonic.markNow()
                }
            }
            awaitCommitTurn()
        }
    }

    private suspend fun awaitCommitTurn() {
        withContext(Dispatchers.Default) {
            delay(COMMIT_RETRY_INTERVAL)
        }
    }

    private fun Path.commitLock(): Path =
        Path(requireNotNull(parent), ".$name.commit-lock")
}

private enum class DownloadVerification {
    VERIFIED,
    INVALID,
    UNAVAILABLE,
}

internal object TestHttp {
    private const val USER_AGENT =
        "minecraft-protocol test-support/1.0 " +
                "(https://github.com/hiczp/minecraft-protocol)"

    private val downloader = KtorArtifactDownloader(
        HttpClient(platformHttpClientEngine()) {
            if (!PlatformUtils.IS_JS && !PlatformUtils.IS_WASM_JS) {
                environmentProxy()?.let { configuredProxy ->
                    engine { proxy = configuredProxy }
                }
            }
            configureVerifiedDownloads(USER_AGENT)
        },
    )

    suspend fun getBytes(
        url: String,
        timeout: Duration = 60.seconds,
    ): ByteArray = downloader.getBytes(url, timeout)

    suspend fun getJson(url: String): JsonObject = downloader.getJson(url)

    suspend fun ensureDownload(
        url: String,
        destination: Path,
        expectedSize: Long,
        digestAlgorithm: String,
        expectedDigest: String,
        timeout: Duration = 60.seconds,
    ): Boolean =
        downloader.ensureDownload(
            url = url,
            destination = destination,
            expectedSize = expectedSize,
            digestAlgorithm = digestAlgorithm,
            expectedDigest = expectedDigest,
            timeout = timeout,
        )
}

internal fun environmentProxy() = run {
    if (platformEnvironmentVariable("NO_PROXY") == "*" ||
        platformEnvironmentVariable("no_proxy") == "*"
    ) {
        return@run null
    }
    val (name, value) = listOf(
        "HTTPS_PROXY",
        "https_proxy",
        "HTTP_PROXY",
        "http_proxy",
    ).firstNotNullOfOrNull { name ->
        platformEnvironmentVariable(name)
            ?.takeIf(String::isNotBlank)
            ?.let { value -> name to value }
    } ?: return@run null

    runCatching { ProxyBuilder.http(Url(value)) }.getOrElse { failure ->
        throw IllegalArgumentException(
            "Invalid proxy URL in $name",
            failure,
        )
    }
}

internal expect fun platformEnvironmentVariable(name: String): String?

internal expect fun platformHttpClientEngine(): HttpClientEngineFactory<*>

private const val VERSION_MANIFEST_URL =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

internal suspend fun officialReleaseManifestEntry(version: String): JsonObject =
    TestHttp.getJson(VERSION_MANIFEST_URL)
        .requiredArray("versions")
        .map { it.jsonObject }
        .firstOrNull {
            it.requiredString("id") == version &&
                    it.requiredString("type") == "release"
        }
        ?: error("Mojang manifest has no stable release $version")

internal fun jsonObjectOf(
    vararg entries: Pair<String, JsonElement>,
): JsonObject = JsonObject(linkedMapOf(*entries))

internal fun jsonString(value: String): JsonPrimitive = JsonPrimitive(value)

internal fun jsonNumber(value: Number): JsonPrimitive = JsonPrimitive(value)

private const val FILE_BUFFER_SIZE = 1024 * 1024
private const val UNIQUE_PATH_ATTEMPTS = 100
private const val UNIQUE_SUFFIX_BYTES = 12
private val COMMIT_RETRY_INTERVAL = 25.milliseconds
private val COMMIT_TIMEOUT = 2.minutes
private val STALE_COMMIT_LOCK_AFTER = 2.minutes
