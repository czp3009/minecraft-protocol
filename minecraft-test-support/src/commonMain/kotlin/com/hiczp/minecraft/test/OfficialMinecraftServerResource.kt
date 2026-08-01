package com.hiczp.minecraft.test

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class MinecraftTestEndpoint(
    val host: String,
    val port: Int,
)

data class OfficialMinecraftServerConfiguration(
    /** `0` asks the operating system to select an available loopback port. */
    val port: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    val startupTimeout: Duration = 2.minutes,
    val stopTimeout: Duration = 30.seconds,
    val maximumBindAttempts: Int = 5,
    val threadName: String = "official-minecraft-server",
    val logFile: Path? = null,
    val maximumLogCharacters: Int = 300_000,
) {
    init {
        require(port in 0..65_535) {
            "port must be 0 (automatic) or a valid TCP port"
        }
        require(startupTimeout.isPositive() && startupTimeout.isFinite()) {
            "startupTimeout must be positive and finite"
        }
        require(stopTimeout.isPositive() && stopTimeout.isFinite()) {
            "stopTimeout must be positive and finite"
        }
        require(maximumBindAttempts > 0) {
            "maximumBindAttempts must be positive"
        }
        require(threadName.isNotBlank()) { "threadName must not be blank" }
        require(maximumLogCharacters > 0) {
            "maximumLogCharacters must be positive"
        }
    }
}

/** A ready official-server process and its isolated loopback endpoint. */
class OfficialMinecraftServerResource private constructor(
    val endpoint: MinecraftTestEndpoint,
    val serverArtifact: OfficialServerArtifact,
    private val process: MinecraftTestProcess,
    private val stopTimeout: Duration,
) : AutoCloseable {
    fun logText(): String = process.logText()

    suspend fun waitForLog(marker: String, timeout: Duration) {
        process.waitForLog(marker, timeout)
    }

    suspend fun sendCommand(command: String) {
        process.sendLine(command)
    }

    suspend fun stop(): Int? {
        if (process.isAlive) process.sendLine("stop")
        return process.awaitExitWithin(stopTimeout)
    }

    override fun close() {
        process.close()
    }

    internal companion object {
        suspend fun start(
            environment: MinecraftTestEnvironment,
            workDirectory: Path,
            configuration: OfficialMinecraftServerConfiguration,
        ): OfficialMinecraftServerResource {
            val artifact = prepareServer(environment, workDirectory)

            val automaticPort = configuration.port == 0
            val bindAttempts = if (automaticPort) {
                configuration.maximumBindAttempts
            } else {
                1
            }
            var lastBindFailure: Throwable? = null
            repeat(bindAttempts) { attempt ->
                val port = if (automaticPort) {
                    selectAvailableLoopbackPort()
                } else {
                    configuration.port
                }
                writeProperties(
                    workDirectory = workDirectory,
                    port = port,
                    overrides = configuration.properties,
                )
                val process = MinecraftTestProcess.start(
                    command = listOf(
                        environment.javaExecutable.toString(),
                        "-Djava.awt.headless=true",
                        "-Djoml.nounsafe=true",
                        "-jar",
                        artifact.jar.toString(),
                        "nogui",
                    ),
                    workingDirectory = workDirectory,
                    threadName = configuration.threadName,
                    logFile = configuration.logFile
                        ?: Path(workDirectory, "server.log"),
                    maximumLogCharacters =
                        configuration.maximumLogCharacters,
                )
                try {
                    awaitStatusResponse(
                        process = process,
                        host = LOOPBACK,
                        port = port,
                        timeout = configuration.startupTimeout,
                    )
                    return OfficialMinecraftServerResource(
                        endpoint = MinecraftTestEndpoint(LOOPBACK, port),
                        serverArtifact = artifact,
                        process = process,
                        stopTimeout = configuration.stopTimeout,
                    )
                } catch (failure: Throwable) {
                    val diagnostic = process.logText()
                    process.close()
                    if (failure is CancellationException) throw failure
                    if (
                        attempt + 1 == bindAttempts ||
                        !diagnostic.isPortBindFailure()
                    ) {
                        throw AssertionError(
                            "Official server failed to become ready.\n" +
                                    "--- official server log ---\n" +
                                    diagnostic,
                            failure,
                        )
                    }
                    lastBindFailure = failure
                }
            }
            throw AssertionError(
                "Official server could not acquire a loopback port after " +
                        "$bindAttempts attempts",
                lastBindFailure,
            )
        }

        private suspend fun prepareServer(
            environment: MinecraftTestEnvironment,
            workDirectory: Path,
        ): OfficialServerArtifact {
            val artifact = environment.officialServer()
            workDirectory.ensureDirectory()
            Path(workDirectory, "eula.txt").atomicWriteText("eula=true\n")
            return artifact
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
                            "Official server exited with $exitCode while " +
                                    "waiting for its status protocol",
                        )
                    }
                }
            }
            throw AssertionError(
                "Official server did not complete a status request and pong " +
                        "within $timeout",
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
                "level-name" to "official-test-world-$port",
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
                    "Unsafe official server property name: $name"
                }
                require(value.none { it == '\n' || it == '\r' }) {
                    "Unsafe official server property value for $name"
                }
                properties[name] = value
            }
            properties["enable-status"] = "true"
            properties["online-mode"] = "false"
            properties["server-ip"] = LOOPBACK
            properties["server-port"] = port.toString()
            Path(workDirectory, "server.properties").atomicWriteText(
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

        private val STATUS_POLL_INTERVAL = 25.milliseconds
        private val STATUS_SOCKET_TIMEOUT = 2.seconds
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

suspend fun MinecraftTestEnvironment.startOfficialMinecraftServer(
    workDirectory: Path,
    configuration: OfficialMinecraftServerConfiguration =
        OfficialMinecraftServerConfiguration(),
): OfficialMinecraftServerResource =
    OfficialMinecraftServerResource.start(
        environment = this,
        workDirectory = workDirectory,
        configuration = configuration,
    )

private const val LOOPBACK = "127.0.0.1"
