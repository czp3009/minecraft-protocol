package com.hiczp.minecraft.test.host

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal data class OfficialServerRuntime(
    val directory: Path,
    val implementationJar: Path,
    val libraries: List<Path>,
)

/**
 * Reads the server runtime prepared by the
 * `prepareOfficialMinecraftCodecOracle` Gradle gate. No lock or extraction
 * happens at test time; this is pure disk I/O.
 */
internal fun officialServerRuntime(): OfficialServerRuntime {
    val minecraftTestLayout = HostedMinecraftTestSupport.minecraftTestLayout
    val output = minecraftTestLayout.serverRuntimeDirectory
    check(output.isDirectory()) {
        "Official runtime is absent: $output; run the Gradle prepareOfficialMinecraftCodecOracle task first"
    }
    val implementation = output.resolve("server.jar")
    check(implementation.isRegularFile()) {
        "Official runtime implementation JAR is missing: $implementation"
    }
    val librariesDir = output.resolve("libraries")
    val libraries = if (librariesDir.isDirectory()) {
        Files.walk(librariesDir, FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths
                .filter { path -> path.isRegularFile() }
                .sorted(compareBy(Path::toString))
                .toList()
        }
    } else {
        emptyList()
    }
    return OfficialServerRuntime(
        directory = output,
        implementationJar = implementation,
        libraries = libraries,
    )
}
