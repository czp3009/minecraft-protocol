package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path

internal data class MinecraftTestLayout(
    val minecraftVersion: String,
    val serverCacheDirectory: Path,
    val clientCacheDirectory: Path,
    val versionMetadataFile: Path,
    val headlessLauncherFile: Path,
    val serverRuntimeDirectory: Path,
    val codecClassesDirectory: Path,
    val hostWorkRoot: Path,
    val javaExecutable: Path,
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
    OFFICIAL_CLIENT("official-client"),
}

internal data class OfficialServerArtifact(
    internal val jar: Path,
)

internal object OfficialArtifacts {
    fun server(layout: MinecraftTestLayout): OfficialServerArtifact {
        val directory = layout.serverCacheDirectory
        val jar = Path(directory, "server.jar")
        val metadataFile = Path(directory, "download-metadata.json")
        check(jar.isRegularFile() && metadataFile.isRegularFile()) {
            "The official server fixture is missing; run this test through its standard Gradle test task"
        }
        val metadata = metadataFile.readJsonObject()
        check(metadata.requiredString("minecraft_version") == layout.minecraftVersion) {
            "The official server fixture belongs to a different Minecraft version"
        }
        return OfficialServerArtifact(
            jar = jar,
        )
    }

    fun headlessLauncher(layout: MinecraftTestLayout): Path {
        val launcher = layout.headlessLauncherFile
        check(launcher.isRegularFile()) {
            "The verified HeadlessMC fixture is missing: $launcher. Run this test through its standard Gradle test task."
        }
        return launcher
    }
}
