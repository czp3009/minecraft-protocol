package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path

internal data class MinecraftTestLayout(
    val minecraftVersion: String,
    val officialServerRootDirectory: Path,
    val headlessClientRootDirectory: Path,
    val serverRuntimeDirectory: Path,
    val codecClassesDirectory: Path,
    val hostWorkRoot: Path,
) {
    fun newRuntimeDirectory(kind: MinecraftRuntimeKind): Path =
        createUniqueDirectory(
            Path(
                hostWorkRoot,
                "runtimes",
                kind.directoryName,
                minecraftVersion,
            ),
        )

    fun newScratchDirectory(): Path = createUniqueDirectory(
        Path(
            hostWorkRoot,
            "tmp",
        ),
    )
}

internal enum class MinecraftRuntimeKind(val directoryName: String) {
    OFFICIAL_SERVER("official-server"),
    HEADLESS_CLIENT("headless-client"),
}

internal data class OfficialServerArtifact(
    internal val runtimeDirectory: Path,
    internal val jar: Path,
    internal val templateDirectory: Path,
)

internal object OfficialArtifacts {
    fun server(layout: MinecraftTestLayout): OfficialServerArtifact {
        val directory = layout.officialServerRootDirectory
        val manifestFile = Path(directory, "manifest.json")
        check(directory.isDirectory() && manifestFile.isRegularFile()) {
            "The official server fixture is missing; run this test through its standard Gradle test task"
        }
        val manifest = manifestFile.readJsonObject()
        check(manifest.requiredString("minecraft_version") == layout.minecraftVersion) {
            "The official server fixture belongs to a different Minecraft version"
        }
        val runtimeDirectory = directory.safeResolve(
            manifest.requiredString("relative_server_runtime_directory"),
        )
        val jar = directory.safeResolve(
            manifest.requiredString("relative_server_jar"),
        )
        val template = directory.safeResolve(
            manifest.requiredString("relative_template_directory"),
        )
        check(
            runtimeDirectory.isDirectory() &&
                    jar.isRegularFile() &&
                    template.isDirectory(),
        ) {
            "The official server fixture manifest describes missing prepared inputs"
        }
        return OfficialServerArtifact(
            runtimeDirectory = runtimeDirectory,
            jar = jar,
            templateDirectory = template,
        )
    }
}
