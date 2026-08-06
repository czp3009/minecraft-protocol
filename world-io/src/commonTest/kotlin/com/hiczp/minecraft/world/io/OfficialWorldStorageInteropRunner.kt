package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.test.MinecraftTestSupport
import com.hiczp.minecraft.test.OfficialMinecraftServer
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import com.hiczp.minecraft.test.use
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Generates a world with the exact official server, rewrites its NBT and
 * region containers through this library, then requires the official server
 * to load and save the rewritten world.
 *
 * This scenario dereferences the Fixture Host's absolute working-directory
 * path. Invoke it only from a test source set whose runtime has filesystem
 * access and shares the Host's filesystem namespace. Browser, device, and
 * simulator test source sets must not invoke it.
 */
internal object OfficialWorldStorageInteropRunner {
    suspend fun run() {
        MinecraftTestSupport.newOfficialServer(
            OfficialMinecraftServerConfiguration(
                properties = mapOf(
                    "level-name" to WORLD_NAME,
                    "sync-chunk-writes" to "true",
                ),
            ),
        ).use { initialServer ->
            var server = initialServer
            runOfficialServer(server, generateChunk = true)
            val workingDirectory = Path(
                MinecraftTestSupport.hostWorkingDirectory(server),
            )
            val worldDirectory = Path(workingDirectory, WORLD_NAME)
            val before = auditAndRewrite(worldDirectory, rewrite = true)
            check(before.regionFiles > 0) {
                "Official server did not generate a non-empty region file"
            }
            check(before.chunks > 0) {
                "Official server did not generate a readable chunk"
            }

            server = MinecraftTestSupport.restartServer(server)
            runOfficialServer(server, generateChunk = false)
            val after = auditAndRewrite(worldDirectory, rewrite = false)
            check(after.chunks > 0)

            MinecraftTestSupport.deleteWorkingDirectory(server)
            check(!SystemFileSystem.exists(workingDirectory)) {
                "Fixture Host working directory remained after synchronous deletion"
            }
        }
    }

    private suspend fun runOfficialServer(
        server: OfficialMinecraftServer,
        generateChunk: Boolean,
    ) {
        try {
            if (generateChunk) {
                MinecraftTestSupport.sendCommand(server, "forceload add 0 0")
                MinecraftTestSupport.sendCommand(server, "save-all flush")
                MinecraftTestSupport.waitForLog(server, "Saved the game")
            }
            val exitCode = MinecraftTestSupport.closeProcess(server)
            check(exitCode == 0) {
                "Official server exited with $exitCode"
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                """
                |Official world interoperability failed.
                |--- official server log ---
                |${MinecraftTestSupport.logText(server)}
                """.trimMargin(),
                failure,
            )
        }
    }

    private suspend fun auditAndRewrite(
        worldDirectory: Path,
        rewrite: Boolean,
    ): AuditResult {
        val paths = MinecraftWorldPaths(worldDirectory)
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
        return AuditResult(regionFileCount, chunkCount)
    }

    private data class AuditResult(
        val regionFiles: Int,
        val chunks: Int,
    )

    private const val WORLD_NAME = "world-storage-interop"
    private val REGION_FILE_NAME = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")
}
