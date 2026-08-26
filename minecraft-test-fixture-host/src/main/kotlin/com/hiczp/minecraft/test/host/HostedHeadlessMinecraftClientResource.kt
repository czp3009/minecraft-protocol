package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.HeadlessMinecraftClientConfiguration
import com.hiczp.minecraft.test.HeadlessMinecraftClientState
import com.hiczp.minecraft.test.MinecraftTestEndpoint
import com.hiczp.minecraft.test.MinecraftTestResourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A title-ready HMC-Specifics client process in an isolated workspace. */
internal class HostedHeadlessMinecraftClientResource private constructor(
    val workDirectory: Path,
    private val headlessMinecraftClientConfiguration: HeadlessMinecraftClientConfiguration,
    private val minecraftTestProcess: MinecraftTestProcess,
) : AutoCloseable {
    private val processMutex = Mutex()
    private lateinit var managedMinecraftTestResource: ManagedMinecraftTestResource

    fun logText(): String = minecraftTestProcess.logText()

    fun status(): MinecraftTestResourceStatus {
        val alive = minecraftTestProcess.isAlive
        return MinecraftTestResourceStatus(
            alive = alive,
            exitCode = if (alive) null else minecraftTestProcess.exitCode,
        )
    }

    suspend fun waitForLog(marker: String, timeout: Duration) {
        minecraftTestProcess.waitForLog(marker, timeout)
    }

    suspend fun connect(minecraftTestEndpoint: MinecraftTestEndpoint): HeadlessMinecraftClientState {
        require(minecraftTestEndpoint.host == LOOPBACK && minecraftTestEndpoint.port in 1..0xFFFF) {
            "Headless client tests require a valid loopback endpoint"
        }
        return processMutex.withLock {
            check(managedMinecraftTestResource.isOpen) { "Headless client is closing" }
            val startedAt = TimeSource.Monotonic.markNow()
            minecraftTestProcess.sendLineAndWait(
                line = "connect ${minecraftTestEndpoint.host} ${minecraftTestEndpoint.port}",
                marker = "Connecting to server ${minecraftTestEndpoint.host} at port ${minecraftTestEndpoint.port}...",
                timeout = COMMAND_TIMEOUT,
            )
            queryHeadlessClientState(
                minecraftTestProcess = minecraftTestProcess,
                timeout = COMMAND_TIMEOUT - startedAt.elapsedNow(),
            )
        }
    }

    suspend fun state(): HeadlessMinecraftClientState = processMutex.withLock {
        check(managedMinecraftTestResource.isOpen) { "Headless client is closing" }
        queryHeadlessClientState(minecraftTestProcess, COMMAND_TIMEOUT)
    }

    suspend fun disconnect() {
        processMutex.withLock {
            check(managedMinecraftTestResource.isOpen) { "Headless client is closing" }
            val startedAt = TimeSource.Monotonic.markNow()
            minecraftTestProcess.sendLineAndWait(
                line = "disconnect",
                marker = DISCONNECT_MARKER,
                timeout = COMMAND_TIMEOUT,
            )
            awaitTitleScreen(
                minecraftTestProcess = minecraftTestProcess,
                timeout = COMMAND_TIMEOUT - startedAt.elapsedNow(),
            )
        }
    }

    suspend fun sendCommand(
        command: String,
        expectedNewOutput: String?,
        timeout: Duration,
    ) {
        validateHeadlessClientAction(command)
        processMutex.withLock {
            check(managedMinecraftTestResource.isOpen) { "Headless client is closing" }
            if (expectedNewOutput == null) {
                minecraftTestProcess.sendLine(command)
            } else {
                minecraftTestProcess.sendLineAndWait(command, expectedNewOutput, timeout)
            }
        }
    }

    suspend fun closeProcess(): Int = processMutex.withLock {
        check(managedMinecraftTestResource.isOpen) { "Headless client is closing" }
        closeProcessLocked()
    }

    suspend fun awaitExit(): Int = minecraftTestProcess.awaitExit()

    override fun close() {
        managedMinecraftTestResource.close()
    }

    fun invokeOnCleanupCompletion(handler: (Throwable?) -> Unit) {
        managedMinecraftTestResource.invokeOnCleanupCompletion(handler)
    }

    suspend fun beginWorkingDirectoryDeletion() {
        processMutex.withLock {
            check(!minecraftTestProcess.isAlive) {
                "Headless client must be stopped before deleting its working directory"
            }
            managedMinecraftTestResource.close()
        }
    }

    suspend fun awaitCleanup() = managedMinecraftTestResource.awaitCleanup()

    internal fun attach(managedMinecraftTestResource: ManagedMinecraftTestResource) {
        check(!::managedMinecraftTestResource.isInitialized)
        this@HostedHeadlessMinecraftClientResource.managedMinecraftTestResource = managedMinecraftTestResource
    }

    internal suspend fun cleanup() {
        processMutex.withLock {
            closeProcessLocked()
        }
    }

    private suspend fun closeProcessLocked(): Int =
        stopHeadlessClientProcess(minecraftTestProcess, headlessMinecraftClientConfiguration.stopTimeout)

    internal companion object {
        suspend fun start(
            minecraftTestLayout: MinecraftTestLayout,
            workDirectory: Path,
            headlessMinecraftClientConfiguration: HeadlessMinecraftClientConfiguration,
        ): HostedHeadlessMinecraftClientResource {
            val headlessClientInstallation = HeadlessClientPreparation.prepare(minecraftTestLayout)
            val minecraftTestProcess = launchTitleReadyHeadlessClient(
                headlessClientInstallation = headlessClientInstallation,
                workDirectory = workDirectory,
                headlessMinecraftClientConfiguration = headlessMinecraftClientConfiguration,
                useTemplate = headlessMinecraftClientConfiguration.usesDefaultTemplate(),
            )
            return HostedHeadlessMinecraftClientResource(
                workDirectory = workDirectory,
                headlessMinecraftClientConfiguration = headlessMinecraftClientConfiguration,
                minecraftTestProcess = minecraftTestProcess,
            )
        }
    }
}

