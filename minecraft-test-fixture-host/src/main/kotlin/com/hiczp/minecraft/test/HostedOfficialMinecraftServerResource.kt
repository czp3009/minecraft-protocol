package com.hiczp.minecraft.test

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A ready official-server process and its isolated loopback endpoint. */
internal class HostedOfficialMinecraftServerResource private constructor(
    private val serverArtifact: OfficialServerArtifact,
    private val layout: MinecraftTestLayout,
    private val workDirectory: Path,
    private val configuration: OfficialMinecraftServerConfiguration,
    launched: LaunchedOfficialServer,
) : AutoCloseable {
    private val processMutex = Mutex()
    private lateinit var managedResource: ManagedMinecraftTestResource

    @Volatile
    private var process = launched.process

    @Volatile
    private var currentEndpoint = launched.endpoint

    val endpoint: MinecraftTestEndpoint
        get() = currentEndpoint

    val minecraftVersion: String
        get() = layout.minecraftVersion

    val officialServerSha256: String
        get() = serverArtifact.sha256

    val isAlive: Boolean
        get() = process.isAlive

    val exitCode: Int
        get() = process.exitCode

    /** The semantic world path owned by this server resource. */
    val worldDirectory: Path = workDirectory.safeResolve(
        configuration.properties["level-name"] ?: DEFAULT_WORLD_NAME,
    )

    fun logText(): String = process.logText()

    suspend fun waitForLog(
        marker: String,
        timeout: Duration = SERVER_EVENT_TIMEOUT,
    ) {
        process.waitForLog(marker, timeout)
    }

    suspend fun sendCommand(command: String) {
        processMutex.withLock {
            check(managedResource.isOpen) { "Official server is closing" }
            process.sendLine(command)
        }
    }

    /** Requests a clean stop while retaining this resource's work directory. */
    suspend fun stop(): Int? = processMutex.withLock {
        stopLocked()
    }

    /** Restarts the official server in the same isolated world directory. */
    suspend fun restart() {
        processMutex.withLock {
            check(managedResource.isOpen) { "Official server is closing" }
            if (process.isAlive) {
                val exitCode = stopLocked()
                check(exitCode == 0) {
                    "Official server did not stop cleanly before restart: $exitCode"
                }
            }
            val relaunched = launchOfficialServer(
                layout = layout,
                artifact = serverArtifact,
                workDirectory = workDirectory,
                configuration = configuration,
            )
            process = relaunched.process
            currentEndpoint = relaunched.endpoint
        }
    }

    override fun close() {
        managedResource.close()
    }

    fun invokeOnCleanupCompletion(handler: (Throwable?) -> Unit) {
        managedResource.invokeOnCleanupCompletion(handler)
    }

    internal fun attach(resource: ManagedMinecraftTestResource) {
        check(!::managedResource.isInitialized)
        managedResource = resource
    }

    internal suspend fun cleanup() {
        processMutex.withLock {
            if (process.isAlive) {
                process.terminate(
                    gracefulTimeout = configuration.stopTimeout,
                )
            }
        }
    }

    private suspend fun stopLocked(): Int? {
        if (!process.isAlive) return process.exitCode
        process.sendLine("stop")
        return process.awaitExitWithin(configuration.stopTimeout)
    }

    internal companion object {
        suspend fun start(
            layout: MinecraftTestLayout,
            workDirectory: Path,
            configuration: OfficialMinecraftServerConfiguration,
        ): HostedOfficialMinecraftServerResource {
            val artifact = OfficialArtifacts.server(layout)
            workDirectory.ensureDirectory()
            Path(workDirectory, "eula.txt").writeText("eula=true\n")
            val launched = launchOfficialServer(
                layout = layout,
                artifact = artifact,
                workDirectory = workDirectory,
                configuration = configuration,
            )
            return HostedOfficialMinecraftServerResource(
                serverArtifact = artifact,
                layout = layout,
                workDirectory = workDirectory,
                configuration = configuration,
                launched = launched,
            )
        }
    }
}

private data class LaunchedOfficialServer(
    val endpoint: MinecraftTestEndpoint,
    val process: MinecraftTestProcess,
)

private suspend fun launchOfficialServer(
    layout: MinecraftTestLayout,
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
            command = listOf(
                layout.javaExecutable.toString(),
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
            awaitStatusResponse(
                process = process,
                host = LOOPBACK,
                port = port,
                timeout = configuration.startupTimeout,
            )
            return LaunchedOfficialServer(
                endpoint = MinecraftTestEndpoint(LOOPBACK, port),
                process = process,
            )
        } catch (failure: Throwable) {
            val diagnostic = process.logText()
            runCatching { process.terminate() }
                .onFailure(failure::addSuppressed)
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
    Path(workDirectory, "server.properties").writeText(
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
private val STATUS_POLL_INTERVAL = 25.milliseconds
private val STATUS_SOCKET_TIMEOUT = 2.seconds
private val SERVER_EVENT_TIMEOUT = 30.seconds
