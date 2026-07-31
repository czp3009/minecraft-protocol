package com.hiczp.minecraft.test

import kotlinx.serialization.json.*
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
import kotlin.io.path.*

internal val testJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

internal fun Path.readJsonObject(): JsonObject =
    testJson.parseToJsonElement(readText()).jsonObject

internal fun ByteArray.decodeJsonObject(source: String): JsonObject =
    try {
        testJson.parseToJsonElement(toString(StandardCharsets.UTF_8))
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

internal fun JsonObject.optionalBoolean(name: String): Boolean? =
    get(name)?.jsonPrimitive?.let { value ->
        check(!value.isString) { "JSON property '$name' is not a Boolean" }
        value.booleanOrNull
            ?: error("JSON property '$name' is not a Boolean")
    }

internal fun Path.writeJson(value: JsonElement) {
    atomicWriteText(testJson.encodeToString(JsonElement.serializer(), value) + "\n")
}

internal fun Path.atomicWriteText(content: String) {
    atomicWrite(content.toByteArray(StandardCharsets.UTF_8))
}

internal fun Path.atomicWrite(content: ByteArray) {
    parent.createDirectories()
    val temporary = Files.createTempFile(parent, ".$name.", ".tmp")
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

internal fun ByteArray.sha1(): String =
    MessageDigest.getInstance("SHA-1").digest(this).toHexString()

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHexString()

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

internal fun Path.safeResolve(relative: String): Path {
    val base = toAbsolutePath().normalize()
    val resolved = base.resolve(relative).normalize()
    require(resolved.startsWith(base)) {
        "Path escapes $base: $relative"
    }
    return resolved
}

internal fun Path.deleteTree() {
    if (!exists()) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

internal inline fun <T> Path.withExclusiveLock(block: () -> T): T {
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

internal object TestHttp {
    private const val USER_AGENT =
        "minecraft-protocol test-support/1.0 " +
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

    fun getJson(url: String): JsonObject =
        getBytes(url).decodeJsonObject(url)

    fun ensureDownload(
        url: String,
        destination: Path,
        expectedSize: Long,
        digestAlgorithm: String,
        expectedDigest: String,
        timeout: Duration = Duration.ofSeconds(60),
        attempts: Int = 4,
    ) {
        if (
            destination.isRegularFile() &&
            Files.size(destination) == expectedSize &&
            destination.digestForDownload(digestAlgorithm) == expectedDigest
        ) {
            return
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
                    "Downloaded artifact has the wrong size: $url"
                }
                check(
                    temporary.digestForDownload(digestAlgorithm) ==
                            expectedDigest,
                ) {
                    "Downloaded artifact failed $digestAlgorithm: $url"
                }
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return
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

    private fun Path.digestForDownload(algorithm: String): String =
        when (algorithm) {
            "SHA-1" -> sha1()
            "SHA-256" -> sha256()
            else -> error("Unsupported digest algorithm: $algorithm")
        }
}

internal fun jsonObjectOf(
    vararg entries: Pair<String, JsonElement>,
): JsonObject = JsonObject(linkedMapOf(*entries))

internal fun jsonString(value: String): JsonPrimitive = JsonPrimitive(value)

internal fun jsonNumber(value: Number): JsonPrimitive = JsonPrimitive(value)

internal fun JsonElement.objectOrNull(): JsonObject? =
    takeUnless { it === JsonNull }?.jsonObject