private suspend fun launchTitleReadyHeadlessClient(
    headlessClientInstallation: HeadlessClientInstallation,
    workDirectory: Path,
    headlessMinecraftClientConfiguration: HeadlessMinecraftClientConfiguration,
    useTemplate: Boolean,
): MinecraftTestProcess {
    val privateInstallation = prepareHeadlessClientRuntime(
        headlessClientInstallation,
        workDirectory,
    )
    val gameDirectory = workDirectory.resolve("game")
    val headlessMcHome = workDirectory.resolve("headlessmc-home")
    prepareHeadlessClientWorkspace(
        headlessClientInstallation = privateInstallation,
        gameDirectory = gameDirectory,
        useTemplate = useTemplate,
    )
    headlessMcHome.createDirectories()
    if (useTemplate) {
        check(gameDirectory.resolve("options.txt").isRegularFile()) {
            "Headless client template is missing options.txt"
        }
    } else {
        writeClientOptions(gameDirectory)
    }
    val minecraftTestProcess = MinecraftTestProcess.start(
        command = headlessClientCommand(
            headlessClientInstallation = privateInstallation,
            gameDirectory = gameDirectory,
            headlessMcHome = headlessMcHome,
            playerName = headlessMinecraftClientConfiguration.playerName,
        ),
        workingDirectory = workDirectory,
        threadName = "headless-minecraft-client",
    )
    try {
        val startedAt = TimeSource.Monotonic.markNow()
        minecraftTestProcess.waitForLog(
            HMC_SPECIFICS_READY_MARKER,
            headlessMinecraftClientConfiguration.startupTimeout,
        )
        awaitTitleScreen(
            minecraftTestProcess = minecraftTestProcess,
            timeout = headlessMinecraftClientConfiguration.startupTimeout - startedAt.elapsedNow(),
        )
        minecraftTestProcess.requireAlive("Headless client after title-screen readiness")
        return minecraftTestProcess
    } catch (failure: Throwable) {
        forceStopAfterFailure(minecraftTestProcess, failure)
        if (failure is CancellationException) throw failure
        throw AssertionError(
            """
            |Headless client failed to reach its title screen.
            |--- headless client log ---
            |${minecraftTestProcess.logText()}
            """.trimMargin(),
            failure,
        )
    }
}

