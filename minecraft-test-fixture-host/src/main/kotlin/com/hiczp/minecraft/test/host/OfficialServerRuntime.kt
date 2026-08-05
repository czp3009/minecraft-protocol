package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
    val layout = HostedMinecraftTestSupport.layout
    val output = layout.serverRuntimeDirectory
    return loadServerRuntime(output)
}

internal fun loadServerRuntime(
    output: Path,
): OfficialServerRuntime {
    check(output.isDirectory()) {
        "Official runtime is absent: $output; run the Gradle prepareOfficialMinecraftCodecOracle task first"
    }
    val implementation = Path(output, "server.jar")
    check(implementation.isRegularFile()) {
        "Official runtime implementation JAR is missing: $implementation"
    }
    val librariesDir = Path(output, "libraries")
    val libraries = if (librariesDir.isDirectory()) {
        collectFiles(librariesDir)
            .sortedBy(Path::toString)
            .toList()
    } else {
        emptyList()
    }
    return OfficialServerRuntime(
        directory = output,
        implementationJar = implementation,
        libraries = libraries,
    )
}

private fun collectFiles(dir: Path): Sequence<Path> =
    SystemFileSystem.list(dir).asSequence().flatMap { child ->
        val full = Path(dir, child.name)
        if (SystemFileSystem.metadataOrNull(full)?.isDirectory == true) {
            collectFiles(full)
        } else {
            sequenceOf(full)
        }
    }
