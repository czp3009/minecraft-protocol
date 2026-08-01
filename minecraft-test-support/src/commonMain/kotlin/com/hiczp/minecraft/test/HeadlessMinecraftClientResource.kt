package com.hiczp.minecraft.test

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

private val headlessClientLogger = DirectLoggerFactory.logger(
    "com.hiczp.minecraft.test.HeadlessMinecraftClientResource",
)

data class HeadlessMinecraftClientConfiguration(
    val playerName: String,
    val endpoint: MinecraftTestEndpoint,
    val downloadWorkers: Int = DEFAULT_CLIENT_DOWNLOAD_WORKERS,
    val threadName: String = "official-headless-client",
    val logFile: Path? = null,
    val maximumLogCharacters: Int = 300_000,
) {
    init {
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            "Unsafe offline player name: $playerName"
        }
        require(endpoint.host == "127.0.0.1" && endpoint.port in 1..0xFFFF) {
            "Headless client tests require a valid loopback endpoint"
        }
        require(downloadWorkers > 0) { "downloadWorkers must be positive" }
        require(threadName.isNotBlank()) { "threadName must not be blank" }
        require(maximumLogCharacters > 0) {
            "maximumLogCharacters must be positive"
        }
    }
}

/** A complete official-client + HeadlessMC process test resource. */
class HeadlessMinecraftClientResource private constructor(
    val installation: OfficialClientInstallation,
    private val process: MinecraftTestProcess,
) : AutoCloseable {
    val isAlive: Boolean
        get() = process.isAlive

    val exitCode: Int
        get() = process.exitCode

    fun logText(): String = process.logText()

    suspend fun awaitExit(): Int = process.awaitExit()

    override fun close() {
        process.close()
    }

    internal companion object {
        suspend fun start(
            preparedClient: PreparedHeadlessMinecraftClient,
            workDirectory: Path,
            configuration: HeadlessMinecraftClientConfiguration,
        ): HeadlessMinecraftClientResource {
            val installation = preparedClient.installation
            val launcher = preparedClient.launcher
            require(installation.directory.isDirectory()) {
                "Prepared Minecraft client directory does not exist: " +
                        installation.directory
            }
            require(launcher.isRegularFile()) {
                "HeadlessMC launcher does not exist: $launcher"
            }

            val runDirectory = prepareRunDirectory(workDirectory)
            val gameDirectory = Path(runDirectory, "game")
            val java = preparedClient.javaExecutable.toString()
            val endpoint = configuration.endpoint
            val command = listOf(
                java,
                "-Xms256M",
                "-Xmx1G",
                "-Djava.awt.headless=true",
                "-Djoml.nounsafe=true",
                "--sun-misc-unsafe-memory-access=allow",
                "--enable-native-access=ALL-UNNAMED",
                "-Dhmc.mcdir=${installation.directory}",
                "-Dhmc.gamedir=$gameDirectory",
                "-Dhmc.java.versions=$java",
                "-Dhmc.no.auto.config=true",
                "-Dhmc.java.use.current=false",
                "-Dhmc.java.require.exact=true",
                "-Dhmc.auto.download.java=false",
                "-Dhmc.auto.download.versions=false",
                "-Dhmc.account.refresh.on.game.launch=false",
                "-Dhmc.account.refresh.on.launch=false",
                "-Dhmc.store.accounts=false",
                "-Dhmc.offline=true",
                "-Dhmc.offline.username=${configuration.playerName}",
                "-Dhmc.offline.uuid=${offlineUuid(configuration.playerName)}",
                "-Dhmc.offline.token=0",
                "-Dhmc.gameargs=--quickPlayMultiplayer " +
                        "${endpoint.host}:${endpoint.port}",
                "-Dhmc.jline.enabled=false",
                "-Dhmc.filehandler.enabled=false",
                "-Dhmc.rethrow.launch.exceptions=true",
                "-Dhmc.exit.on.failed.command=true",
                "-Dhmc.crash.report.watcher=true",
                "-Dhmc.check.xvfb=false",
                "-jar",
                launcher.toString(),
                "--command",
                "launch",
                installation.version,
                "-lwjgl",
                "-inmemory",
                "-offline",
            )
            headlessClientLogger.info {
                "Launching isolated HeadlessMC client in $runDirectory " +
                        "for ${endpoint.host}:${endpoint.port}"
            }
            val process = MinecraftTestProcess.start(
                command = command,
                workingDirectory = runDirectory,
                threadName = configuration.threadName,
                logFile = configuration.logFile
                    ?: Path(runDirectory, "client.log"),
                maximumLogCharacters = configuration.maximumLogCharacters,
            )
            return HeadlessMinecraftClientResource(
                installation = installation,
                process = process,
            )
        }

        private fun offlineUuid(name: String): String {
            val bytes = "OfflinePlayer:$name".encodeToByteArray().md5()
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            return Uuid.fromByteArray(bytes).toHexString()
        }

        private fun prepareRunDirectory(workDirectory: Path): Path {
            workDirectory.ensureDirectory()
            val runDirectory = createUniqueDirectory(workDirectory, "run-")
            val gameDirectory = Path(runDirectory, "game")
            gameDirectory.ensureDirectory()
            writeClientOptions(gameDirectory)
            return runDirectory
        }

        private fun writeClientOptions(gameDirectory: Path) {
            Path(gameDirectory, "options.txt").atomicWriteText(
                """
                |autoSuggestions:false
                |enableVsync:false
                |maxFps:30
                |narrator:0
                |pauseOnLostFocus:false
                |renderDistance:2
                |simulationDistance:5
                """.trimMargin() + "\n",
            )
        }
    }
}

/**
 * Verified immutable inputs from which isolated HeadlessMC resources can be
 * started without holding a server socket open during artifact preparation.
 */
class PreparedHeadlessMinecraftClient internal constructor(
    val installation: OfficialClientInstallation,
    internal val launcher: Path,
    internal val javaExecutable: Path,
) {
    suspend fun start(
        workDirectory: Path,
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClientResource =
        HeadlessMinecraftClientResource.start(
            preparedClient = this,
            workDirectory = workDirectory,
            configuration = configuration,
        )
}

suspend fun MinecraftTestEnvironment.prepareHeadlessMinecraftClient(
    downloadWorkers: Int = DEFAULT_CLIENT_DOWNLOAD_WORKERS,
): PreparedHeadlessMinecraftClient {
    headlessClientLogger.info {
        "Preparing official client artifacts for $minecraftVersion"
    }
    val prepared = PreparedHeadlessMinecraftClient(
        installation = officialClient(downloadWorkers),
        launcher = headlessMinecraftLauncher(),
        javaExecutable = javaExecutable,
    )
    headlessClientLogger.info {
        "Official client artifacts and HeadlessMC launcher are ready"
    }
    return prepared
}
