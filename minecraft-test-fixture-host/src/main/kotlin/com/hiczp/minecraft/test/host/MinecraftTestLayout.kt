package com.hiczp.minecraft.test.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal data class MinecraftTestLayout(
    val minecraftVersion: String,
    val officialServerRootDirectory: Path,
    val headlessClientRootDirectory: Path,
    val serverRuntimeDirectory: Path,
    val codecClassesDirectory: Path,
    val hostWorkRoot: Path,
) {
    fun newRuntimeDirectory(minecraftRuntimeKind: MinecraftRuntimeKind): Path =
        createUniqueDirectory(
            hostWorkRoot.resolve("runtimes").resolve(minecraftRuntimeKind.directoryName).resolve(minecraftVersion),
        )

    fun newScratchDirectory(): Path = createUniqueDirectory(
        hostWorkRoot.resolve("tmp"),
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
    fun server(minecraftTestLayout: MinecraftTestLayout): OfficialServerArtifact {
        val directory = minecraftTestLayout.officialServerRootDirectory
        val manifestFile = directory.resolve("manifest.json")
        check(directory.isDirectory() && manifestFile.isRegularFile()) {
            "The official server fixture is missing; run this test through its standard Gradle test task"
        }
        val manifest = testJson.decodeFromString<JsonObject>(manifestFile.readText())
        check(manifest.getValue("minecraft_version").jsonPrimitive.content == minecraftTestLayout.minecraftVersion) {
            "The official server fixture belongs to a different Minecraft version"
        }
        val runtimeDirectory = directory.safeResolve(
            manifest.getValue("relative_server_runtime_directory").jsonPrimitive.content,
        )
        val jar = directory.safeResolve(
            manifest.getValue("relative_server_jar").jsonPrimitive.content,
        )
        val template = directory.safeResolve(
            manifest.getValue("relative_template_directory").jsonPrimitive.content,
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