private suspend fun awaitTitleScreen(
    minecraftTestProcess: MinecraftTestProcess,
    timeout: Duration,
) {
    require(timeout.isPositive()) {
        "Headless client exhausted its startup timeout before HMC-Specifics became ready"
    }
    val startedAt = TimeSource.Monotonic.markNow()
    while (startedAt.elapsedNow() < timeout) {
        val remaining = timeout - startedAt.elapsedNow()
        val headlessMinecraftClientState = queryHeadlessClientState(minecraftTestProcess, remaining)
        if (headlessMinecraftClientState.screenClassName == TITLE_SCREEN_MARKER) {
            return
        }
    }
    error(
        "Headless client did not reach its title screen within $timeout:\n${minecraftTestProcess.logText()}",
    )
}

private suspend fun queryHeadlessClientState(
    minecraftTestProcess: MinecraftTestProcess,
    timeout: Duration,
): HeadlessMinecraftClientState {
    require(timeout.isPositive() && timeout.isFinite()) {
        "Headless client state timeout must be positive and finite"
    }
    val output = minecraftTestProcess.sendLineAndWaitForAny(
        line = "gui",
        markers = listOf(SCREEN_MARKER, NO_GUI_MARKER),
        timeout = timeout,
    ).line
    return parseHeadlessClientState(output)
}

internal fun parseHeadlessClientState(output: String): HeadlessMinecraftClientState {
    if (NO_GUI_MARKER in output) {
        return HeadlessMinecraftClientState(screenClassName = null)
    }
    val screenClassName = SCREEN_CLASS_PATTERN.find(output)
        ?.groupValues
        ?.get(1)
    checkNotNull(screenClassName) {
        "HMC-Specifics returned an invalid GUI state line: $output"
    }
    return HeadlessMinecraftClientState(screenClassName)
}

internal fun prepareHeadlessClientRuntime(
    headlessClientInstallation: HeadlessClientInstallation,
    workDirectory: Path,
): HeadlessClientInstallation {
    val runtime = workDirectory.resolve("runtime")
    val minecraft = runtime.resolve("minecraft")
    val launcher = runtime.resolve("headlessmc").resolve("headlessmc-launcher.jar")
    headlessClientInstallation.minecraftDirectory.linkDirectoryTo(minecraft)
    headlessClientInstallation.launcher.linkFileTo(launcher)
    return headlessClientInstallation.copy(
        minecraftDirectory = minecraft,
        launcher = launcher,
    )
}

internal fun prepareHeadlessClientWorkspace(
    headlessClientInstallation: HeadlessClientInstallation,
    gameDirectory: Path,
    useTemplate: Boolean,
) {
    gameDirectory.createDirectories()
    if (useTemplate) {
        headlessClientInstallation.templateDirectory.copyTreeTo(
            destination = gameDirectory,
            excludedRelativePaths = CLIENT_TEMPLATE_IMMUTABLE_DIRECTORIES,
        )
    }
    headlessClientInstallation.modsDirectory.linkTreeTo(gameDirectory.resolve("mods"))
    headlessClientInstallation.processedModsDirectory?.linkTreeTo(
        gameDirectory.resolve(".fabric").resolve("processedMods"),
    )
}

