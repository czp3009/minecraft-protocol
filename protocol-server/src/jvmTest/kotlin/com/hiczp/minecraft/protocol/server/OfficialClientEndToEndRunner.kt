package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.offlineUuid
import com.hiczp.minecraft.protocol.auth.toUndashedString
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.Uuid
import com.hiczp.minecraft.protocol.model.type.Vector3d
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.absolutePathString

/**
 * Black-box interoperability runner for the matching official client.
 *
 * The five-argument form starts the unmodified desktop client directly. A
 * sixth HeadlessMC JAR argument replaces LWJGL with stubs so the same client
 * can run on a CI worker without a display server.
 */
internal object OfficialClientEndToEndRunner {
    private const val PLAYER_NAME = "KmpE2EClient"
    private const val KEEP_ALIVE_ID = 0x1020_3040_5060_7080L

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 5 || arguments.size == 6) {
            "Expected <client-java> <minecraft-directory> <version> " +
                    "<work-directory> <report.json> [headlessmc.jar]"
        }
        val javaExecutable = Path.of(arguments[0]).toAbsolutePath().normalize()
        val minecraftDirectory =
            Path.of(arguments[1]).toAbsolutePath().normalize()
        val version = arguments[2]
        val workDirectory =
            Path.of(arguments[3]).toAbsolutePath().normalize()
        val report = Path.of(arguments[4]).toAbsolutePath().normalize()
        val headlessLauncher = arguments.getOrNull(5)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()

        require(Files.isRegularFile(javaExecutable)) {
            "Minecraft analysis Java does not exist: $javaExecutable"
        }
        require(Files.isDirectory(minecraftDirectory)) {
            "Prepared Minecraft client directory does not exist: " +
                    minecraftDirectory
        }
        if (headlessLauncher != null) {
            require(Files.isRegularFile(headlessLauncher)) {
                "HeadlessMC launcher does not exist: $headlessLauncher"
            }
        }
        Files.createDirectories(workDirectory)
        Files.createDirectories(report.parent)

        val installation = ClientInstallation.load(
            minecraftDirectory = minecraftDirectory,
            version = version,
        )
        require(
            installation.javaMajorVersion == javaMajorVersion(javaExecutable),
        ) {
            "Minecraft $version requires Java ${installation.javaMajorVersion}"
        }
        val runDirectory = workDirectory.resolve(
            "run-${System.currentTimeMillis()}",
        )
        val gameDirectory = runDirectory.resolve("game")
        val nativeDirectory = runDirectory.resolve("natives")
        Files.createDirectories(gameDirectory)
        Files.createDirectories(nativeDirectory)
        installation.extractNativeLibraries(nativeDirectory)
        writeClientOptions(gameDirectory)

        val clientLog = StringBuilder()
        var process: Process? = null
        var logThread: Thread? = null
        try {
            val outcome = runBlocking {
                SelectorManager(Dispatchers.IO).use { selector ->
                    MinecraftServer.bind(
                        selectorManager = selector,
                        host = "127.0.0.1",
                        port = 0,
                        configuration = MinecraftServerConfiguration(
                            compressionThreshold = 64,
                            viewDistance = 2,
                            simulationDistance = 5,
                            statusDescription =
                                "minecraft-protocol official client E2E",
                        ),
                    ).use { server ->
                        val launched = launchClient(
                            javaExecutable = javaExecutable,
                            minecraftDirectory = minecraftDirectory,
                            installation = installation,
                            gameDirectory = gameDirectory,
                            nativeDirectory = nativeDirectory,
                            port = server.port,
                            headlessLauncher = headlessLauncher,
                        )
                        process = launched
                        logThread = captureLog(
                            process = launched,
                            clientLog = clientLog,
                            output = runDirectory.resolve("client.log"),
                        )
                        awaitPlayRoundTrip(server, launched)
                    }
                }
            }
            writeReport(
                output = report,
                installation = installation,
                outcome = outcome,
                headless = headlessLauncher != null,
            )
            println(
                "Official Minecraft ${installation.version} client reached " +
                        "Play, accepted flat chunks and remained active with an " +
                        "initial entity" +
                        if (headlessLauncher == null) {
                            ""
                        } else {
                            " without a display server"
                        },
            )
        } catch (failure: Throwable) {
            val log = synchronized(clientLog) { clientLog.toString() }
            throw AssertionError(
                "Official client -> production initial-world E2E failed.\n" +
                        "--- official client log ---\n$log",
                failure,
            )
        } finally {
            process?.let(::stopProcess)
            logThread?.join(Duration.ofSeconds(5))
        }
    }

    private suspend fun awaitPlayRoundTrip(
        server: MinecraftServer,
        process: Process,
    ): EndToEndOutcome {
        val processWatcher = Thread.ofVirtual()
            .name("official-client-e2e-process")
            .start {
                try {
                    process.waitFor()
                    server.close()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        try {
            return withTimeout(Duration.ofMinutes(2).toMillis()) {
                var statusConnections = 0
                while (true) {
                    check(process.isAlive) {
                        "Official client exited with ${process.exitValue()}"
                    }
                    val connection = try {
                        server.accept()
                    } catch (failure: Throwable) {
                        if (!process.isAlive) {
                            error(
                                "Official client exited with " +
                                        process.exitValue(),
                            )
                        }
                        throw failure
                    }
                    try {
                        when (val negotiation = connection.negotiate()) {
                            MinecraftServerNegotiationResult.StatusCompleted -> {
                                statusConnections++
                                connection.close()
                            }

                            is MinecraftServerNegotiationResult.PlayReady -> {
                                val pig = MinecraftEntitySnapshot(
                                    entityId = 2,
                                    uuid = Uuid(0, 2),
                                    type = Identifier("pig"),
                                    position = Vector3d(3.5, 65.0, 3.5),
                                )
                                val world = MinecraftInitialWorld.flatVanilla(
                                    configuration = server.configuration,
                                    entities = listOf(pig),
                                )
                                val synchronization =
                                    connection.synchronizeInitialWorld(world)
                                connection.session.send(
                                    PlayClientboundKeepAlivePacket(KEEP_ALIVE_ID),
                                )
                                val observed = mutableListOf<String>()
                                var teleportAcknowledged = false
                                var chunkBatchAcknowledged = false
                                var keepAliveAcknowledged = false
                                var clientTickObserved = false
                                repeat(
                                    server.configuration.maximumPacketsPerPhase,
                                ) {
                                    val packet = connection.session.receive()
                                    observed +=
                                        packet::class.simpleName ?: "<anonymous>"
                                    when (packet) {
                                        is ConfirmTeleportationPacket ->
                                            teleportAcknowledged =
                                                packet.teleportId ==
                                                        synchronization.teleportId

                                        is ChunkBatchReceivedPacket ->
                                            chunkBatchAcknowledged = true

                                        is PlayServerboundKeepAlivePacket ->
                                            keepAliveAcknowledged =
                                                packet.id == KEEP_ALIVE_ID

                                        is ClientTickEndPacket ->
                                            clientTickObserved = true

                                        else -> Unit
                                    }
                                    if (
                                        teleportAcknowledged &&
                                        chunkBatchAcknowledged &&
                                        keepAliveAcknowledged &&
                                        clientTickObserved
                                    ) {
                                        delay(CONNECTION_STABILITY_DELAY_MILLIS)
                                        check(process.isAlive) {
                                            "Official client exited after " +
                                                    "initial world synchronization"
                                        }
                                        connection.close()
                                        return@withTimeout EndToEndOutcome(
                                            statusConnections =
                                                statusConnections,
                                            playerName =
                                                negotiation.profile.name,
                                            acceptedKnownPacks =
                                                negotiation.acceptedKnownPacks
                                                    .size,
                                            synchronizedChunks =
                                                synchronization.chunkCount,
                                            synchronizedEntities =
                                                synchronization.entityCount,
                                            entityType = pig.type.toString(),
                                            entityTypeId = pig.typeId,
                                            teleportAcknowledged = true,
                                            chunkBatchAcknowledged = true,
                                            clientTickObserved = true,
                                            clientRemainedConnected = true,
                                            observedPlayPackets = observed,
                                        )
                                    }
                                }
                                error(
                                    "Client did not complete initial-world " +
                                            "acknowledgements; teleport=" +
                                            "$teleportAcknowledged, chunkBatch=" +
                                            "$chunkBatchAcknowledged, keepAlive=" +
                                            "$keepAliveAcknowledged, clientTick=" +
                                            "$clientTickObserved; observed " +
                                            observed.joinToString(),
                                )
                            }
                        }
                    } catch (failure: Throwable) {
                        connection.close()
                        throw failure
                    }
                }
                error("Unreachable")
            }
        } finally {
            processWatcher.interrupt()
        }
    }

    private fun launchClient(
        javaExecutable: Path,
        minecraftDirectory: Path,
        installation: ClientInstallation,
        gameDirectory: Path,
        nativeDirectory: Path,
        port: Int,
        headlessLauncher: Path?,
    ): Process =
        if (headlessLauncher == null) {
            launchDesktopClient(
                javaExecutable = javaExecutable,
                installation = installation,
                gameDirectory = gameDirectory,
                nativeDirectory = nativeDirectory,
                port = port,
            )
        } else {
            launchHeadlessClient(
                javaExecutable = javaExecutable,
                minecraftDirectory = minecraftDirectory,
                installation = installation,
                gameDirectory = gameDirectory,
                launcher = headlessLauncher,
                port = port,
            )
        }

    private fun launchDesktopClient(
        javaExecutable: Path,
        installation: ClientInstallation,
        gameDirectory: Path,
        nativeDirectory: Path,
        port: Int,
    ): Process {
        val command = buildList {
            add(javaExecutable.absolutePathString())
            add("-Xms256M")
            add("-Xmx1G")
            add("--sun-misc-unsafe-memory-access=allow")
            add("--enable-native-access=ALL-UNNAMED")
            add("-Djava.library.path=${nativeDirectory.absolutePathString()}")
            add("-Djna.tmpdir=${nativeDirectory.absolutePathString()}")
            add(
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=" +
                        nativeDirectory.absolutePathString(),
            )
            add(
                "-Dio.netty.native.workdir=" +
                        nativeDirectory.absolutePathString(),
            )
            add("-Dminecraft.launcher.brand=minecraft-protocol-e2e")
            add("-Dminecraft.launcher.version=1")
            add("-cp")
            add(installation.classpath)
            add(installation.mainClass)
            addAll(
                listOf(
                    "--username",
                    PLAYER_NAME,
                    "--version",
                    installation.version,
                    "--gameDir",
                    gameDirectory.absolutePathString(),
                    "--assetsDir",
                    installation.assetsDirectory.absolutePathString(),
                    "--assetIndex",
                    installation.assetIndex,
                    "--uuid",
                    offlineUuid(PLAYER_NAME).toUndashedString(),
                    "--accessToken",
                    "0",
                    "--clientId",
                    "0",
                    "--xuid",
                    "0",
                    "--versionType",
                    "release",
                    "--width",
                    "854",
                    "--height",
                    "480",
                    "--quickPlayMultiplayer",
                    "127.0.0.1:$port",
                ),
            )
        }
        return ProcessBuilder(command)
            .directory(gameDirectory.toFile())
            .redirectErrorStream(true)
            .start()
    }

    private fun launchHeadlessClient(
        javaExecutable: Path,
        minecraftDirectory: Path,
        installation: ClientInstallation,
        gameDirectory: Path,
        launcher: Path,
        port: Int,
    ): Process {
        val playerUuid = offlineUuid(PLAYER_NAME).toUndashedString()
        val minecraftJvmArguments = listOf(
            "-Xms256M",
            "-Xmx1G",
            "-Djava.awt.headless=true",
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
        ).joinToString(" ")
        val command = listOf(
            javaExecutable.absolutePathString(),
            "-Xms128M",
            "-Xmx512M",
            "-Dhmc.mcdir=${minecraftDirectory.absolutePathString()}",
            "-Dhmc.gamedir=${gameDirectory.absolutePathString()}",
            "-Dhmc.java.versions=${javaExecutable.absolutePathString()}",
            "-Dhmc.no.auto.config=true",
            "-Dhmc.java.use.current=false",
            "-Dhmc.java.require.exact=true",
            "-Dhmc.auto.download.java=false",
            "-Dhmc.auto.download.versions=false",
            "-Dhmc.account.refresh.on.game.launch=false",
            "-Dhmc.account.refresh.on.launch=false",
            "-Dhmc.store.accounts=false",
            "-Dhmc.offline=true",
            "-Dhmc.offline.username=$PLAYER_NAME",
            "-Dhmc.offline.uuid=$playerUuid",
            "-Dhmc.offline.token=0",
            "-Dhmc.jvmargs=$minecraftJvmArguments",
            "-Dhmc.gameargs=--quickPlayMultiplayer 127.0.0.1:$port",
            "-Dhmc.jline.enabled=false",
            "-Dhmc.filehandler.enabled=false",
            "-Dhmc.rethrow.launch.exceptions=true",
            "-Dhmc.exit.on.failed.command=true",
            "-Dhmc.crash.report.watcher=true",
            "-Dhmc.check.xvfb=false",
            "-jar",
            launcher.absolutePathString(),
            "--command",
            "launch",
            installation.version,
            "-lwjgl",
            "-offline",
        )
        return ProcessBuilder(command)
            .directory(gameDirectory.parent.toFile())
            .redirectErrorStream(true)
            .start()
    }

    private fun captureLog(
        process: Process,
        clientLog: StringBuilder,
        output: Path,
    ): Thread =
        Thread.ofVirtual().name("official-client-e2e-log").start {
            Files.newBufferedWriter(output).use { writer ->
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        writer.appendLine(line)
                        writer.flush()
                        synchronized(clientLog) {
                            clientLog.appendLine(line)
                            if (clientLog.length > 300_000) {
                                clientLog.delete(
                                    0,
                                    clientLog.length - 200_000,
                                )
                            }
                        }
                    }
                }
            }
        }

    private fun stopProcess(process: Process) {
        val descendants = process.toHandle().descendants().toList()
        descendants.asReversed().forEach { handle ->
            if (handle.isAlive) handle.destroy()
        }
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            descendants.asReversed().forEach { handle ->
                if (handle.isAlive) handle.destroyForcibly()
            }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private fun writeClientOptions(gameDirectory: Path) {
        Files.writeString(
            gameDirectory.resolve("options.txt"),
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

    private fun writeReport(
        output: Path,
        installation: ClientInstallation,
        outcome: EndToEndOutcome,
        headless: Boolean,
    ) {
        Files.writeString(
            output,
            """
            |{
            |  "schema_version": 2,
            |  "minecraft_version": "${installation.version}",
            |  "protocol_version": ${MinecraftProtocol.PROTOCOL_VERSION},
            |  "official_client_sha1": "${installation.clientSha1}",
            |  "client": "${
                if (headless) {
                    "official client with HeadlessMC LWJGL stubs"
                } else {
                    "unmodified official desktop client"
                }
            }",
            |  "headless": $headless,
            |  "server_stack": "protocol-server -> protocol-session -> protocol-transport",
            |  "online_mode": false,
            |  "status_connections": ${outcome.statusConnections},
            |  "player_name": "${outcome.playerName}",
            |  "accepted_known_packs": ${outcome.acceptedKnownPacks},
            |  "login_completed": true,
            |  "configuration_completed": true,
            |  "play_login_processed": true,
            |  "play_keep_alive_round_trip": true,
            |  "synchronized_chunks": ${outcome.synchronizedChunks},
            |  "synchronized_entities": ${outcome.synchronizedEntities},
            |  "entity_type": "${outcome.entityType}",
            |  "entity_type_id": ${outcome.entityTypeId},
            |  "teleport_acknowledged": ${outcome.teleportAcknowledged},
            |  "chunk_batch_acknowledged": ${outcome.chunkBatchAcknowledged},
            |  "client_tick_observed": ${outcome.clientTickObserved},
            |  "client_remained_connected": ${outcome.clientRemainedConnected},
            |  "observed_play_packets": [
            |${
                outcome.observedPlayPackets.joinToString(",\n") {
                    "    \"$it\""
                }
            }
            |  ]
            |}
            """.trimMargin() + "\n",
        )
    }
}

private data class EndToEndOutcome(
    val statusConnections: Int,
    val playerName: String,
    val acceptedKnownPacks: Int,
    val synchronizedChunks: Int,
    val synchronizedEntities: Int,
    val entityType: String,
    val entityTypeId: Int,
    val teleportAcknowledged: Boolean,
    val chunkBatchAcknowledged: Boolean,
    val clientTickObserved: Boolean,
    val clientRemainedConnected: Boolean,
    val observedPlayPackets: List<String>,
)

private data class ClientInstallation(
    val version: String,
    val mainClass: String,
    val javaMajorVersion: Int,
    val assetIndex: String,
    val assetsDirectory: Path,
    val clientJar: Path,
    val clientSha1: String,
    val libraries: List<ClientLibrary>,
) {
    val classpath: String
        get() = (libraries.map(ClientLibrary::file) + listOf(clientJar))
            .joinToString(File.pathSeparator)

    fun extractNativeLibraries(output: Path) {
        libraries.asSequence()
            .filter(ClientLibrary::nativeForCurrentArchitecture)
            .forEach { library ->
                JarFile(library.file.toFile()).use { jar ->
                    jar.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .filter { entry ->
                            entry.name.substringAfterLast('.')
                                .lowercase() in NATIVE_EXTENSIONS
                        }
                        .filter { entry ->
                            !entry.name.startsWith("META-INF/") &&
                                    entryMatchesArchitecture(entry.name)
                        }
                        .forEach { entry ->
                            val target = output.resolve(
                                entry.name.substringAfterLast('/'),
                            )
                            jar.getInputStream(entry).use { input ->
                                Files.copy(
                                    input,
                                    target,
                                    StandardCopyOption.REPLACE_EXISTING,
                                )
                            }
                        }
                }
            }
    }

    companion object {
        fun load(
            minecraftDirectory: Path,
            version: String,
        ): ClientInstallation {
            val versionDirectory =
                minecraftDirectory.resolve("versions").resolve(version)
            val jsonPath = versionDirectory.resolve("$version.json")
            val clientJar = versionDirectory.resolve("$version.jar")
            require(Files.isRegularFile(jsonPath)) {
                "Official client metadata does not exist: $jsonPath"
            }
            require(Files.isRegularFile(clientJar)) {
                "Official client JAR does not exist: $clientJar"
            }
            val root = Json.parseToJsonElement(
                Files.readString(jsonPath),
            ).jsonObject
            val expectedClientSha1 = root.requiredObject("downloads")
                .requiredObject("client")
                .requiredString("sha1")
            require(sha1(clientJar) == expectedClientSha1) {
                "Official client JAR failed its Mojang SHA-1"
            }
            val libraries = root.requiredArray("libraries")
                .map { it.jsonObject }
                .filter(::isAllowedOnCurrentPlatform)
                .flatMap { library ->
                    val coordinate = library.requiredString("name")
                    val downloads = library.requiredObject("downloads")
                    buildList {
                        (downloads["artifact"] as? JsonObject)?.let {
                            add(
                                loadLibrary(
                                    minecraftDirectory,
                                    coordinate,
                                    it,
                                ),
                            )
                        }
                        (downloads["classifiers"] as? JsonObject)
                            ?.forEach { (classifier, element) ->
                                if (
                                    isNativeClassifierForCurrentArchitecture(
                                        classifier,
                                    )
                                ) {
                                    add(
                                        loadLibrary(
                                            minecraftDirectory,
                                            "$coordinate:$classifier",
                                            element.jsonObject,
                                        ),
                                    )
                                }
                            }
                    }
                }
            val assetIndex = root.requiredObject("assetIndex")
            val assetIndexId = assetIndex.requiredString("id")
            val assetsDirectory = minecraftDirectory.resolve("assets")
            val assetIndexPath = assetsDirectory
                .resolve("indexes")
                .resolve("$assetIndexId.json")
                .normalize()
            require(Files.isRegularFile(assetIndexPath)) {
                "Official client asset index does not exist: $assetIndexPath"
            }
            require(Files.size(assetIndexPath) == assetIndex.requiredLong("size")) {
                "Official client asset index has the wrong size: $assetIndexPath"
            }
            require(sha1(assetIndexPath) == assetIndex.requiredString("sha1")) {
                "Official client asset index failed its Mojang SHA-1"
            }
            validateAssets(
                assetsDirectory = assetsDirectory,
                index = Json.parseToJsonElement(
                    Files.readString(assetIndexPath),
                ).jsonObject,
            )
            return ClientInstallation(
                version = version,
                mainClass = root.requiredString("mainClass"),
                javaMajorVersion = root.requiredObject("javaVersion")
                    .requiredInt("majorVersion"),
                assetIndex = assetIndexId,
                assetsDirectory = assetsDirectory,
                clientJar = clientJar,
                clientSha1 = expectedClientSha1,
                libraries = libraries,
            )
        }

        private fun loadLibrary(
            minecraftDirectory: Path,
            coordinate: String,
            artifact: JsonObject,
        ): ClientLibrary {
            val file = minecraftDirectory
                .resolve("libraries")
                .resolve(artifact.requiredString("path"))
                .normalize()
            require(Files.isRegularFile(file)) {
                "Official client library does not exist: $file"
            }
            require(Files.size(file) == artifact.requiredLong("size")) {
                "Official client library has the wrong size: $file"
            }
            require(sha1(file) == artifact.requiredString("sha1")) {
                "Official client library failed its Mojang SHA-1: $file"
            }
            return ClientLibrary(coordinate, file)
        }

        private fun validateAssets(
            assetsDirectory: Path,
            index: JsonObject,
        ) {
            index.requiredObject("objects").forEach { (name, element) ->
                val asset = element.jsonObject
                val hash = asset.requiredString("hash")
                require(SHA1.matches(hash)) {
                    "Official client asset $name has an invalid SHA-1"
                }
                val file = assetsDirectory
                    .resolve("objects")
                    .resolve(hash.substring(0, 2))
                    .resolve(hash)
                    .normalize()
                require(Files.isRegularFile(file)) {
                    "Official client asset does not exist: $name ($file)"
                }
                require(Files.size(file) == asset.requiredLong("size")) {
                    "Official client asset has the wrong size: $name ($file)"
                }
                require(sha1(file) == hash) {
                    "Official client asset failed its Mojang SHA-1: " +
                            "$name ($file)"
                }
            }
        }
    }
}

private data class ClientLibrary(
    val coordinate: String,
    val file: Path,
) {
    val nativeForCurrentArchitecture: Boolean
        get() = isNativeClassifierForCurrentArchitecture(
            coordinate.substringAfterLast(':'),
        )
}

private fun isNativeClassifierForCurrentArchitecture(
    classifier: String,
): Boolean {
    if (!classifier.startsWith("natives-")) return false
    return when {
        CURRENT_OS == "windows" && CURRENT_ARCH == "x64" ->
            classifier == "natives-windows"

        CURRENT_OS == "windows" && CURRENT_ARCH == "x86" ->
            classifier == "natives-windows-x86"

        CURRENT_OS == "windows" && CURRENT_ARCH == "arm64" ->
            classifier == "natives-windows-arm64"

        CURRENT_OS == "linux" && CURRENT_ARCH == "x64" ->
            classifier == "natives-linux"

        CURRENT_OS == "linux" && CURRENT_ARCH == "arm64" ->
            classifier == "natives-linux-arm64"

        CURRENT_OS == "osx" && CURRENT_ARCH == "x64" ->
            classifier == "natives-macos"

        CURRENT_OS == "osx" && CURRENT_ARCH == "arm64" ->
            classifier == "natives-macos-arm64"

        else -> false
    }
}

private fun isAllowedOnCurrentPlatform(library: JsonObject): Boolean {
    val rules = library["rules"] as? JsonArray ?: return true
    var allowed = false
    rules.forEach { element ->
        val rule = element.jsonObject
        if (ruleMatchesCurrentPlatform(rule)) {
            allowed = rule.requiredString("action") == "allow"
        }
    }
    return allowed
}

private fun ruleMatchesCurrentPlatform(rule: JsonObject): Boolean {
    val operatingSystem = rule["os"] as? JsonObject
    if (operatingSystem != null) {
        val name = operatingSystem["name"]
            ?.jsonPrimitive
            ?.contentOrNull
        if (name != null && name != CURRENT_OS) return false
        val architecture = operatingSystem["arch"]
            ?.jsonPrimitive
            ?.contentOrNull
        if (architecture != null && !Regex(architecture).matches(CURRENT_ARCH)) {
            return false
        }
        val version = operatingSystem["version"]
            ?.jsonPrimitive
            ?.contentOrNull
        if (
            version != null &&
            !Regex(version).containsMatchIn(System.getProperty("os.version"))
        ) {
            return false
        }
    }
    val features = rule["features"] as? JsonObject
    if (features != null) {
        return features.values.all {
            it.jsonPrimitive.booleanOrNull == false
        }
    }
    return true
}

private fun entryMatchesArchitecture(name: String): Boolean {
    val normalized = name.lowercase()
    return when (CURRENT_ARCH) {
        "x64" ->
            "/arm64/" !in normalized &&
                    "/aarch64/" !in normalized &&
                    "/x86/" !in normalized

        "arm64" ->
            "/x64/" !in normalized &&
                    "/x86_64/" !in normalized &&
                    "/x86/" !in normalized

        "x86" ->
            "/x64/" !in normalized &&
                    "/x86_64/" !in normalized &&
                    "/arm64/" !in normalized &&
                    "/aarch64/" !in normalized

        else -> false
    }
}

private fun javaMajorVersion(javaExecutable: Path): Int {
    val process = ProcessBuilder(
        javaExecutable.absolutePathString(),
        "-version",
    )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(15, TimeUnit.SECONDS)) {
        "Timed out querying $javaExecutable"
    }
    check(process.exitValue() == 0) {
        "Could not query $javaExecutable: $output"
    }
    return Regex("""version "(\d+)""")
        .find(output)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: error("Could not parse Java version from: $output")
}

private fun sha1(path: Path): String =
    MessageDigest.getInstance("SHA-1").run {
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                update(buffer, 0, read)
            }
        }
        digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

private fun JsonObject.requiredObject(name: String): JsonObject =
    getValue(name).jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray =
    getValue(name).jsonArray

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredInt(name: String): Int =
    requiredString(name).toInt()

private fun JsonObject.requiredLong(name: String): Long =
    requiredString(name).toLong()

private val CURRENT_OS: String = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
        "windows"

    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
        "osx"

    else -> "linux"
}

private val CURRENT_ARCH: String = when (
    System.getProperty("os.arch").lowercase()
) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    "x86", "i386", "i486", "i586", "i686" -> "x86"
    else -> System.getProperty("os.arch").lowercase()
}

private val NATIVE_EXTENSIONS = setOf("dll", "so", "dylib", "jnilib")
private val SHA1 = Regex("[0-9a-f]{40}")
private const val CONNECTION_STABILITY_DELAY_MILLIS: Long = 1_500
