package com.hiczp.minecraft.test

import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class OfficialClientInstallation(
    internal val directory: Path,
    val version: String,
    val requiredJavaMajor: Int,
    val clientSha1: String,
)

internal object OfficialClientPreparation {
    private val sha1Pattern = Regex("[0-9a-f]{40}")

    fun prepare(layout: MinecraftTestLayout): OfficialClientInstallation =
        verifyClient(
            version = layout.minecraftVersion,
            clientRoot = OfficialArtifacts.clientRoot(layout),
        )

    private fun verifyClient(
        version: String,
        clientRoot: Path,
    ): OfficialClientInstallation {
        check(clientRoot.isDirectory()) {
            "The verified official client fixture is missing: $clientRoot. Run this test through its standard Gradle test task."
        }

        val versionRoot = requireNotNull(clientRoot.parent)
        val metadataPath = Path(versionRoot, "version.json")
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
        val expectedClientSha1 = validateSha1(
            clientSpec.requiredString("sha1"),
            "client download",
        )
        check(clientJar.isRegularFile()) {
            "Official client JAR is missing: $clientJar"
        }
        check(clientJar.sha1() == expectedClientSha1) {
            "Official client JAR failed SHA-1 verification"
        }

        collectLibraryArtifacts(metadata).forEach { relative ->
            val library = Path(clientRoot, "libraries").safeResolve(relative)
            check(library.isRegularFile()) {
                "Official client library is missing: $library"
            }
        }

        val assetIndexSpec = metadata.requiredObject("assetIndex")
        val assetIndexId = assetIndexSpec.requiredString("id")
        val expectedAssetIndexSha1 = validateSha1(
            assetIndexSpec.requiredString("sha1"),
            "asset index",
        )
        val assetIndexPath = Path(clientRoot, "assets", "indexes")
            .safeResolve("$assetIndexId.json")
        check(assetIndexPath.isRegularFile()) {
            "Official asset index is missing: $assetIndexPath"
        }
        check(assetIndexPath.sha1() == expectedAssetIndexSha1) {
            "Official asset index failed SHA-1 verification"
        }

        val versionDirectory = Path(clientRoot, "versions", version)
        check(Path(versionDirectory, "$version.jar").isRegularFile()) {
            "Prepared HeadlessMC client JAR is missing from $versionDirectory"
        }
        check(Path(versionDirectory, "$version.json").isRegularFile()) {
            "Prepared HeadlessMC metadata is missing from $versionDirectory"
        }

        val javaMajor = metadata["javaVersion"]
            ?.jsonObject
            ?.requiredInt("majorVersion")
            ?: 0
        return OfficialClientInstallation(
            directory = clientRoot,
            version = version,
            requiredJavaMajor = javaMajor,
            clientSha1 = expectedClientSha1,
        )
    }

    private fun collectLibraryArtifacts(metadata: JsonObject): Set<String> =
        buildSet {
            metadata.requiredArray("libraries").forEach { element ->
                val downloads = element.jsonObject["downloads"]
                    ?.takeUnless { it === JsonNull }
                    ?.jsonObject
                    ?: return@forEach
                downloads["artifact"]?.jsonObject?.let { artifact ->
                    add(artifact.requiredString("path"))
                }
                downloads["classifiers"]?.jsonObject?.values
                    ?.forEach { classifier ->
                        add(classifier.jsonObject.requiredString("path"))
                    }
            }
        }

    private fun validateSha1(value: String, context: String): String =
        value.lowercase().also { sha1 ->
            require(sha1.matches(sha1Pattern)) {
                "$context has an invalid SHA-1"
            }
        }
}
