package com.hiczp.minecraft.protocol.buildScript

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.util.AttributeKey
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readRemaining
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path as IoPath
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.time.Duration as KotlinDuration
import kotlin.time.Duration.Companion.seconds

internal val protocolJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

abstract class MinecraftProtocolToolTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    init {
        minecraftVersion.convention(MinecraftTarget.MINECRAFT_VERSION)
    }
}

internal fun Path.readJsonObject(): JsonObject =
    protocolJson.parseToJsonElement(readText()).jsonObject

internal fun ByteArray.decodeJsonObject(source: String): JsonObject =
    try {
        protocolJson.parseToJsonElement(toString(StandardCharsets.UTF_8))
            .jsonObject
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
        check(value.isString) {
            "JSON property '$name' is not a string"
        }
        value.content
    }

internal fun JsonObject.requiredInt(name: String): Int =
    getValue(name).jsonPrimitive.let { value ->
        check(!value.isString) {
            "JSON property '$name' is not an integer"
        }
        value.intOrNull
            ?: error("JSON property '$name' is not an integer")
    }

internal fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.let { value ->
        check(!value.isString) {
            "JSON property '$name' is not an integer"
        }
        value.longOrNull
            ?: error("JSON property '$name' is not an integer")
    }

internal fun jsonObjectOf(
    vararg entries: Pair<String, JsonElement>,
): JsonObject = JsonObject(linkedMapOf(*entries))

internal fun jsonString(value: String): JsonPrimitive = JsonPrimitive(value)

internal fun jsonNumber(value: Number): JsonPrimitive = JsonPrimitive(value)

internal fun jsonBoolean(value: Boolean): JsonPrimitive = JsonPrimitive(value)

internal fun Path.writeJson(
    value: JsonElement,
    sortKeys: Boolean = false,
) {
    atomicWriteText(renderJson(value, sortKeys) + "\n")
}

internal fun renderJson(
    value: JsonElement,
    sortKeys: Boolean = false,
): String = protocolJson.encodeToString(
    JsonElement.serializer(),
    if (sortKeys) value.withSortedObjectKeys() else value,
)

private fun JsonElement.withSortedObjectKeys(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map { it.withSortedObjectKeys() })
    is JsonObject -> JsonObject(
        entries.sortedBy { it.key }.associateTo(linkedMapOf()) { (key, value) ->
            key to value.withSortedObjectKeys()
        },
    )

    else -> this
}

internal fun Path.atomicWriteText(content: String) {
    atomicWrite(content.toByteArray(StandardCharsets.UTF_8))
}

