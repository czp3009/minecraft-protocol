package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.protocol.model.type.NbtCompound
import com.hiczp.minecraft.test.MinecraftTestSupport
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import com.hiczp.minecraft.test.OfficialMinecraftServerResource
import com.hiczp.minecraft.test.useRemote
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.io.files.Path

/**
 * Generates a world with the exact official server, rewrites its NBT and
 * region containers through this library, then requires the official server
 * to load and save the rewritten world.
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
        ).useRemote { server ->
            runOfficialServer(server, generateChunk = true)
            val before = server.withWorldSnapshot(writeBack = true) {
                auditAndRewrite(it, rewrite = true)
            }
            check(before.regionFiles > 0) {
                "Official server did not generate a non-empty region file"
            }
            check(before.chunks > 0) {
                "Official server did not generate a readable chunk"
            }

            server.restart()
            runOfficialServer(server, generateChunk = false)
            val after = server.withWorldSnapshot(writeBack = false) {
                auditAndRewrite(it, rewrite = false)
            }
            check(after.chunks > 0)
        }
    }

    private suspend fun runOfficialServer(
        server: OfficialMinecraftServerResource,
        generateChunk: Boolean,
    ) {
        try {
            if (generateChunk) {
                server.sendCommand("forceload add 0 0")
                server.sendCommand("save-all flush")
                server.waitForLog("Saved the game")
            }
            val exitCode = server.stop()
            check(exitCode != null) {
                "Official server did not stop within its configured limit"
            }
            check(exitCode == 0) {
                "Official server exited with $exitCode"
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                """
                |Official world interoperability failed.
                |--- official server log ---
                |${server.logText()}
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
