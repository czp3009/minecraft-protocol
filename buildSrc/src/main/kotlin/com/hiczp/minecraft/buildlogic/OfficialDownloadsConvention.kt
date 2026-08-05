package com.hiczp.minecraft.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

/** Lazy fixture inputs consumed by standard official-peer test tasks. */
class OfficialMinecraftFixtureOutputs(
    val officialServer: FileCollection,
    val officialClient: FileCollection,
    val codecOracle: FileCollection,
    val serverCacheDirectory: Provider<Directory>,
    val clientCacheDirectory: Provider<Directory>,
    val versionMetadataFile: Provider<RegularFile>,
    val headlessLauncherFile: Provider<RegularFile>,
    val serverRuntimeDirectory: Provider<Directory>,
    val codecClassesDirectory: Provider<Directory>,
)

/**
 * Registers every official-Minecraft download task and analysis task on the
 * root project. Gradle infers the download and preparation order from task
 * inputs, outputs, and `TaskProvider.flatMap` provenance.
 */
fun Project.applyOfficialDownloadsConvention(): OfficialMinecraftFixtureOutputs {
    val minecraftVersion = MinecraftTarget.MINECRAFT_VERSION
    val protocolRef = layout.buildDirectory.dir("protocol-reference")
    val versionManifestFile = protocolRef.map { it.file("version_manifest_v2.json") }
    val versionRoot = protocolRef.map { it.dir(minecraftVersion) }
    val versionMetadataFile = versionRoot.map { it.file("version.json") }
    val serverDir = versionRoot.map { it.dir("mojang-server") }
    val serverJarFile = serverDir.map { it.file("server.jar") }
    val serverMetadataFile = serverDir.map { it.file("download-metadata.json") }
    val serverRuntimeDirectory = serverDir.map { it.dir("runtime") }
    val clientDir = versionRoot.map { it.dir("mojang-client") }
    val clientJarFile = clientDir.map { it.file("client.jar") }
    val clientLibrariesDir = clientDir.map { it.dir("libraries") }
    val clientAssetsIndexes = clientDir.map { it.dir("assets/indexes") }
    val clientAssetObjectsDirectory = clientDir.map { it.dir("assets/objects") }
    val clientMetadataFile = clientDir.map { it.file("download-metadata.json") }
    val headlessMcDirectory = versionRoot.map { it.dir("headlessmc") }
    val headlessMcJarFile = headlessMcDirectory.map {
        it.file("headlessmc-launcher.jar")
    }
    val headlessMcDummyOggFile = headlessMcDirectory.map { it.file("dummy.ogg") }
    val headlessMcDummyPngFile = headlessMcDirectory.map { it.file("dummy.png") }
    val headlessMcDummyJsonFile = headlessMcDirectory.map { it.file("dummy.json") }
    val headlessMcClientVersionDirectory = clientDir.map {
        it.dir("versions").dir(minecraftVersion)
    }
    val analysisRoot = layout.buildDirectory.dir(
        "generated/official-minecraft/$minecraftVersion",
    )
    val targetFile = analysisRoot.map { it.file("target/target.json") }
    val reportsDir = analysisRoot.map { it.dir("data-generator-reports") }
    val configFile = analysisRoot.map { it.file("configuration/configuration.json") }

    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    val projectJava = toolchains.launcherFor { spec ->
        spec.languageVersion.set(
            JavaLanguageVersion.of(BuildVersions.JAVA_VERSION),
        )
    }

    tasks.register("minecraftVersion", PrintMinecraftVersionTask::class.java) { task ->
        task.group = "help"
        task.description = "Print the official Minecraft release."
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
    }

    val downloadManifest = tasks.register(
        "downloadVersionManifest", DownloadVersionManifestTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the Mojang version manifest."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.manifestUrl.set(VERSION_MANIFEST_URL)
        task.outputFile.set(versionManifestFile)
    }

    val downloadMetadata = tasks.register(
        "downloadVersionMetadata", DownloadVersionMetadataTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download version metadata."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.manifestFile.set(downloadManifest.flatMap { it.outputFile })
        task.outputFile.set(versionMetadataFile)
    }
    val metadataOut = downloadMetadata.flatMap { it.outputFile }

    val downloadServer = tasks.register(
        "downloadOfficialMinecraftServer",
        DownloadOfficialMinecraftServerTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the official server JAR."
        task.offline.set(gradle.startParameter.isOffline)
        task.versionMetadata.set(metadataOut)
        task.serverJar.set(serverJarFile)
        task.metadataFile.set(serverMetadataFile)
    }
    val prepareServer = tasks.register("prepareOfficialMinecraftServer") { task ->
        task.group = "official minecraft"
        task.description = "Prepare the official Minecraft server fixture."
        task.dependsOn(downloadServer)
    }

    val downloadClient = tasks.register(
        "downloadOfficialMinecraftClient",
        DownloadOfficialMinecraftClientTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the official client JAR."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.metadataFile.set(metadataOut)
        task.clientJar.set(clientJarFile)
        task.downloadMetadataFile.set(clientMetadataFile)
    }
    val downloadClientLibraries = tasks.register(
        "downloadOfficialMinecraftClientLibraries",
        DownloadOfficialMinecraftClientLibrariesTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the official client libraries."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.metadataFile.set(metadataOut)
        task.librariesDirectory.set(clientLibrariesDir)
    }
    val downloadClientAssetIndex = tasks.register(
        "downloadOfficialMinecraftAssetIndex",
        DownloadOfficialMinecraftAssetIndexTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the official client asset index."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.metadataFile.set(metadataOut)
        task.assetIndexesDirectory.set(clientAssetsIndexes)
    }

    val downloadHeadlessMc = tasks.register(
        "downloadHeadlessMc", DownloadHeadlessMcTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the HeadlessMC launcher."
        task.offline.set(gradle.startParameter.isOffline)
        task.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        task.launcherFile.set(headlessMcJarFile)
    }
    val downloadHeadlessMcDummyFiles = tasks.register(
        "downloadHeadlessMcDummyFiles",
        DownloadHeadlessMcDummyFilesTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the HeadlessMC dummy files."
        task.offline.set(gradle.startParameter.isOffline)
        task.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        task.dummyOggFile.set(headlessMcDummyOggFile)
        task.dummyPngFile.set(headlessMcDummyPngFile)
        task.dummyJsonFile.set(headlessMcDummyJsonFile)
    }
    val prepareHeadlessMc = tasks.register("prepareHeadlessMc") { task ->
        task.group = "official minecraft"
        task.description = "Prepare the HeadlessMC launcher."
        task.dependsOn(downloadHeadlessMc)
    }

    val extractRuntime = tasks.register(
        "extractOfficialServerRuntime",
        ExtractOfficialServerRuntimeTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Extract the server implementation JAR and libraries."
        task.dependsOn(prepareServer)
        task.serverJar.set(serverJarFile)
        task.outputDirectory.set(serverRuntimeDirectory)
    }

    val codecOracleSourceCfg = configurations.create("codecOracleSource") {
        it.isCanBeConsumed = false
        it.isCanBeResolved = true
        it.isTransitive = false
        it.description = "Official codec oracle bridge source"
    }
    dependencies.add(
        "codecOracleSource",
        dependencies.project(
            mapOf(
                "path" to ":minecraft-test-fixture-host",
                "configuration" to "codecOracleSourceElements",
            ),
        ),
    )
    val codecSourceFile = layout.file(codecOracleSourceCfg.elements.map { it.single().asFile })
    val codecOracleDir = versionRoot.map { it.dir("codec-oracle") }
    val codecClassesDirectory = codecOracleDir.map { it.dir("classes") }
    val compileCodecOracle = tasks.register(
        "compileOfficialCodecOracle",
        CompileOfficialCodecOracleTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Compile the official codec bridge."
        task.sourceFile.set(codecSourceFile)
        task.runtimeDirectory.set(extractRuntime.flatMap { it.outputDirectory })
        task.outputDirectory.set(codecClassesDirectory)
    }
    val prepareCodecOracle = tasks.register(
        "prepareOfficialMinecraftCodecOracle",
    ) { task ->
        task.group = "official minecraft"
        task.description = "Prepare the official Minecraft codec oracle fixture."
        task.dependsOn(compileCodecOracle)
    }

    val downloadAssets = tasks.register(
        "downloadOfficialMinecraftAssets",
        DownloadOfficialMinecraftAssetsTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the official client assets required beside HeadlessMC dummy files."
        task.offline.set(gradle.startParameter.isOffline)
        task.assetIndexesDirectory.set(downloadClientAssetIndex.flatMap {
            it.assetIndexesDirectory
        })
        task.dummyOggFile.set(downloadHeadlessMcDummyFiles.flatMap {
            it.dummyOggFile
        })
        task.dummyPngFile.set(downloadHeadlessMcDummyFiles.flatMap {
            it.dummyPngFile
        })
        task.dummyJsonFile.set(downloadHeadlessMcDummyFiles.flatMap {
            it.dummyJsonFile
        })
        task.outputDirectory.set(clientAssetObjectsDirectory)
    }
    val createHeadlessMcClientLayout = tasks.register(
        "createHeadlessMcClientLayout",
        Sync::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Create the client version layout required by HeadlessMC."
        task.from(downloadClient.flatMap { it.clientJar }) { copy ->
            copy.rename { "$minecraftVersion.jar" }
        }
        task.from(metadataOut) { copy ->
            copy.rename { "$minecraftVersion.json" }
        }
        task.into(headlessMcClientVersionDirectory)
    }
    val prepareClient = tasks.register("prepareOfficialMinecraftClient") { task ->
        task.group = "official minecraft"
        task.description = "Prepare the official Minecraft client fixture."
        task.dependsOn(
            downloadClient,
            downloadClientLibraries,
            downloadClientAssetIndex,
            downloadAssets,
            createHeadlessMcClientLayout,
        )
    }

    val analyzeTarget = tasks.register(
        "analyzeOfficialMinecraftTarget",
        AnalyzeOfficialMinecraftTargetTask::class.java,
    ) { task ->
        task.group = "official minecraft analysis"
        task.description = "Analyze version and protocol facts."
        task.dependsOn(prepareServer)
        task.serverJar.set(serverJarFile)
        task.downloadMetadata.set(serverMetadataFile)
        task.outputFile.set(targetFile)
    }

    val analyzeReports = tasks.register(
        "analyzeOfficialMinecraftReports",
        AnalyzeOfficialMinecraftReportsTask::class.java,
    ) { task ->
        task.group = "official minecraft analysis"
        task.description = "Capture packets, registries, and blocks reports."
        task.dependsOn(prepareServer)
        task.javaExecutable.set(projectJava.map { it.executablePath.asFile.absolutePath })
        task.serverJar.set(serverJarFile)
        task.downloadMetadata.set(serverMetadataFile)
        task.outputDirectory.set(reportsDir)
    }

    val analyzeConfig = tasks.register(
        "analyzeOfficialMinecraftConfiguration",
        AnalyzeOfficialMinecraftConfigurationTask::class.java,
    ) { task ->
        task.group = "official minecraft analysis"
        task.description = "Capture Configuration Known Packs branches."
        task.dependsOn(prepareServer)
        task.javaExecutable.set(projectJava.map { it.executablePath.asFile.absolutePath })
        task.serverJar.set(serverJarFile)
        task.packetsReport.set(analyzeReports.flatMap {
            it.outputDirectory.file("reports/packets.json")
        })
        task.outputFile.set(configFile)
    }

    tasks.register("officialMinecraftAnalysis") { task ->
        task.group = "official minecraft analysis"
        task.description = "Run every official Minecraft analysis task."
        task.dependsOn(analyzeTarget, analyzeReports, analyzeConfig)
    }

    publishOfficialMinecraftAnalysis(
        "officialMinecraftTarget",
        analyzeTarget.flatMap { it.outputFile },
        analyzeTarget,
    )
    publishOfficialMinecraftAnalysis(
        "officialMinecraftReports",
        analyzeReports.flatMap { it.outputDirectory },
        analyzeReports,
        directory = true,
    )
    publishOfficialMinecraftAnalysis(
        "officialMinecraftConfiguration",
        analyzeConfig.flatMap { it.outputFile },
        analyzeConfig,
    )

    val fixtureOutputs = OfficialMinecraftFixtureOutputs(
        officialServer = files(
            serverJarFile,
            serverMetadataFile,
        ).builtBy(prepareServer),
        officialClient = files(
            versionMetadataFile,
            clientJarFile,
            clientLibrariesDir,
            clientAssetsIndexes,
            clientMetadataFile,
            headlessMcJarFile,
            headlessMcClientVersionDirectory,
            clientAssetObjectsDirectory,
        ).builtBy(prepareClient, prepareHeadlessMc),
        codecOracle = files(
            serverJarFile,
            serverMetadataFile,
            serverRuntimeDirectory,
            codecClassesDirectory,
        ).builtBy(prepareCodecOracle),
        serverCacheDirectory = serverDir,
        clientCacheDirectory = clientDir,
        versionMetadataFile = versionMetadataFile,
        headlessLauncherFile = headlessMcJarFile,
        serverRuntimeDirectory = serverRuntimeDirectory,
        codecClassesDirectory = codecClassesDirectory,
    )
    extensions.add("officialMinecraftFixtureOutputs", fixtureOutputs)
    return fixtureOutputs
}

private const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
