package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.MinecraftTestEndpoint
import com.hiczp.minecraft.test.MinecraftTestResourceStatus
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.Volatile
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A ready official-server process and its isolated loopback endpoint. */
internal class HostedOfficialMinecraftServerResource private constructor(
    private val serverArtifact: OfficialServerArtifact,
    val workDirectory: Path,
    private val configuration: OfficialMinecraftServerConfiguration,
    initialLaunch: LaunchedOfficialServer,
) : AutoCloseable {
    private val processMutex = Mutex()
    private lateinit var managedResource: ManagedMinecraftTestResource

    @Volatile
    private var launched = initialLaunch

    val endpoint: MinecraftTestEndpoint
        get() = launched.endpoint

    fun logText(): String = launched.process.logText()

    fun status(): MinecraftTestResourceStatus {
        val current = launched.process
        val alive = current.isAlive
        return MinecraftTestResourceStatus(
            alive = alive,
            exitCode = if (alive) null else current.exitCode,
        )
    }

    suspend fun waitForLog(
        marker: String,
        timeout: Duration,
    ) {
        launched.process.waitForLog(marker, timeout)
    }

    suspend fun sendCommand(
        command: String,
        expectedNewOutput: String?,
        timeout: Duration,
    ) {
        processMutex.withLock {
            check(managedResource.isOpen) { "Official server is closing" }
            val process = launched.process
            if (expectedNewOutput == null) {
                process.sendLine(command)
            } else {
                process.sendLineAndWait(command, expectedNewOutput, timeout)
            }
        }
    }

    /** Closes the process while retaining this resource's work directory. */
    suspend fun closeProcess(): Int = processMutex.withLock {
        check(managedResource.isOpen) { "Official server is closing" }
        closeProcessLocked()
    }

    suspend fun awaitExit(): Int = launched.process.awaitExit()

    /** Restarts the official server in the same isolated world directory. */
    suspend fun restart() {
        processMutex.withLock {
            check(managedResource.isOpen) { "Official server is closing" }
            val process = launched.process
            if (process.isAlive) {
                val exitCode = closeProcessLocked()
                check(exitCode == 0) {
                    "Official server did not stop cleanly before restart: $exitCode"
                }
            }
            launched = launchOfficialServer(
                artifact = serverArtifact,
                workDirectory = workDirectory,
                configuration = configuration,
            )
        }
    }

    override fun close() {
        managedResource.close()
    }

    fun invokeOnCleanupCompletion(handler: (Throwable?) -> Unit) {
        managedResource.invokeOnCleanupCompletion(handler)
    }

    suspend fun beginWorkingDirectoryDeletion() {
        processMutex.withLock {
            check(!launched.process.isAlive) {
                "Official server must be stopped before deleting its working directory"
            }
            managedResource.close()
        }
    }

    suspend fun awaitCleanup() = managedResource.awaitCleanup()

    internal fun attach(resource: ManagedMinecraftTestResource) {
        check(!::managedResource.isInitialized)
        managedResource = resource
    }

    internal suspend fun cleanup() {
        processMutex.withLock {
            closeProcessLocked()
        }
    }

    private suspend fun closeProcessLocked(): Int {
        val process = launched.process
        if (!process.isAlive) return process.exitCode
        return process.terminate(
            gracefulTimeout = configuration.stopTimeout,
        )
    }

    internal companion object {
        suspend fun start(
            layout: MinecraftTestLayout,
            workDirectory: Path,
            configuration: OfficialMinecraftServerConfiguration,
        ): HostedOfficialMinecraftServerResource {
            val artifact = prepareOfficialServerWorkspace(
                preparedArtifact = OfficialArtifacts.server(layout),
                workDirectory = workDirectory,
                configuration = configuration,
            )
            workDirectory.resolve("eula.txt").writeText("eula=true\n")
            val launched = launchOfficialServer(
                artifact = artifact,
                workDirectory = workDirectory,
                configuration = configuration,
            )
            return HostedOfficialMinecraftServerResource(
                serverArtifact = artifact,
                workDirectory = workDirectory,
                configuration = configuration,
                initialLaunch = launched,
            )
        }
    }
}