private fun headlessClientCommand(
    headlessClientInstallation: HeadlessClientInstallation,
    gameDirectory: Path,
    headlessMcHome: Path,
    playerName: String,
): List<String> {
    val offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".encodeToByteArray())
        .toString()
        .replace("-", "")
    return fixtureJavaCommand(
        "-Xms256M",
        "-Xmx1G",
        "-Duser.home=$headlessMcHome",
        "-Djava.awt.headless=true",
        "-Djoml.nounsafe=true",
        "--sun-misc-unsafe-memory-access=allow",
        "-Dhmc.mcdir=${headlessClientInstallation.minecraftDirectory}",
        "-Dhmc.gamedir=$gameDirectory",
        "-Dhmc.java.versions=java",
        "-Dhmc.no.auto.config=true",
        "-Dhmc.java.use.current=false",
        "-Dhmc.java.require.exact=true",
        "-Dhmc.auto.download=false",
        "-Dhmc.auto.download.java=false",
        "-Dhmc.auto.download.versions=false",
        "-Dhmc.auto.download.specifics=false",
        "-Dhmc.assets.dummy=true",
        "-Dhmc.assets.check.hash=false",
        "-Dhmc.assets.check.size=false",
        "-Dhmc.assets.check.file.hash=false",
        "-Dhmc.always.download.assets.index=false",
        "-Dhmc.libraries.check.hash=false",
        "-Dhmc.libraries.check.size=false",
        "-Dhmc.libraries.check.file.hash=false",
        "-Dhmc.install.mc.logging=false",
        "-Dhmc.account.refresh.on.game.launch=false",
        "-Dhmc.account.refresh.on.launch=false",
        "-Dhmc.store.accounts=false",
        "-Dhmc.offline=true",
        "-Dhmc.offline.username=$playerName",
        "-Dhmc.offline.uuid=$offlineUuid",
        "-Dhmc.offline.token=0",
        "-Dhmc.jline.enabled=false",
        "-Dhmc.filehandler.enabled=false",
        "-Dhmc.rethrow.launch.exceptions=true",
        "-Dhmc.exit.on.failed.command=true",
        "-Dhmc.crash.report.watcher=true",
        "-Dhmc.check.xvfb=false",
        "-jar",
        headlessClientInstallation.launcher.toString(),
        "--command",
        "launch",
        headlessClientInstallation.fabricProfileId,
        "-lwjgl",
        "-inmemory",
        "-offline",
    )
}

private suspend fun stopHeadlessClientProcess(
    minecraftTestProcess: MinecraftTestProcess,
    timeout: Duration,
): Int {
    if (!minecraftTestProcess.isAlive) {
        return minecraftTestProcess.exitCode.also { exitCode ->
            check(exitCode == 0) {
                "Headless client exited abnormally with $exitCode:\n${minecraftTestProcess.logText()}"
            }
        }
    }
    try {
        val startedAt = TimeSource.Monotonic.markNow()
        minecraftTestProcess.sendLineAndWait(
            line = "quit",
            marker = QUIT_MARKER,
            timeout = timeout,
        )
        val remaining = timeout - startedAt.elapsedNow()
        val exitCode = if (!minecraftTestProcess.isAlive) {
            minecraftTestProcess.exitCode
        } else {
            check(remaining.isPositive()) {
                "Headless client exhausted its stop timeout after HMC-Specifics accepted quit"
            }
            checkNotNull(minecraftTestProcess.awaitExitWithin(remaining)) {
                "Headless client did not exit after HMC-Specifics accepted quit"
            }
        }
        check(exitCode == 0) {
            "Headless client exited abnormally with $exitCode:\n${minecraftTestProcess.logText()}"
        }
        return exitCode
    } catch (failure: Throwable) {
        forceStopAfterFailure(minecraftTestProcess, failure)
        if (failure is CancellationException) throw failure
        throw AssertionError(
            """
            |Headless client did not stop cleanly.
            |--- headless client log ---
            |${minecraftTestProcess.logText()}
            """.trimMargin(),
            failure,
        )
    }
}

private suspend fun forceStopAfterFailure(
    minecraftTestProcess: MinecraftTestProcess,
    failure: Throwable,
) = withContext(NonCancellable) {
    if (!minecraftTestProcess.isAlive) return@withContext
    minecraftTestProcess.forceStop()
    try {
        checkNotNull(minecraftTestProcess.awaitExitWithin(FORCED_STOP_TIMEOUT)) {
            "Headless client remained alive after forced termination"
        }
    } catch (cleanupFailure: Throwable) {
        failure.addSuppressed(cleanupFailure)
    }
}