internal fun Path.atomicWrite(content: ByteArray) {
    parent?.createDirectories()
    val temporary = Files.createTempFile(
        parent,
        ".$name.",
        ".tmp",
    )
    try {
        Files.write(
            temporary,
            content,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        Files.move(
            temporary,
            this,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun DefaultTask.createIsolatedTemporaryDirectory(
    prefix: String,
): Path {
    val root = temporaryDir.toPath().parent
        .resolve("$name-runs")
        .createDirectories()
    return Files.createTempDirectory(root, "$prefix-")
}

internal fun ByteArray.sha1(): String =
    MessageDigest.getInstance("SHA-1")
        .digest(this)
        .toHexString()

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()

internal fun Path.sha1(): String = digest("SHA-1")

internal fun Path.sha256(): String = digest("SHA-256")

private fun Path.digest(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

internal object ProtocolHttp {
    private const val MAX_RETRIES = 3
    private const val STREAM_BUFFER_SIZE = 1024L * 1024L
    private const val USER_AGENT =
        "minecraft-protocol Gradle tools/1.0 " +
                "(https://github.com/hiczp/minecraft-protocol)"

    private class RetryableResponseBody(
        val consume: suspend (HttpResponse) -> Unit,
    )

    private val retryableResponseBodyKey =
        AttributeKey<RetryableResponseBody>("RetryableResponseBody")

    private val retryableResponseBodyPlugin = createClientPlugin(
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

    private val client = HttpClient(CIO) {
        environmentProxy()?.let { configuredProxy ->
            engine {
                proxy = configuredProxy
            }
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = MAX_RETRIES)
            retryOnException(
                maxRetries = MAX_RETRIES,
                retryOnTimeout = true,
            )
            exponentialDelay()
        }
        install(retryableResponseBodyPlugin)
        install(HttpTimeout)
        install(UserAgent) {
            agent = USER_AGENT
        }
    }

    suspend fun getBytes(
        url: String,
        timeout: KotlinDuration = 60.seconds,
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

    suspend fun getJson(
        url: String,
        timeout: KotlinDuration = 60.seconds,
    ): JsonObject = getBytes(url, timeout).decodeJsonObject(url)

    suspend fun ensureDownload(
        url: String,
        destination: Path,
        expectedSize: Long,
        expectedSha1: String,
        offline: Boolean = false,
        timeout: KotlinDuration = 60.seconds,
    ): Boolean {
        if (
            destination.isRegularFile() &&
            Files.size(destination) == expectedSize &&
            destination.digest("SHA-1") == expectedSha1
        ) {
            return false
        }
        check(!offline) {
            "Official artifact is absent or invalid: $destination"
        }
        destination.parent.createDirectories()
        val temporary = Files.createTempFile(
            destination.parent,
            ".${destination.name}.",
            ".download",
        )
        try {
            val response = client.get(url) {
                configureTimeout(timeout)
                attributes.put(
                    retryableResponseBodyKey,
                    RetryableResponseBody { current ->
                        SystemFileSystem.sink(IoPath(temporary.toString()))
                            .buffered()
                            .use { sink ->
                                val channel = current.bodyAsChannel()
                                while (!channel.exhausted()) {
                                    channel.readRemaining(STREAM_BUFFER_SIZE)
                                        .transferTo(sink)
                                }
                            }
                    },
                )
            }
            check(response.status.isSuccess()) {
                "HTTP ${response.status.value} for $url"
            }
            check(Files.size(temporary) == expectedSize) {
                "Downloaded artifact has wrong size: $url"
            }
            check(
                temporary.digest("SHA-1") == expectedSha1,
            ) {
                "Downloaded artifact failed SHA-1: $url"
            }
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun environmentProxy() = run {
        if (environmentVariable("NO_PROXY", "no_proxy") == "*") {
            return@run null
        }
        val (name, value) = listOf(
            "HTTPS_PROXY",
            "https_proxy",
            "HTTP_PROXY",
            "http_proxy",
        ).firstNotNullOfOrNull { name ->
            System.getenv(name)
                ?.takeIf(String::isNotBlank)
                ?.let { value -> name to value }
        } ?: return@run null

        runCatching { ProxyBuilder.http(Url(value)) }
            .getOrElse { failure ->
                throw IllegalArgumentException(
                    "Invalid proxy URL in $name",
                    failure,
                )
            }
    }

    private fun environmentVariable(
        upperCase: String,
        lowerCase: String,
    ): String? = System.getenv(upperCase) ?: System.getenv(lowerCase)

    private fun io.ktor.client.request.HttpRequestBuilder.configureTimeout(
        timeout: KotlinDuration,
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
}

internal data class ProcessResult(
    val exitCode: Int,
    val output: String,
)

internal fun runProcess(
    command: List<String>,
    workingDirectory: Path? = null,
    timeout: Duration? = null,
    environment: Map<String, String> = emptyMap(),
): ProcessResult {
    val process = ProcessBuilder(command)
        .apply {
            if (workingDirectory != null) {
                directory(workingDirectory.toFile())
            }
            redirectErrorStream(true)
            environment().putAll(environment)
        }
        .start()
    val output = StringBuilder()
    val reader = Thread.ofVirtual().start {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach(output::appendLine)
        }
    }
    val completed = if (timeout == null) {
        process.waitFor()
        true
    } else {
        process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    if (!completed) {
        process.destroyForcibly()
        process.waitFor()
    }
    reader.join()
    check(completed) {
        "Process timed out: ${command.joinToString(" ")}"
    }
    return ProcessResult(process.exitValue(), output.toString())
}

internal fun Path.readZipEntry(name: String): ByteArray =
    ZipFile(toFile()).use { zip ->
        val entry = zip.getEntry(name)
            ?: error("ZIP entry does not exist: $name")
        zip.getInputStream(entry).use { it.readBytes() }
    }

internal data class MinecraftProtocolTarget(
    val minecraftVersion: String,
    val protocolVersion: Int,
    val javaMajorVersion: Int,
)

internal data class OfficialMinecraftTargetReport(
    val target: MinecraftProtocolTarget,
    val serverSha1: String,
    val serverSha256: String,
    val versionMetadataSha1: String,
)

internal fun Path.readOfficialMinecraftTargetReport(): OfficialMinecraftTargetReport {
    check(isRegularFile()) {
        "Official Minecraft target analysis is missing: $this"
    }
    val report = readJsonObject()
    check(report.requiredInt("schema_version") == 1) {
        "Unsupported official Minecraft target schema"
    }
    val serverSha1 = report.requiredString("official_server_sha1")
    val serverSha256 = report.requiredString("official_server_sha256")
    val metadataSha1 = report.requiredString("version_metadata_sha1")
    check(serverSha1.matches(Regex("[0-9a-f]{40}"))) {
        "Official Minecraft target has an invalid server SHA-1"
    }
    check(serverSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Official Minecraft target has an invalid server SHA-256"
    }
    check(metadataSha1.matches(Regex("[0-9a-f]{40}"))) {
        "Official Minecraft target has an invalid metadata SHA-1"
    }
    return OfficialMinecraftTargetReport(
        target = MinecraftProtocolTarget(
            minecraftVersion = report.requiredString("minecraft_version"),
            protocolVersion = report.requiredInt("protocol_version"),
            javaMajorVersion = report.requiredInt("java_major_version"),
        ),
        serverSha1 = serverSha1,
        serverSha256 = serverSha256,
        versionMetadataSha1 = metadataSha1,
    ).also {
        check(it.target.minecraftVersion.isNotBlank()) {
            "Official Minecraft target has an empty version"
        }
        check(it.target.protocolVersion >= 0) {
            "Official Minecraft target has a negative protocol version"
        }
        check(it.target.javaMajorVersion > 0) {
            "Official Minecraft target has no Java requirement"
        }
    }
}

internal fun Path.readMinecraftProtocolTarget(
    expectedVersion: String,
): MinecraftProtocolTarget {
    check(isRegularFile()) {
        "Official server JAR is missing: $this"
    }
    val version = readZipEntry("version.json")
        .decodeJsonObject("$this!/version.json")
    val minecraftVersion = version.requiredString("id")
    check(minecraftVersion == expectedVersion) {
        "Official server identifies Minecraft $minecraftVersion; " +
                "build selects $expectedVersion"
    }
    return MinecraftProtocolTarget(
        minecraftVersion = minecraftVersion,
        protocolVersion = version.requiredInt("protocol_version"),
        javaMajorVersion = version.requiredInt("java_version"),
    )
}

internal fun Path.deleteTree() {
    if (!exists()) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder())
            .forEach(Files::delete)
    }
}