internal fun prepareOfficialServerWorkspace(
    preparedArtifact: OfficialServerArtifact,
    workDirectory: Path,
    configuration: OfficialMinecraftServerConfiguration,
): OfficialServerArtifact {
    workDirectory.createDirectories()
    preparedArtifact.runtimeDirectory.linkTreeTo(
        destination = workDirectory,
        excludedRelativePaths = SERVER_SYMBOLIC_RUNTIME_DIRECTORIES,
    )
    SERVER_SYMBOLIC_RUNTIME_DIRECTORIES.forEach { relativePath ->
        preparedArtifact.runtimeDirectory.safeResolve(relativePath)
            .linkDirectoryTo(workDirectory.safeResolve(relativePath))
    }
    val runtimeJar = workDirectory.resolve("server.jar")
    if (!configuration.usesDefaultTemplate()) {
        return preparedArtifact.copy(
            runtimeDirectory = workDirectory,
            jar = runtimeJar,
        )
    }
    preparedArtifact.templateDirectory.copyTreeTo(workDirectory)
    val levelName = configuration.properties["level-name"]
        ?: DEFAULT_WORLD_NAME
    val targetWorld = workDirectory.safeResolve(levelName)
    val defaultWorld = workDirectory.resolve(DEFAULT_WORLD_NAME)
    if (targetWorld != defaultWorld && defaultWorld.isDirectory()) {
        check(!targetWorld.exists()) {
            "Template target world already exists: $targetWorld"
        }
        targetWorld.parent?.createDirectories()
        Files.move(
            defaultWorld,
            targetWorld,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
    return preparedArtifact.copy(
        runtimeDirectory = workDirectory,
        jar = runtimeJar,
    )
}

internal fun OfficialMinecraftServerConfiguration.usesDefaultTemplate(): Boolean =
    this == OfficialMinecraftServerConfiguration()

private data class LaunchedOfficialServer(
    val endpoint: MinecraftTestEndpoint,
    val process: MinecraftTestProcess,
)

private suspend fun launchOfficialServer(
    artifact: OfficialServerArtifact,
    workDirectory: Path,
    configuration: OfficialMinecraftServerConfiguration,
): LaunchedOfficialServer {
    var lastBindFailure: Throwable? = null
    repeat(configuration.maximumBindAttempts) { attempt ->
        val port = selectAvailableLoopbackPort()
        writeProperties(
            workDirectory = workDirectory,
            port = port,
            overrides = configuration.properties,
        )
        val process = MinecraftTestProcess.start(
            command = fixtureJavaCommand(
                "-Djava.awt.headless=true",
                "-Djoml.nounsafe=true",
                "-jar",
                artifact.jar.toString(),
                "nogui",
            ),
            workingDirectory = workDirectory,
            threadName = "official-minecraft-server",
            shutdownCommand = "stop",
        )
        try {
            val startedAt = TimeSource.Monotonic.markNow()
            process.waitForLog(
                OFFICIAL_SERVER_DONE_MARKER,
                configuration.startupTimeout,
            )
            awaitStatusResponse(
                process = process,
                host = LOOPBACK,
                port = port,
                timeout = configuration.startupTimeout - startedAt.elapsedNow(),
            )
            return LaunchedOfficialServer(
                endpoint = MinecraftTestEndpoint(LOOPBACK, port),
                process = process,
            )
        } catch (failure: Throwable) {
            val diagnostic = process.logText()
            terminateAfterFailure(process, failure)
            if (failure is CancellationException) throw failure
            if (
                attempt + 1 == configuration.maximumBindAttempts ||
                !diagnostic.isPortBindFailure()
            ) {
                throw AssertionError(
                    """
                    |Official server failed to become ready.
                    |--- official server log ---
                    |$diagnostic
                    """.trimMargin(),
                    failure,
                )
            }
            lastBindFailure = failure
        }
    }
    throw AssertionError(
        "Official server could not acquire a loopback port after ${configuration.maximumBindAttempts} attempts",
        lastBindFailure,
    )
}

private suspend fun awaitStatusResponse(
    process: MinecraftTestProcess,
    host: String,
    port: Int,
    timeout: Duration,
) {
    val startedAt = TimeSource.Monotonic.markNow()
    var lastFailure: Throwable? = null
    SelectorManager(Dispatchers.Default).use { selector ->
        val probe = MinecraftStatusProbe(selector)
        while (startedAt.elapsedNow() < timeout) {
            process.requireAlive("Official server during status polling")
            val remaining = timeout - startedAt.elapsedNow()
            try {
                probe.query(
                    host = host,
                    port = port,
                    socketTimeoutMillis = minOf(
                        remaining.inWholeMilliseconds.coerceAtLeast(1),
                        STATUS_SOCKET_TIMEOUT.inWholeMilliseconds,
                    ),
                )
                return
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                lastFailure = failure
            }

            val remainingAfterProbe = timeout - startedAt.elapsedNow()
            if (!remainingAfterProbe.isPositive()) break
            val exitCode = process.awaitExitWithin(
                minOf(remainingAfterProbe, STATUS_POLL_INTERVAL),
            )
            if (exitCode != null) {
                error(
                    "Official server exited with $exitCode while waiting for its status protocol",
                )
            }
        }
    }
    throw AssertionError(
        "Official server did not complete a status request and pong within $timeout",
        lastFailure,
    )
}

private fun writeProperties(
    workDirectory: Path,
    port: Int,
    overrides: Map<String, String>,
) {
    val properties = linkedMapOf(
        "accepts-transfers" to "false",
        "enable-query" to "false",
        "enable-rcon" to "false",
        "enable-status" to "true",
        "enforce-secure-profile" to "false",
        "generate-structures" to "false",
        "level-name" to DEFAULT_WORLD_NAME,
        "level-seed" to "8675309",
        "level-type" to "minecraft:flat",
        "management-server-enabled" to "false",
        "max-players" to "1",
        "max-tick-time" to "-1",
        "motd" to "minecraft-protocol official test",
        "network-compression-threshold" to "64",
        "online-mode" to "false",
        "pause-when-empty-seconds" to "-1",
        "server-ip" to LOOPBACK,
        "server-port" to port.toString(),
        "simulation-distance" to "2",
        "spawn-protection" to "0",
        "sync-chunk-writes" to "false",
        "view-distance" to "2",
    )
    overrides.forEach { (name, value) ->
        require(
            name.isNotBlank() &&
                    name.none { it == '=' || it == '\n' || it == '\r' },
        ) {
            "Invalid official server property name: $name"
        }
        require(value.none { it == '\n' || it == '\r' }) {
            "Invalid official server property value for $name"
        }
        properties[name] = value
    }
    properties["enable-status"] = "true"
    properties["online-mode"] = "false"
    properties["server-ip"] = LOOPBACK
    properties["server-port"] = port.toString()
    workDirectory.resolve("server.properties").writeText(
        properties.entries.sortedBy { it.key }.joinToString(
            separator = "\n",
            postfix = "\n",
        ) { (name, value) -> "$name=$value" },
    )
}

private fun String.isPortBindFailure(): Boolean =
    listOf(
        "failed to bind to port",
        "address already in use",
        "bindexception",
    ).any { marker -> contains(marker, ignoreCase = true) }

internal suspend fun generateOfficialMinecraftServerTemplate(
    minecraftVersion: String,
    serverJar: Path,
    outputRoot: Path,
    workRoot: Path,
) {
    outputRoot.deleteTree()
    val candidate = createUniqueDirectory(workRoot)
    var published = false
    try {
        candidate.resolve("eula.txt").writeText("eula=true\n")
        val configuration = OfficialMinecraftServerConfiguration(
        )
        val launched = launchOfficialServer(
            artifact = OfficialServerArtifact(
                runtimeDirectory = candidate,
                jar = serverJar,
                templateDirectory = candidate.resolve("unused-template"),
            ),
            workDirectory = candidate,
            configuration = configuration,
        )
        val exitCode = try {
            launched.process.terminate(
                gracefulTimeout = configuration.stopTimeout,
            )
        } catch (failure: Throwable) {
            terminateAfterFailure(launched.process, failure)
            throw failure
        }
        check(exitCode == 0) {
            "Official server template process exited with $exitCode:\n${launched.process.logText()}"
        }
        check(candidate.resolve(DEFAULT_WORLD_NAME).isDirectory()) {
            "Official server template did not generate the default world"
        }
        val runtimeDirectory = outputRoot.resolve("runtime")
        serverJar.copyFileTo(runtimeDirectory.resolve("server.jar"))
        SERVER_EXTRACTED_RUNTIME_DIRECTORIES.forEach { name ->
            candidate.safeResolve(name).copyTreeTo(
                runtimeDirectory.safeResolve(name),
            )
        }
        val templateDirectory = outputRoot.resolve("template")
        candidate.copyTreeTo(templateDirectory)
        SERVER_EXTRACTED_RUNTIME_DIRECTORIES.forEach { name ->
            templateDirectory.safeResolve(name).deleteTree()
        }
        SERVER_TEMPLATE_CLEARED_DIRECTORIES.forEach { relativePath ->
            templateDirectory.safeResolve(relativePath).deleteFilesRecursively()
        }
        SERVER_TEMPLATE_IGNORED_FILES.forEach { relativePath ->
            templateDirectory.safeResolve(relativePath).deleteTree()
        }
        SERVER_TEMPLATE_CLEARED_DIRECTORIES.forEach { relativePath ->
            check(templateDirectory.safeResolve(relativePath).isDirectory()) {
                "Official server template lost reusable directory $relativePath"
            }
        }
        SERVER_TEMPLATE_IGNORED_FILES.forEach { relativePath ->
            check(!templateDirectory.safeResolve(relativePath).exists()) {
                "Official server template retained mutable file $relativePath"
            }
        }
        val runtimeEntries = runtimeDirectory.listDirectoryEntries()
            .map { path -> path.fileName.toString() }
            .sorted()
        check(runtimeEntries == SERVER_RUNTIME_ENTRIES) {
            "Official server runtime contains unexpected entries: $runtimeEntries"
        }
        val templateEntries = templateDirectory.listDirectoryEntries()
            .map { path -> path.fileName.toString() }
            .sorted()
        val excludedMutableEntries =
            SERVER_TEMPLATE_CLEARED_DIRECTORIES.map { directory -> "$directory/**" } + SERVER_TEMPLATE_IGNORED_FILES
        val manifest = JsonObject(
            linkedMapOf(
                "schema_version" to JsonPrimitive(1),
                "minecraft_version" to JsonPrimitive(minecraftVersion),
                "relative_server_runtime_directory" to
                        JsonPrimitive("runtime"),
                "relative_server_jar" to
                        JsonPrimitive("runtime/server.jar"),
                "relative_template_directory" to
                        JsonPrimitive("template"),
                "default_world_name" to
                        JsonPrimitive(DEFAULT_WORLD_NAME),
                "runtime_entries" to JsonArray(
                    runtimeEntries.map(::JsonPrimitive),
                ),
                "template_entries" to JsonArray(
                    templateEntries.map(::JsonPrimitive),
                ),
                "excluded_mutable_entries" to JsonArray(
                    excludedMutableEntries.map(::JsonPrimitive),
                ),
                "template_policy_revision" to JsonPrimitive(2),
                "ready_signal" to JsonPrimitive("done-log-status-and-pong"),
                "clean_stop" to JsonPrimitive(true),
            ),
        )
        outputRoot.resolve("manifest.json").writeText(
            "${testJson.encodeToString(manifest)}\n",
        )
        published = true
    } catch (failure: Throwable) {
        deleteTreesPreserving(failure, candidate, outputRoot)
        throw failure
    } finally {
        if (published) candidate.deleteTree()
    }
}

private suspend fun terminateAfterFailure(
    process: MinecraftTestProcess,
    failure: Throwable,
) = withContext(NonCancellable) {
    try {
        process.terminate()
    } catch (cleanupFailure: Throwable) {
        failure.addSuppressed(cleanupFailure)
    }
}

internal suspend fun selectAvailableLoopbackPort(): Int =
    SelectorManager(Dispatchers.Default).use { selector ->
        val socket = aSocket(selector).tcp().bind(LOOPBACK, 0) {
            reuseAddress = true
        }
        val port = socket.port
        socket.close()
        socket.awaitClosed()
        port
    }

private const val LOOPBACK = "127.0.0.1"
private const val DEFAULT_WORLD_NAME = "world"
private const val OFFICIAL_SERVER_DONE_MARKER = "[Server thread/INFO]: Done ("
private val SERVER_EXTRACTED_RUNTIME_DIRECTORIES = listOf(
    "libraries",
    "versions",
)
private val SERVER_RUNTIME_ENTRIES = listOf(
    "libraries",
    "server.jar",
    "versions",
)
private val SERVER_SYMBOLIC_RUNTIME_DIRECTORIES = setOf(
    "libraries",
)
private val SERVER_TEMPLATE_CLEARED_DIRECTORIES = listOf(
    "logs",
)
private val SERVER_TEMPLATE_IGNORED_FILES = listOf(
    "server.properties",
)

private val STATUS_POLL_INTERVAL = 25.milliseconds
private val STATUS_SOCKET_TIMEOUT = 2.seconds
