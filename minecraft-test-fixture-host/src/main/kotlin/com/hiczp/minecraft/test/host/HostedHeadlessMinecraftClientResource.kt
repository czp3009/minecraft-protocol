package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.HeadlessMinecraftClientConfiguration
import com.hiczp.minecraft.test.MinecraftTestEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** A ready official-client process with an isolated game directory. */
internal class HostedHeadlessMinecraftClientResource private constructor(
    val endpoint: MinecraftTestEndpoint,
    val workDirectory: Path,
    private val process: MinecraftTestProcess,
) : AutoCloseable {
    private val processMutex = Mutex()
    private lateinit var managedResource: ManagedMinecraftTestResource

    val isAlive: Boolean
        get() = process.isAlive

    val exitCode: Int
        get() = process.exitCode

    fun logText(): String = process.logText()

    suspend fun waitForLog(marker: String, timeout: Duration) {
        process.waitForLog(marker, timeout)
    }

    suspend fun closeProcess(): Int = processMutex.withLock {
        check(managedResource.isOpen) { "Headless client is closing" }
        closeProcessLocked()
    }

    suspend fun awaitExit(): Int = process.awaitExit()

    override fun close() {
        managedResource.close()
    }

    fun invokeOnCleanupCompletion(handler: (Throwable?) -> Unit) {
        managedResource.invokeOnCleanupCompletion(handler)
    }

    suspend fun deleteWorkingDirectory() {
        processMutex.withLock {
            check(!process.isAlive) {
                "Headless client must be stopped before deleting its working directory"
            }
            managedResource.close()
        }
        managedResource.awaitCleanup()
    }

    internal fun attach(resource: ManagedMinecraftTestResource) {
        check(!::managedResource.isInitialized)
        managedResource = resource
    }

    internal suspend fun cleanup() {
        processMutex.withLock {
            closeProcessLocked()
        }
    }

    private suspend fun closeProcessLocked(): Int =
        if (process.isAlive) process.terminate() else process.exitCode

    internal companion object {
        suspend fun start(
            layout: MinecraftTestLayout,
            workDirectory: Path,
            configuration: HeadlessMinecraftClientConfiguration,
        ): HostedHeadlessMinecraftClientResource {
            val installation = OfficialClientPreparation.prepare(layout)
            val launcher = OfficialArtifacts.headlessLauncher(layout)
            val gameDirectory = Path(workDirectory, "game")
            gameDirectory.ensureDirectory()
            writeClientOptions(gameDirectory)

            val endpoint = configuration.endpoint
            val command = listOf(
                "java",
                "-Xms256M",
                "-Xmx1G",
                "-Djava.awt.headless=true",
                "-Djoml.nounsafe=true",
                "--sun-misc-unsafe-memory-access=allow",
                "--enable-native-access=ALL-UNNAMED",
                "-Dhmc.mcdir=${installation.directory}",
                "-Dhmc.gamedir=$gameDirectory",
                "-Dhmc.java.versions=java",
                "-Dhmc.no.auto.config=true",
                "-Dhmc.java.use.current=false",
                "-Dhmc.java.require.exact=true",
                "-Dhmc.auto.download.java=false",
                "-Dhmc.auto.download.versions=false",
                "-Dhmc.auto.download.specifics=false",
                "-Dhmc.assets.dummy=true",
                "-Dhmc.assets.check.file.hash=false",
                "-Dhmc.always.download.assets.index=false",
                "-Dhmc.libraries.check.file.hash=false",
                "-Dhmc.install.mc.logging=false",
                "-Dhmc.account.refresh.on.game.launch=false",
                "-Dhmc.account.refresh.on.launch=false",
                "-Dhmc.store.accounts=false",
                "-Dhmc.offline=true",
                "-Dhmc.offline.username=${configuration.playerName}",
                "-Dhmc.offline.uuid=${offlineUuid(configuration.playerName)}",
                "-Dhmc.offline.token=0",
                "-Dhmc.gameargs=--quickPlayMultiplayer ${endpoint.host}:${endpoint.port}",
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
            val process = MinecraftTestProcess.start(
                command = command,
                workingDirectory = workDirectory,
                threadName = "official-headless-client",
            )
            try {
                process.waitForLog(LAUNCH_READY_MARKER, LAUNCH_TIMEOUT)
                return HostedHeadlessMinecraftClientResource(
                    endpoint = endpoint,
                    workDirectory = workDirectory,
                    process = process,
                )
            } catch (failure: Throwable) {
                runCatching { process.terminate() }
                    .onFailure(failure::addSuppressed)
                throw AssertionError(
                    """
                    |Official client failed to enter its in-memory launcher.
                    |--- official client log ---
                    |${process.logText()}
                    """.trimMargin(),
                    failure,
                )
            }
        }

        private fun offlineUuid(name: String): String {
            val bytes = "OfflinePlayer:$name".encodeToByteArray().md5()
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            return Uuid.fromByteArray(bytes).toHexString()
        }

        private fun writeClientOptions(gameDirectory: Path) {
            val options = """
                |autoSuggestions:false
                |enableVsync:false
                |maxFps:30
                |narrator:0
                |pauseOnLostFocus:false
                |renderDistance:2
                |simulationDistance:5
            """.trimMargin()
            Path(gameDirectory, "options.txt").writeText(
                "$options\n",
            )
        }

        private val LAUNCH_TIMEOUT = 2.minutes
        private const val LAUNCH_READY_MARKER =
            "Launching with simple in-memory launcher"
    }
}

private const val LOOPBACK = "127.0.0.1"
