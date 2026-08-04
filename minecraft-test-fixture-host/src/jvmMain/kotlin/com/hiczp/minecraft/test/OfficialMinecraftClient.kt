package com.hiczp.minecraft.test

import kotlinx.io.files.Path

internal class OfficialClientInstallation(
    internal val directory: Path,
    val version: String,
    val clientSha1: String,
)

internal object OfficialClientPreparation {
    fun prepare(layout: MinecraftTestLayout): OfficialClientInstallation =
        loadClient(
            version = layout.minecraftVersion,
            clientRoot = layout.clientCacheDirectory,
            metadataPath = layout.versionMetadataFile,
        )

    private fun loadClient(
        version: String,
        clientRoot: Path,
        metadataPath: Path,
    ): OfficialClientInstallation {
        check(clientRoot.isDirectory()) {
            "The verified official client fixture is missing: $clientRoot. Run this test through its standard Gradle test task."
        }

        check(metadataPath.isRegularFile()) {
            "Official version metadata is missing: $metadataPath"
        }
        val metadata = metadataPath.readJsonObject()
        check(metadata.requiredString("id") == version) {
            "Official version metadata identifies a different release"
        }

        val clientJar = Path(clientRoot, "client.jar")
        val clientSpec = metadata.requiredObject("downloads")
            .requiredObject("client")
        check(clientJar.isRegularFile()) {
            "Official client JAR is missing: $clientJar"
        }

        val versionDirectory = Path(clientRoot, "versions", version)
        check(Path(versionDirectory, "$version.jar").isRegularFile()) {
            "Prepared HeadlessMC client JAR is missing from $versionDirectory"
        }
        check(Path(versionDirectory, "$version.json").isRegularFile()) {
            "Prepared HeadlessMC metadata is missing from $versionDirectory"
        }

        return OfficialClientInstallation(
            directory = clientRoot,
            version = version,
            clientSha1 = clientSpec.requiredString("sha1"),
        )
    }
}