internal suspend fun generateHeadlessClientTemplate(
    minecraftVersion: String,
    headlessMcVersion: String,
    fabricLoaderVersion: String,
    hmcSpecificsReleaseTag: String,
    hmcSpecificsAssetName: String,
    hmcSpecificsAssetUrl: String,
    runtimeDirectory: Path,
    templateDirectory: Path,
    manifestFile: Path,
    workRoot: Path,
) {
    templateDirectory.deleteTree()
    manifestFile.deleteTree()
    val candidate = createUniqueDirectory(workRoot)
    var published = false
    try {
        val headlessClientInstallation = HeadlessClientInstallation(
            minecraftVersion = minecraftVersion,
            fabricProfileId = "fabric-loader-$fabricLoaderVersion-$minecraftVersion",
            minecraftDirectory = runtimeDirectory.resolve("minecraft"),
            launcher = runtimeDirectory.resolve("headlessmc").resolve("headlessmc-launcher.jar"),
            modsDirectory = runtimeDirectory.resolve("mods"),
            processedModsDirectory = null,
            templateDirectory = templateDirectory,
        )
        val headlessMinecraftClientConfiguration = HeadlessMinecraftClientConfiguration(
            playerName = TEMPLATE_PLAYER_NAME,
        )
        val minecraftTestProcess = launchTitleReadyHeadlessClient(
            headlessClientInstallation = headlessClientInstallation,
            workDirectory = candidate,
            headlessMinecraftClientConfiguration = headlessMinecraftClientConfiguration,
            useTemplate = false,
        )
        stopHeadlessClientProcess(minecraftTestProcess, headlessMinecraftClientConfiguration.stopTimeout)
        val candidateGame = candidate.resolve("game")
        check(candidateGame.resolve("options.txt").isRegularFile()) {
            "Headless client template did not retain deterministic options"
        }
        val candidateMods = candidateGame.resolve("mods")
        val modEntries = candidateMods.listDirectoryEntries()
            .map { path -> path.fileName.toString() }
            .sorted()
        check(modEntries == listOf(hmcSpecificsAssetName)) {
            "Headless client template loaded unexpected mods: $modEntries"
        }
        val processedMods = candidateGame.resolve(".fabric").resolve("processedMods")
        check(
            processedMods.isDirectory() &&
                    processedMods.listDirectoryEntries().any { path ->
                        path.isRegularFile()
                    },
        ) {
            "Headless client template did not produce the Fabric processed-mod cache"
        }
        candidateGame.copyTreeTo(
            destination = templateDirectory,
            excludedRelativePaths = CLIENT_TEMPLATE_IMMUTABLE_DIRECTORIES,
        )
        headlessClientInstallation.modsDirectory.linkTreeTo(
            templateDirectory.resolve("mods"),
        )
        processedMods.linkTreeTo(
            templateDirectory.resolve(".fabric").resolve("processedMods"),
        )
        CLIENT_TEMPLATE_CLEARED_DIRECTORIES.forEach { relativePath ->
            templateDirectory.safeResolve(relativePath).deleteFilesRecursively()
        }
        CLIENT_TEMPLATE_IGNORED_FILES.forEach { relativePath ->
            templateDirectory.safeResolve(relativePath).deleteTree()
        }
        CLIENT_TEMPLATE_CLEARED_DIRECTORIES.forEach { relativePath ->
            check(templateDirectory.safeResolve(relativePath).isDirectory()) {
                "Headless client template lost reusable directory $relativePath"
            }
        }
        CLIENT_TEMPLATE_IGNORED_FILES.forEach { relativePath ->
            check(!templateDirectory.safeResolve(relativePath).exists()) {
                "Headless client template retained mutable file $relativePath"
            }
        }
        val templateEntries = templateDirectory.listDirectoryEntries()
            .map { path -> path.fileName.toString() }
            .sorted()
        val excludedMutableEntries =
            CLIENT_TEMPLATE_CLEARED_DIRECTORIES.map { directory -> "$directory/**" } +
                    CLIENT_TEMPLATE_IGNORED_FILES
        val manifest = JsonObject(
            linkedMapOf(
                "schema_version" to JsonPrimitive(1),
                "minecraft_version" to JsonPrimitive(minecraftVersion),
                "headlessmc_version" to JsonPrimitive(headlessMcVersion),
                "fabric_loader_version" to JsonPrimitive(fabricLoaderVersion),
                "fabric_profile_id" to
                        JsonPrimitive(headlessClientInstallation.fabricProfileId),
                "hmc_specifics_release_tag" to
                        JsonPrimitive(hmcSpecificsReleaseTag),
                "hmc_specifics_asset_name" to
                        JsonPrimitive(hmcSpecificsAssetName),
                "hmc_specifics_asset_url" to
                        JsonPrimitive(hmcSpecificsAssetUrl),
                "relative_headlessmc_launcher" to JsonPrimitive(
                    "runtime/headlessmc/headlessmc-launcher.jar",
                ),
                "relative_minecraft_directory" to
                        JsonPrimitive("runtime/minecraft"),
                "relative_mods_directory" to
                        JsonPrimitive("runtime/mods"),
                "relative_processed_mods_directory" to JsonPrimitive(
                    "template/.fabric/processedMods",
                ),
                "relative_template_directory" to JsonPrimitive("template"),
                "template_entries" to JsonArray(
                    templateEntries.map(::JsonPrimitive),
                ),
                "excluded_mutable_entries" to JsonArray(
                    excludedMutableEntries.map(::JsonPrimitive),
                ),
                "immutable_template_directories" to JsonArray(
                    CLIENT_TEMPLATE_IMMUTABLE_DIRECTORIES
                        .sorted()
                        .map(::JsonPrimitive),
                ),
                "template_policy_revision" to JsonPrimitive(3),
                "ready_marker" to JsonPrimitive(HMC_SPECIFICS_READY_MARKER),
                "title_screen_marker" to JsonPrimitive(TITLE_SCREEN_MARKER),
                "quit_marker" to JsonPrimitive(QUIT_MARKER),
                "clean_stop" to JsonPrimitive(true),
            ),
        )
        manifestFile.writeText(
            "${testJson.encodeToString(manifest)}\n",
        )
        published = true
    } catch (failure: Throwable) {
        deleteTreesPreserving(
            failure,
            candidate,
            templateDirectory,
            manifestFile,
        )
        throw failure
    } finally {
        if (published) candidate.deleteTree()
    }
}

