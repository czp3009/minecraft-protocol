package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
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

internal val protocolJson = Json {
    ignoreUnknownKeys = false
}

abstract class MinecraftProtocolToolTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    init {
        minecraftVersion.convention(MinecraftTarget.version)
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
): String = buildString {
    appendJson(value, 0, sortKeys)
}

private fun StringBuilder.appendJson(
    value: JsonElement,
    depth: Int,
    sortKeys: Boolean,
) {
    when (value) {
        JsonNull -> append("null")
        is JsonPrimitive -> {
            if (value.isString) {
                appendJsonString(value.content)
            } else {
                append(value.toString())
            }
        }

        is JsonArray -> {
            if (value.isEmpty()) {
                append("[]")
                return
            }
            append("[\n")
            value.forEachIndexed { index, element ->
                append("  ".repeat(depth + 1))
                appendJson(element, depth + 1, sortKeys)
                if (index != value.lastIndex) append(',')
                append('\n')
            }
            append("  ".repeat(depth))
            append(']')
        }

        is JsonObject -> {
            if (value.isEmpty()) {
                append("{}")
                return
            }
            val entries = if (sortKeys) {
                value.entries.sortedBy { it.key }
            } else {
                value.entries.toList()
            }
            append("{\n")
            entries.forEachIndexed { index, (key, element) ->
                append("  ".repeat(depth + 1))
                appendJsonString(key)
                append(": ")
                appendJson(element, depth + 1, sortKeys)
                if (index != entries.lastIndex) append(',')
                append('\n')
            }
            append("  ".repeat(depth))
            append('}')
        }
    }
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code in 0x20..0x7E) {
                    append(character)
                } else {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                }
            }
        }
    }
    append('"')
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

internal inline fun <T> Path.withExclusiveFileLock(block: () -> T): T {
    parent.createDirectories()
    return FileChannel.open(
        this,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    ).use { channel ->
        channel.lock().use {
            block()
        }
    }
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

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

internal object ProtocolHttp {
    private const val USER_AGENT =
        "minecraft-protocol Gradle tools/1.0 " +
                "(https://github.com/hiczp/minecraft-protocol)"

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun getBytes(
        url: String,
        timeout: Duration = Duration.ofSeconds(60),
        attempts: Int = 4,
    ): ByteArray {
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
                val response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                check(response.statusCode() in 200..299) {
                    "HTTP ${response.statusCode()} for $url"
                }
                return response.body()
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt + 1 < attempts) {
                    Thread.sleep(500L shl attempt)
                }
            }
        }
        throw IOException(
            "Request failed after $attempts attempts: $url",
            lastFailure,
        )
    }

    fun getJson(
        url: String,
        timeout: Duration = Duration.ofSeconds(60),
    ): JsonObject = getBytes(url, timeout).decodeJsonObject(url)

    fun ensureDownload(
        url: String,
        destination: Path,
        expectedSize: Long,
        expectedSha1: String,
        offline: Boolean = false,
        timeout: Duration = Duration.ofSeconds(60),
        attempts: Int = 4,
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
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            val temporary = Files.createTempFile(
                destination.parent,
                ".${destination.name}.",
                ".download",
            )
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
                val response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
                check(response.statusCode() in 200..299) {
                    "HTTP ${response.statusCode()} for $url"
                }
                response.body().use { input ->
                    Files.newOutputStream(
                        temporary,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    ).use(input::copyTo)
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
            } catch (failure: Throwable) {
                lastFailure = failure
                Files.deleteIfExists(temporary)
                if (attempt + 1 < attempts) {
                    Thread.sleep(500L shl attempt)
                }
            }
        }
        throw IOException(
            "Artifact download failed after $attempts attempts: $url",
            lastFailure,
        )
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
