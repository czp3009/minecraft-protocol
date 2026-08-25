package com.hiczp.minecraft.buildlogic

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import java.io.EOFException
import java.io.IOException
import java.net.Authenticator
import java.net.ConnectException
import java.net.PasswordAuthentication
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import javax.net.ssl.SSLException
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.text.toCharArray
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.files.Path as IoPath
import kotlin.time.Duration as KotlinDuration

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

internal fun Path.writeJson(
    value: JsonElement,
    sortKeys: Boolean = false,
) {
    val document = if (sortKeys) value.withSortedObjectKeys() else value
    atomicWriteText("${protocolJson.encodeToString(document)}\n")
}

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

internal object ProtocolHttp {
    private const val MAX_RETRIES = 3
    private const val MAX_ATTEMPTS = MAX_RETRIES + 1
    private const val STREAM_BUFFER_SIZE = 1024L * 1024L
    private val DOWNLOAD_REQUEST_TIMEOUT = 10.minutes
    private val DOWNLOAD_CONNECT_TIMEOUT = 30.seconds
    private val DOWNLOAD_SOCKET_TIMEOUT = 60.seconds
    private const val USER_AGENT = "minecraft-protocol Gradle tools/1.0 (https://github.com/hiczp/minecraft-protocol)"
    private val logger = Logging.getLogger(ProtocolHttp::class.java)

    private class RetryableResponseBody(
        val url: String,
        val consume: suspend (HttpResponse) -> Unit,
    ) {
        val attempts = AtomicInteger()
    }

    private class UnexpectedHttpStatusException(
        statusCode: Int,
        statusDescription: String,
    ) : IOException("HTTP $statusCode $statusDescription")

    private val retryableResponseBodyKey = AttributeKey<RetryableResponseBody>("RetryableResponseBody")

    private val retryableResponseBodyPlugin = createClientPlugin(
        "RetryableResponseBody",
    ) {
        on(Send) { request ->
            val responseBody = request.attributes.getOrNull(retryableResponseBodyKey)
            responseBody?.attempts?.incrementAndGet()
            val call: HttpClientCall = proceed(request)
            responseBody?.consume?.invoke(call.response)
            call
        }
    }

    private val systemPropertyProxyAuthenticator = createSystemPropertyProxyAuthenticator()

