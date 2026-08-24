package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.HeadlessMinecraftClientConfiguration
import com.hiczp.minecraft.test.HeadlessMinecraftClientState
import com.hiczp.minecraft.test.MinecraftTestEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.kotlincrypto.hash.md.MD5
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** A title-ready HMC-Specifics client process in an isolated workspace. */
internal class HostedHeadlessMinecraftClientResource private constructor(
    val workDirectory: Path,
    private val configuration: HeadlessMinecraftClientConfiguration,
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

    suspend fun connect(endpoint: MinecraftTestEndpoint): HeadlessMinecraftClientState {
        require(endpoint.host == LOOPBACK && endpoint.port in 1..0xFFFF) {
            "Headless client tests require a valid loopback endpoint"
        }
        return processMutex.withLock {
            check(managedResource.isOpen) { "Headless client is closing" }
            val startedAt = TimeSource.Monotonic.markNow()
            process.sendLineAndWait(
                line = "connect ${endpoint.host} ${endpoint.port}",
                marker = "Connecting to server ${endpoint.host} at port ${endpoint.port}...",
                timeout = COMMAND_TIMEOUT,
            )
            queryHeadlessClientState(
                process = process,
                timeout = COMMAND_TIMEOUT - startedAt.elapsedNow(),
            )
        }
    }

    suspend fun state(): HeadlessMinecraftClientState = processMutex.withLock {
        check(managedResource.isOpen) { "Headless client is closing" }
        queryHeadlessClientState(process, COMMAND_TIMEOUT)
    }

    suspend fun disconnect() {
        processMutex.withLock {
            check(managedResource.isOpen) { "Headless client is closing" }
            process.sendLineAndWait(
                line = "disconnect",
                marker = DISCONNECT_MARKER,
                timeout = COMMAND_TIMEOUT,
            )
            awaitTitleScreen(
                process = process,
                timeout = COMMAND_TIMEOUT,
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
            check(managedResource.isOpen) { "Headless client is closing" }
            if (expectedNewOutput == null) {
                process.sendLine(command)
            } else {
                process.sendLineAndWait(command, expectedNewOutput, timeout)
            }
        }
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
        stopHeadlessClientProcess(process, configuration.stopTimeout)

    internal companion object {
        suspend fun start(
            layout: MinecraftTestLayout,
            workDirectory: Path,
            configuration: HeadlessMinecraftClientConfiguration,
        ): HostedHeadlessMinecraftClientResource {
            val installation = HeadlessClientPreparation.prepare(layout)
            val process = launchTitleReadyHeadlessClient(
                installation = installation,
                workDirectory = workDirectory,
                configuration = configuration,
                useTemplate = configuration.usesDefaultTemplate(),
            )
            return HostedHeadlessMinecraftClientResource(
                workDirectory = workDirectory,
                configuration = configuration,
                process = process,
            )
        }
    }
}

private suspend fun launchTitleReadyHeadlessClient(
    installation: HeadlessClientInstallation,
    workDirectory: Path,
    configuration: HeadlessMinecraftClientConfiguration,
    useTemplate: Boolean,
): MinecraftTestProcess {
    val privateInstallation = prepareHeadlessClientRuntime(
        installation,
        workDirectory,
    )
    val gameDirectory = workDirectory.resolve("game")
    val headlessMcHome = workDirectory.resolve("headlessmc-home")
    prepareHeadlessClientWorkspace(
        installation = privateInstallation,
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
    val process = MinecraftTestProcess.start(
        command = headlessClientCommand(
            installation = privateInstallation,
            gameDirectory = gameDirectory,
            headlessMcHome = headlessMcHome,
            playerName = configuration.playerName,
        ),
        workingDirectory = workDirectory,
        threadName = "headless-minecraft-client",
    )
    try {
        val startedAt = TimeSource.Monotonic.markNow()
        process.waitForLog(
            HMC_SPECIFICS_READY_MARKER,
            configuration.startupTimeout,
        )
        awaitTitleScreen(
            process = process,
            timeout = configuration.startupTimeout - startedAt.elapsedNow(),
        )
        process.requireAlive("Headless client after title-screen readiness")
        return process
    } catch (failure: Throwable) {
        forceStopAfterFailure(process, failure)
        if (failure is CancellationException) throw failure
        throw AssertionError(
            """
            |Headless client failed to reach its title screen.
            |--- headless client log ---
            |${process.logText()}
            """.trimMargin(),
            failure,
        )
    }
}

private suspend fun awaitTitleScreen(
    process: MinecraftTestProcess,
    timeout: Duration,
) {
    require(timeout.isPositive()) {
        "Headless client exhausted its startup timeout before HMC-Specifics became ready"
    }
    val startedAt = TimeSource.Monotonic.markNow()
    while (startedAt.elapsedNow() < timeout) {
        val remaining = timeout - startedAt.elapsedNow()
        val state = queryHeadlessClientState(process, remaining)
        if (state.screenClassName == TITLE_SCREEN_MARKER) {
            return
        }
    }
    error(
        "Headless client did not reach its title screen within $timeout:\n${process.logText()}",
    )
}

private suspend fun queryHeadlessClientState(
    process: MinecraftTestProcess,
    timeout: Duration,
): HeadlessMinecraftClientState {
    require(timeout.isPositive() && timeout.isFinite()) {
        "Headless client state timeout must be positive and finite"
    }
    val output = process.sendLineAndWaitForAny(
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
    installation: HeadlessClientInstallation,
    workDirectory: Path,
): HeadlessClientInstallation {
    val runtime = workDirectory.resolve("runtime")
    val minecraft = runtime.resolve("minecraft")
    val launcher = runtime.resolve("headlessmc").resolve("headlessmc-launcher.jar")
    installation.minecraftDirectory.linkDirectoryTo(minecraft)
    installation.launcher.linkFileTo(launcher)
    return installation.copy(
        minecraftDirectory = minecraft,
        launcher = launcher,
    )
}

internal fun prepareHeadlessClientWorkspace(
    installation: HeadlessClientInstallation,
    gameDirectory: Path,
    useTemplate: Boolean,
) {
    gameDirectory.createDirectories()
    if (useTemplate) {
        installation.templateDirectory.copyTreeTo(
            destination = gameDirectory,
            excludedRelativePaths = CLIENT_TEMPLATE_IMMUTABLE_DIRECTORIES,
        )
    }
    installation.modsDirectory.linkTreeTo(gameDirectory.resolve("mods"))
    installation.processedModsDirectory?.linkTreeTo(
        gameDirectory.resolve(".fabric").resolve("processedMods"),
    )
}

private fun headlessClientCommand(
    installation: HeadlessClientInstallation,
    gameDirectory: Path,
    headlessMcHome: Path,
    playerName: String,
): List<String> = fixtureJavaCommand(
    "-Xms256M",
    "-Xmx1G",
    "-Duser.home=$headlessMcHome",
    "-Djava.awt.headless=true",
    "-Djoml.nounsafe=true",
    "--sun-misc-unsafe-memory-access=allow",
    "-Dhmc.mcdir=${installation.minecraftDirectory}",
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
    "-Dhmc.offline.uuid=${offlineUuid(playerName)}",
    "-Dhmc.offline.token=0",
    "-Dhmc.jline.enabled=false",
    "-Dhmc.filehandler.enabled=false",
    "-Dhmc.rethrow.launch.exceptions=true",
    "-Dhmc.exit.on.failed.command=true",
    "-Dhmc.crash.report.watcher=true",
    "-Dhmc.check.xvfb=false",
    "-jar",
    installation.launcher.toString(),
    "--command",
    "launch",
    installation.fabricProfileId,
    "-lwjgl",
    "-inmemory",
    "-offline",
)

private suspend fun stopHeadlessClientProcess(
    process: MinecraftTestProcess,
    timeout: Duration,
): Int {
    if (!process.isAlive) {
        return process.exitCode.also { exitCode ->
            check(exitCode == 0) {
                "Headless client exited abnormally with $exitCode:\n${process.logText()}"
            }
        }
    }
    try {
        process.sendLineAndWait(
            line = "quit",
            marker = QUIT_MARKER,
            timeout = timeout,
        )
        val exitCode = checkNotNull(process.awaitExitWithin(timeout)) {
            "Headless client did not exit after HMC-Specifics accepted quit"
        }
        check(exitCode == 0) {
            "Headless client exited abnormally with $exitCode:\n${process.logText()}"
        }
        return exitCode
    } catch (failure: Throwable) {
        forceStopAfterFailure(process, failure)
        if (failure is CancellationException) throw failure
        throw AssertionError(
            """
            |Headless client did not stop cleanly.
            |--- headless client log ---
            |${process.logText()}
            """.trimMargin(),
            failure,
        )
    }
}

private suspend fun forceStopAfterFailure(
    process: MinecraftTestProcess,
    failure: Throwable,
) = withContext(NonCancellable) {
    if (!process.isAlive) return@withContext
    process.forceStop()
    try {
        checkNotNull(process.awaitExitWithin(FORCED_STOP_TIMEOUT)) {
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
        val installation = HeadlessClientInstallation(
            minecraftVersion = minecraftVersion,
            fabricProfileId = "fabric-loader-$fabricLoaderVersion-$minecraftVersion",
            minecraftDirectory = runtimeDirectory.resolve("minecraft"),
            launcher = runtimeDirectory.resolve("headlessmc").resolve("headlessmc-launcher.jar"),
            modsDirectory = runtimeDirectory.resolve("mods"),
            processedModsDirectory = null,
            templateDirectory = templateDirectory,
        )
        val configuration = HeadlessMinecraftClientConfiguration(
            playerName = TEMPLATE_PLAYER_NAME,
        )
        val process = launchTitleReadyHeadlessClient(
            installation = installation,
            workDirectory = candidate,
            configuration = configuration,
            useTemplate = false,
        )
        stopHeadlessClientProcess(process, configuration.stopTimeout)
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
        installation.modsDirectory.linkTreeTo(
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
        val manifest = JsonObject(
            linkedMapOf(
                "schema_version" to JsonPrimitive(1),
                "minecraft_version" to JsonPrimitive(minecraftVersion),
                "headlessmc_version" to JsonPrimitive(headlessMcVersion),
                "fabric_loader_version" to JsonPrimitive(fabricLoaderVersion),
                "fabric_profile_id" to
                        JsonPrimitive(installation.fabricProfileId),
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
                    clientTemplateIgnoredEntries().map(::JsonPrimitive),
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
            "${testJson.encodeToString(JsonObject.serializer(), manifest)}\n",
        )
        published = true
    } finally {
        candidate.deleteTree()
        if (!published) {
            templateDirectory.deleteTree()
            manifestFile.deleteTree()
        }
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

private fun offlineUuid(name: String): String {
    val bytes = MD5().digest("OfflinePlayer:$name".encodeToByteArray())
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

private fun clientTemplateIgnoredEntries(): List<String> = buildList {
    CLIENT_TEMPLATE_CLEARED_DIRECTORIES.forEach { directory ->
        add("$directory/**")
    }
    addAll(CLIENT_TEMPLATE_IGNORED_FILES)
}

private val COMMAND_TIMEOUT = 30.seconds
private val FORCED_STOP_TIMEOUT = 5.seconds
private val SCREEN_CLASS_PATTERN = Regex("Screen:\\s+([A-Za-z0-9_.$]+)")
