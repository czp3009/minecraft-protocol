package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.protocol.model.type.NbtCompound
import com.hiczp.minecraft.test.MinecraftTestEnvironment
import com.hiczp.minecraft.test.startOfficialServer
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.io.files.Path as IoPath

/**
 * Generates a world with the exact official server, rewrites its NBT and
 * region containers through this library, then requires the official server
 * to load and save the rewritten world.
 */
internal object OfficialWorldStorageInteropRunner {
    fun run(
        environment: MinecraftTestEnvironment,
        workDirectory: Path,
        report: Path,
    ) {
        recreateDirectory(workDirectory)
        Files.createDirectories(report.parent)
        Files.writeString(workDirectory.resolve("eula.txt"), "eula=true\n")

        runOfficialServer(
            environment,
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
            environment,
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
        environment: MinecraftTestEnvironment,
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

        environment.startOfficialServer(
            workDirectory = workDirectory,
            threadName = "official-world-log",
        ).use { server ->
            try {
                server.waitForLog(
                    "[Server thread/INFO]: Done (",
                    Duration.ofMinutes(3),
                )
                if (generateChunk) {
                    server.sendLine("forceload add 0 0")
                    server.sendLine("save-all flush")
                    server.waitForLog(
                        "Saved the game",
                        Duration.ofMinutes(3),
                    )
                }
                server.sendLine("stop")
                val exitCode = server.awaitExit(Duration.ofSeconds(90))
                check(exitCode != null) {
                    "Official server did not stop within 90 seconds"
                }
                check(exitCode == 0) {
                    "Official server exited with $exitCode"
                }
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Official world interoperability failed.\n" +
                            "--- official server log ---\n" +
                            server.logText(),
                    failure,
                )
            }
        }
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
                    regions.fileSystem.metadataOrNull(path) ?: return@forEach
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
