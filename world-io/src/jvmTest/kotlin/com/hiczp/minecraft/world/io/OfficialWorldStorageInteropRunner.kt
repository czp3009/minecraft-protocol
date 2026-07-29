package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.protocol.model.type.NbtCompound
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlinx.io.files.Path as IoPath

/**
 * Generates a world with the exact official server, rewrites its NBT and
 * region containers through this library, then requires the official server
 * to load and save the rewritten world.
 */
internal object OfficialWorldStorageInteropRunner {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 4) {
            "Expected <analysis-java> <official-server.jar> " +
                    "<work-directory> <report.json>"
        }
        val javaExecutable = Path.of(arguments[0]).toAbsolutePath().normalize()
        val serverJar = Path.of(arguments[1]).toAbsolutePath().normalize()
        val workDirectory = Path.of(arguments[2]).toAbsolutePath().normalize()
        val report = Path.of(arguments[3]).toAbsolutePath().normalize()
        require(Files.isRegularFile(javaExecutable))
        require(Files.isRegularFile(serverJar))

        recreateDirectory(workDirectory)
        Files.createDirectories(report.parent)
        Files.writeString(workDirectory.resolve("eula.txt"), "eula=true\n")

        runOfficialServer(
            javaExecutable,
            serverJar,
            workDirectory,
            generateChunk = true,
        )
        val worldDirectory = workDirectory.resolve(WORLD_NAME)
        val before = auditAndRewrite(worldDirectory, rewrite = true)
        check(before.regionFiles > 0) {
            "Official server did not generate a non-empty region file"
        }
        check(before.chunks > 0) {
            "Official server did not generate a readable chunk"
        }

        runOfficialServer(
            javaExecutable,
            serverJar,
            workDirectory,
            generateChunk = false,
        )
        val after = auditAndRewrite(worldDirectory, rewrite = false)
        check(after.chunks > 0)

        Files.writeString(
            report,
            """
            |{
            |  "status": "passed",
            |  "world": "$WORLD_NAME",
            |  "region_files_before": ${before.regionFiles},
            |  "chunks_before": ${before.chunks},
            |  "region_files_after": ${after.regionFiles},
            |  "chunks_after": ${after.chunks}
            |}
            """.trimMargin() + "\n",
        )
        println(
            "Official world storage interop passed: " +
                    "${after.regionFiles} region file(s), ${after.chunks} chunk(s)",
        )
    }

    private fun runOfficialServer(
        javaExecutable: Path,
        serverJar: Path,
        workDirectory: Path,
        generateChunk: Boolean,
    ) {
        val port = ServerSocket(0).use { it.localPort }
        Files.writeString(
            workDirectory.resolve("server.properties"),
            """
            |enable-status=false
            |generate-structures=false
            |level-name=$WORLD_NAME
            |level-type=minecraft:flat
            |max-players=1
            |max-tick-time=-1
            |online-mode=false
            |server-ip=127.0.0.1
            |server-port=$port
            |simulation-distance=2
            |spawn-protection=0
            |sync-chunk-writes=true
            |view-distance=2
            """.trimMargin(),
        )

        val log = StringBuilder()
        val process = ProcessBuilder(
            javaExecutable.absolutePathString(),
            "-Djava.awt.headless=true",
            "-jar",
            serverJar.absolutePathString(),
            "nogui",
        )
            .directory(workDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val logThread = Thread.ofVirtual().name("official-world-log").start {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(log) {
                        log.appendLine(line)
                        if (log.length > 300_000) {
                            log.delete(0, log.length - 200_000)
                        }
                    }
                }
            }
        }

        try {
            waitForLog(process, log, "[Server thread/INFO]: Done (")
            if (generateChunk) {
                process.outputWriter().apply {
                    write("forceload add 0 0\n")
                    write("save-all flush\n")
                    flush()
                }
                waitForLog(process, log, "Saved the game")
            }
            process.outputWriter().apply {
                write("stop\n")
                flush()
            }
            check(process.waitFor(90, TimeUnit.SECONDS)) {
                "Official server did not stop within 90 seconds"
            }
            check(process.exitValue() == 0) {
                "Official server exited with ${process.exitValue()}"
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Official world interoperability failed.\n" +
                        "--- official server log ---\n" +
                        synchronized(log) { log.toString() },
                failure,
            )
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(10, TimeUnit.SECONDS)
                }
            }
            logThread.join(Duration.ofSeconds(5))
        }
    }

    private fun waitForLog(
        process: Process,
        log: StringBuilder,
        text: String,
    ) {
        val deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos()
        while (System.nanoTime() < deadline) {
            check(process.isAlive) {
                "Official server exited before log marker '$text'"
            }
            if (synchronized(log) { text in log }) return
            Thread.sleep(100)
        }
        error("Official server did not emit '$text' within three minutes")
    }

    private fun auditAndRewrite(
        worldDirectory: Path,
        rewrite: Boolean,
    ): AuditResult = runBlocking {
        val paths = MinecraftWorldPaths(IoPath(worldDirectory.toString()))
        val nbtFiles = NbtFileStore()
        val levelData = nbtFiles.read(paths.levelData)
        check(levelData.root.value["Data"] is NbtCompound) {
            "Official level.dat has no Data compound"
        }
        if (rewrite) {
            nbtFiles.write(paths.levelData, levelData)
        }

        val regions = WorldRegionStore(paths)
        var regionFileCount = 0
        var chunkCount = 0
        RegionStorageDirectory.entries.forEach { storage ->
            val directory = paths.regionDirectory(storage)
            if (!regions.fileSystem.exists(directory)) return@forEach
            regions.fileSystem.list(directory)
                .mapNotNull { path ->
                    REGION_FILE_NAME.matchEntire(path.name)?.let {
                        RegionPosition(
                            it.groupValues[1].toInt(),
                            it.groupValues[2].toInt(),
                        )
                    }
                }
                .forEach { position ->
                    val path = paths.regionFile(position, storage)
                    val metadata = regions.fileSystem.metadataOrNull(path)
                    if (metadata == null) return@forEach
                    val region = regions.readRegion(position, storage)
                        ?: error("Region disappeared while reading: $path")
                    regionFileCount++
                    region.chunks.values.forEach { chunk ->
                        regions.chunkNbtFormat.decode(chunk)
                        chunkCount++
                    }
                    if (rewrite) {
                        regions.writeRegion(position, region, storage)
                    }
                }
        }
        AuditResult(regionFileCount, chunkCount)
    }

    private fun recreateDirectory(directory: Path) {
        if (Files.exists(directory)) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        Files.createDirectories(directory)
    }

    private data class AuditResult(
        val regionFiles: Int,
        val chunks: Int,
    )

    private const val WORLD_NAME = "world-storage-interop"
    private val REGION_FILE_NAME = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")
}