    private val client = HttpClient(Java) {
        // Keep engine.proxy unset so JDK HttpClient uses the default
        // ProxySelector and therefore the same JVM proxy properties as Gradle.
        systemPropertyProxyAuthenticator?.let { proxyAuthenticator ->
            engine {
                config {
                    authenticator(proxyAuthenticator)
                }
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
    }.also { httpClient ->
        httpClient.monitor.subscribe(HttpRequestRetryEvent) { retry ->
            val responseBody = retry.request.attributes
                .getOrNull(retryableResponseBodyKey)
                ?: return@subscribe
            val reason = retry.cause?.friendlyDownloadReason()
                ?: retry.response?.status?.let { status ->
                    "HTTP ${status.value} ${status.description}"
                }
                ?: "unknown HTTP failure"
            logger.warn(
                "External download attempt ${retry.retryCount}/$MAX_ATTEMPTS failed; retrying ${responseBody.url}: $reason",
            )
        }
    }

    suspend fun getBytes(
        url: String,
        connectTimeout: KotlinDuration = DOWNLOAD_CONNECT_TIMEOUT,
        offline: Boolean = false,
        validate: (ByteArray) -> Unit = {},
    ): ByteArray {
        requireOnline(url, offline)
        var content: ByteArray? = null
        executeDownload(url, connectTimeout) { current ->
            val bytes = current.bodyAsChannel()
                .readRemaining()
                .readByteArray()
            if (current.status.isSuccess()) {
                validate(bytes)
                content = bytes
            }
        }
        return checkNotNull(content) {
            "HTTP response body was not consumed: $url"
        }
    }

    suspend fun getJson(
        url: String,
        connectTimeout: KotlinDuration = DOWNLOAD_CONNECT_TIMEOUT,
        offline: Boolean = false,
    ): JsonObject {
        var content: JsonObject? = null
        getBytes(url, connectTimeout, offline) { bytes ->
            content = protocolJson.decodeFromString<JsonObject>(bytes.decodeToString())
        }
        return checkNotNull(content)
    }

    suspend fun download(
        url: String,
        destination: Path,
        offline: Boolean = false,
        connectTimeout: KotlinDuration = DOWNLOAD_CONNECT_TIMEOUT,
    ) {
        requireOnline(url, offline, destination)
        destination.parent.createDirectories()
        val temporary = Files.createTempFile(
            destination.parent,
            ".${destination.name}.",
            ".download",
        )
        try {
            executeDownload(url, connectTimeout) { current ->
                SystemFileSystem.sink(IoPath(temporary.toString()))
                    .buffered()
                    .use { sink ->
                        val channel = current.bodyAsChannel()
                        while (!channel.exhausted()) {
                            channel.readRemaining(STREAM_BUFFER_SIZE)
                                .transferTo(sink)
                        }
                    }
            }
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private suspend fun executeDownload(
        url: String,
        connectTimeout: KotlinDuration,
        consume: suspend (HttpResponse) -> Unit,
    ) {
        val responseBody = RetryableResponseBody(url, consume)
        try {
            val response = client.get(url) {
                configureDownloadTimeouts(connectTimeout)
                attributes.put(retryableResponseBodyKey, responseBody)
            }
            if (!response.status.isSuccess()) {
                throw UnexpectedHttpStatusException(
                    response.status.value,
                    response.status.description,
                )
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw GradleException(
                buildString {
                    val attempts = responseBody.attempts.get().coerceAtLeast(1)
                    append("Could not download external resource after ")
                    append(attempts)
                    append(if (attempts == 1) " attempt.\n" else " attempts.\n")
                    append("URL: ")
                    append(url)
                    append("\nReason: ")
                    append(failure.friendlyDownloadReason())
                    append('\n')
                    append(proxyDiagnostic())
                    append("\nCheck the network connection, proxy/VPN, and firewall, then rerun the failed Gradle task.")
                },
                failure,
            )
        }
    }

    private fun requireOnline(
        url: String,
        offline: Boolean,
        destination: Path? = null,
    ) {
        if (!offline) return
        throw GradleException(
            buildString {
                append("Cannot download external resource because Gradle is offline.\n")
                append("URL: ")
                append(url)
                destination?.let {
                    append("\nMissing or invalid file: ")
                    append(it)
                }
                append("\nRerun without --offline after network access is available.")
            },
        )
    }

    private fun Throwable.friendlyDownloadReason(): String {
        val cause = rootCause()
        return when (cause) {
            is UnexpectedHttpStatusException ->
                "the remote server returned ${cause.message}"

            is EOFException ->
                "the HTTP response ended before all bytes arrived (the connection or proxy returned a truncated body)"

            is UnknownHostException ->
                "DNS could not resolve ${cause.message ?: "the remote host"}"

            is ConnectException ->
                "the connection could not be established${cause.message?.let { ": $it" }.orEmpty()}"

            is SSLException ->
                "the TLS connection failed${cause.message?.let { ": $it" }.orEmpty()}"

            is IOException ->
                "the network connection failed${cause.message?.let { ": $it" }.orEmpty()}"

            else -> cause.message
                ?.takeIf(String::isNotBlank)
                ?: cause.javaClass.simpleName
        }
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        val seen = mutableSetOf<Throwable>()
        while (current.cause != null && seen.add(current)) {
            current = current.cause!!
        }
        return current
    }

    private fun proxyDiagnostic(): String {
        val configuredProperties = listOf(
            "https.proxyHost",
            "http.proxyHost",
            "socksProxyHost",
            "java.net.useSystemProxies",
        ).filter { name ->
            System.getProperty(name)?.isNotBlank() == true
        }
        return if (configuredProperties.isEmpty()) {
            "Proxy configuration: no JVM proxy system property is set."
        } else {
            "Proxy configuration: ${configuredProperties.joinToString()} ${if (configuredProperties.size == 1) "is" else "are"} set (values hidden); the JVM default proxy selector is used."
        }
    }

    private fun createSystemPropertyProxyAuthenticator(): Authenticator? {
        val credentials = listOf("http", "https").mapNotNull { protocol ->
            val username = System.getProperty("$protocol.proxyUser")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            protocol to ProxyCredentials(
                username = username,
                password = System.getProperty("$protocol.proxyPassword")
                    .orEmpty(),
            )
        }.toMap()
        if (credentials.isEmpty()) return null

        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestorType != RequestorType.PROXY) return null
                val protocol = requestingURL?.protocol?.lowercase()
                    ?: requestingProtocol?.lowercase()
                val selected = credentials[protocol]
                    ?: credentials.values.singleOrNull()
                    ?: return null
                return PasswordAuthentication(
                    selected.username,
                    selected.password.toCharArray(),
                )
            }
        }
    }

    private data class ProxyCredentials(
        val username: String,
        val password: String,
    )

    private fun HttpRequestBuilder.configureDownloadTimeouts(
        connectTimeout: KotlinDuration,
    ) {
        require(connectTimeout.isPositive() && connectTimeout.isFinite()) {
            "HTTP connect timeout must be positive and finite: $connectTimeout"
        }
        timeout {
            requestTimeoutMillis = DOWNLOAD_REQUEST_TIMEOUT.inWholeMilliseconds
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
            socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT.inWholeMilliseconds
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
    // These tools are non-interactive. Closing stdin also lets a descendant
    // that inherited the pipe observe EOF instead of outliving its launcher.
    val output = StringBuffer()
    val outputClosedByCaller = AtomicBoolean()
    val outputReaderFailure = AtomicReference<Throwable?>()
    val reader = Thread.ofVirtual().start {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach(output::appendLine)
            }
        } catch (failure: Throwable) {
            if (!outputClosedByCaller.get()) {
                outputReaderFailure.set(failure)
            }
        }
    }
    val observedProcesses = linkedMapOf<Long, ProcessHandle>()
    var completed = false
    var failure: Throwable? = null
    try {
        process.outputStream.close()
        completed = if (timeout == null) {
            process.waitFor()
            true
        } else {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }
        if (!completed) {
            observeProcessTree(process, observedProcesses)
        }
        check(completed) {
            "Process timed out: ${command.joinToString(" ")}"
        }
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        var cleanupFailure: Throwable? = null
        if (!completed) {
            try {
                observeProcessTree(process, observedProcesses)
                forceProcessTree(process, observedProcesses)
                check(
                    awaitProcessTreeExit(
                        observedProcesses,
                        PROCESS_FORCED_SHUTDOWN_TIMEOUT_SECONDS,
                    ),
                ) {
                    "Process tree did not exit: ${command.joinToString(" ")}"
                }
            } catch (caught: Throwable) {
                cleanupFailure = caught
            }
        }
        try {
            if (!reader.joinWithin(PROCESS_OUTPUT_DRAIN_TIMEOUT)) {
                outputClosedByCaller.set(true)
                process.inputStream.close()
                check(reader.joinWithin(PROCESS_OUTPUT_DRAIN_TIMEOUT)) {
                    "Process output reader did not stop: ${command.joinToString(" ")}"
                }
            }
        } catch (caught: Throwable) {
            cleanupFailure?.addSuppressed(caught)
                ?: run { cleanupFailure = caught }
        }
        outputReaderFailure.get()?.let { caught ->
            cleanupFailure?.addSuppressed(caught)
                ?: run { cleanupFailure = caught }
        }
        cleanupFailure?.let { caught ->
            failure?.addSuppressed(caught) ?: throw caught
        }
    }
    return ProcessResult(process.exitValue(), output.toString())
}

private fun Thread.joinWithin(timeout: Duration): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    var interrupted = false
    while (isAlive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0L) break
        try {
            val milliseconds = TimeUnit.NANOSECONDS.toMillis(remaining)
            val nanoseconds = (remaining - TimeUnit.MILLISECONDS.toNanos(milliseconds))
                .toInt()
            join(milliseconds, nanoseconds)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
    return !isAlive
}

internal fun observeProcessTree(
    process: Process,
    observedProcesses: MutableMap<Long, ProcessHandle>,
) {
    if (process.isAlive) {
        runCatching {
            process.descendants().use { descendants ->
                descendants.forEach { handle ->
                    observedProcesses.putIfAbsent(handle.pid(), handle)
                }
            }
        }
    }
    val rootHandle = process.toHandle()
    observedProcesses.putIfAbsent(rootHandle.pid(), rootHandle)
}

internal fun forceProcessTree(
    process: Process,
    observedProcesses: Map<Long, ProcessHandle>,
) {
    val rootPid = process.pid()
    observedProcesses.values
        .filter { handle -> handle.pid() != rootPid }
        .asReversed()
        .plus(observedProcesses[rootPid])
        .filterNotNull()
        .filter(ProcessHandle::isAlive)
        .forEach { handle -> runCatching { handle.destroyForcibly() } }
}

internal fun awaitProcessTreeExit(
    observedProcesses: Map<Long, ProcessHandle>,
    timeoutSeconds: Long,
): Boolean {
    val exits = observedProcesses.values
        .filter(ProcessHandle::isAlive)
        .map(ProcessHandle::onExit)
        .toTypedArray()
    if (exits.isEmpty()) return true
    return try {
        CompletableFuture.allOf(*exits)
            .get(timeoutSeconds, TimeUnit.SECONDS)
        true
    } catch (_: TimeoutException) {
        false
    }
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
)

internal fun Path.readOfficialMinecraftTargetReport(): OfficialMinecraftTargetReport {
    check(isRegularFile()) {
        "Official Minecraft target analysis is missing: $this"
    }
    val report = protocolJson.decodeFromString<JsonObject>(readText())
    check(report.getValue("schema_version").jsonPrimitive.int == 1) {
        "Unsupported official Minecraft target schema"
    }
    return OfficialMinecraftTargetReport(
        target = MinecraftProtocolTarget(
            minecraftVersion = report.getValue("minecraft_version").jsonPrimitive.content,
            protocolVersion = report.getValue("protocol_version").jsonPrimitive.int,
            javaMajorVersion = report.getValue("java_major_version").jsonPrimitive.int,
        ),
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
    val version = protocolJson.decodeFromString<JsonObject>(readZipEntry("version.json").decodeToString())
    val minecraftVersion = version.getValue("id").jsonPrimitive.content
    check(minecraftVersion == expectedVersion) {
        "Official server identifies Minecraft $minecraftVersion; build selects $expectedVersion"
    }
    return MinecraftProtocolTarget(
        minecraftVersion = minecraftVersion,
        protocolVersion = version.getValue("protocol_version").jsonPrimitive.int,
        javaMajorVersion = version.getValue("java_version").jsonPrimitive.int,
    )
}

internal fun Path.deleteTree() {
    if (!Files.exists(this, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder())
            .forEach(Files::delete)
    }
}

private val PROCESS_OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(5)
private const val PROCESS_FORCED_SHUTDOWN_TIMEOUT_SECONDS = 5L
