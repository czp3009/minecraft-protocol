package com.hiczp.minecraft.test

import kotlinx.io.files.Path

internal data class OfficialServerRuntime(
    val directory: Path,
    val implementationJar: Path,
    val libraries: List<Path>,
)

/**
 * Reads the pre-extracted server runtime (produced by the
 * `extractOfficialServerRuntime` Gradle task).  No lock or extraction happens
 * at test time; this is pure disk I/O.
 */
internal fun officialServerRuntime(): OfficialServerRuntime {
    val layout = MinecraftTestSupport.layout
    OfficialArtifacts.server(layout)
    val output = Path(layout.serverCacheDirectory(), "runtime")
    return loadServerRuntime(output)
}

internal fun loadServerRuntime(
    output: Path,
): OfficialServerRuntime {
    check(output.isDirectory()) {
        "Official runtime is absent: $output; run the Gradle extractOfficialServerRuntime task first"
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
    kotlinx.io.files.SystemFileSystem.list(dir).asSequence().flatMap { child ->
        val full = Path(dir, child.name)
        if (kotlinx.io.files.SystemFileSystem.metadataOrNull(full)?.isDirectory == true) {
            collectFiles(full)
        } else {
            sequenceOf(full)
        }
    }