private fun validateHeadlessClientAction(command: String) {
    require(command.isNotBlank()) { "Headless client command is blank" }
    require('\n' !in command && '\r' !in command) {
        "Headless client command must contain exactly one line"
    }
    val verb = command.trimStart().substringBefore(' ').lowercase()
    require(verb in ALLOWED_ACTION_VERBS) {
        "Headless client action is not allowed: $verb"
    }
}

internal fun HeadlessMinecraftClientConfiguration.usesDefaultTemplate(): Boolean =
    this == HeadlessMinecraftClientConfiguration(playerName = playerName)

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
    gameDirectory.resolve("options.txt").writeText(
        "$options\n",
    )
}

private val ALLOWED_ACTION_VERBS = setOf(
    "gui",
    "render",
    "click",
    "text",
    "menu",
    "close",
    "key",
    "msg",
    ".",
    "/",
)
private const val LOOPBACK = "127.0.0.1"
private const val TEMPLATE_PLAYER_NAME = "FixtureTemplate"
private const val HMC_SPECIFICS_READY_MARKER = "HMC-Specifics initialized!"
private const val TITLE_SCREEN_MARKER = "net.minecraft.client.gui.screens.TitleScreen"
private const val SCREEN_MARKER = "Screen:"
private const val NO_GUI_MARKER = "Minecraft is currently not displaying a Gui."
private const val DISCONNECT_MARKER = "Disconnecting..."
private const val QUIT_MARKER = "Quitting Minecraft..."
private val CLIENT_TEMPLATE_CLEARED_DIRECTORIES = listOf(
    "crash-reports",
    "logs",
)
private val CLIENT_TEMPLATE_IGNORED_FILES = listOf(
    "downloads/log.json",
)
private val CLIENT_TEMPLATE_IMMUTABLE_DIRECTORIES = setOf(
    ".fabric/processedMods",
    "mods",
)

private val COMMAND_TIMEOUT = 30.seconds
private val FORCED_STOP_TIMEOUT = 5.seconds
private val SCREEN_CLASS_PATTERN = Regex("Screen:\\s+([A-Za-z0-9_.$]+)")
