package com.hiczp.minecraft.protocol.buildScript

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Holds the root-project download task providers so [build.gradle.kts] can
 * wire subproject test dependencies explicitly.
 */
class OfficialDownloadTaskRefs(
    val downloadServer: TaskProvider<DownloadOfficialMinecraftServerTask>,
    val downloadClient: TaskProvider<DownloadOfficialMinecraftClientTask>,
    val downloadAssets: TaskProvider<DownloadOfficialMinecraftAssetsTask>,
    val downloadHeadlessMc: TaskProvider<DownloadHeadlessMcTask>,
    val prepareHeadlessMc: TaskProvider<PrepareHeadlessMcClientTask>,
    val extractServerRuntime: TaskProvider<ExtractOfficialServerRuntimeTask>,
    val compileCodecOracle: TaskProvider<CompileOfficialCodecOracleTask>,
)

/**
 * Registers every official-Minecraft download task and analysis task on the
 * root project.  All dependency ordering is inferred by Gradle through
 * `@Input`/`@Output` + `TaskProvider.flatMap` — no explicit `dependsOn` is
 * needed on the download/preparation chain.
 */
fun Project.applyOfficialDownloadsConvention(): OfficialDownloadTaskRefs {
    val minecraftVersion = MinecraftTarget.MINECRAFT_VERSION
    val protocolRef = layout.buildDirectory.dir("protocol-reference")
    val versionManifestFile = protocolRef.map { it.file("version_manifest_v2.json") }
    val versionRoot = protocolRef.map { it.dir(minecraftVersion) }
    val versionMetadataFile = versionRoot.map { it.file("version.json") }
    val serverDir = versionRoot.map { it.dir("mojang-server") }
    val serverJarFile = serverDir.map { it.file("server.jar") }
    val serverMetadataF = serverDir.map { it.file("download-metadata.json") }
    val clientDir = versionRoot.map { it.dir("mojang-client") }
    val clientAssetsObj = clientDir.map { it.dir("assets/objects") }
    val headlessMcJarFile = versionRoot.map { it.dir("headlessmc") }
        .map { it.file("headlessmc-launcher.jar") }
    val analysisRoot = layout.buildDirectory.dir(
        "generated/official-minecraft/$minecraftVersion",
    )
    val targetFile = analysisRoot.map { it.file("target/target.json") }
    val reportsDir = analysisRoot.map { it.dir("data-generator-reports") }
    val configFile = analysisRoot.map { it.file("configuration/configuration.json") }

    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    val java25 = toolchains.launcherFor { spec ->
        spec.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.register("minecraftVersion", PrintMinecraftVersionTask::class.java) { task ->
        task.group = "help"
        task.description = "Print the official Minecraft release."
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
    }

    // ── layer 0: manifest ────────────────────────────────────────
    val downloadManifest = tasks.register(
        "downloadVersionManifest", DownloadVersionManifestTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the Mojang version manifest."
        task.manifestUrl.set(VERSION_MANIFEST_URL)
        task.outputFile.set(versionManifestFile)
    }

    // ── layer 1: version metadata (reads manifest) ──────────────
    val downloadMetadata = tasks.register(
        "downloadVersionMetadata", DownloadVersionMetadataTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download version metadata."
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.manifestFile.set(downloadManifest.flatMap { it.outputFile })
        task.outputFile.set(versionMetadataFile)
    }
    val metadataOut = downloadMetadata.flatMap { it.outputFile }

    // ── layer 2a: server (reads metadata) ───────────────────────
    val downloadServer = tasks.register(
        "downloadOfficialMinecraftServer",
        DownloadOfficialMinecraftServerTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the official server JAR."
        task.offline.set(gradle.startParameter.isOffline)
        task.versionMetadata.set(metadataOut)
        task.serverJar.set(serverJarFile)
        task.metadataFile.set(serverMetadataF)
    }

    // ── layer 2b: client (reads metadata) ────────────────────────
    val downloadClient = tasks.register(
        "downloadOfficialMinecraftClient",
        DownloadOfficialMinecraftClientTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download client JAR, libraries, and asset index."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.metadataFile.set(metadataOut)
        task.outputDirectory.set(clientDir)
    }

    // ── layer 2c: headlessmc (standalone, no metadata needed) ────
    val downloadHeadlessMc = tasks.register(
        "downloadHeadlessMc", DownloadHeadlessMcTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download the HeadlessMC launcher."
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.outputFile.set(headlessMcJarFile)
    }

    // ── layer 3a: server runtime (reads server jar) ─────────────
    val extractRuntime = tasks.register(
        "extractOfficialServerRuntime",
        ExtractOfficialServerRuntimeTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description =
            "Extract the server implementation JAR and libraries."
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.outputDirectory.set(serverDir.map { it.dir("runtime") })
    }

    // ── layer 4: codec oracle (reads runtime) ────────────────────
    // Source published by :minecraft-test-support via consumable cfg.
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
                "path" to ":minecraft-test-support",
                "configuration" to "codecOracleSourceElements",
            ),
        ),
    )
    val codecSourceFile = layout.file(codecOracleSourceCfg.elements.map {
        it.single().asFile
    })
    val codecOracleDir = versionRoot.map { it.dir("codec-oracle") }
    val compileCodecOracle = tasks.register(
        "compileOfficialCodecOracle",
        CompileOfficialCodecOracleTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Compile the official codec bridge."
        task.sourceFile.set(codecSourceFile)
        task.runtimeDirectory.set(extractRuntime.flatMap { it.outputDirectory })
        task.outputDirectory.set(codecOracleDir.map { it.dir("classes") })
    }

    // ── layer 3b: assets (reads client asset index) ─────────────
    val downloadAssets = tasks.register(
        "downloadOfficialMinecraftAssets",
        DownloadOfficialMinecraftAssetsTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description = "Download all official asset objects."
        task.assetIndexesDir.set(downloadClient.flatMap {
            it.outputDirectory.dir("assets/indexes")
        })
        task.outputDirectory.set(clientAssetsObj)
    }

    // ── layer 3c: headlessmc layout (reads client jar + metadata)
    val prepareHeadlessMc = tasks.register(
        "prepareHeadlessMcClient",
        PrepareHeadlessMcClientTask::class.java,
    ) { task ->
        task.group = "official minecraft"
        task.description =
            "Prepare the HeadlessMC versions/ directory layout."
        task.minecraftVersion.set(MinecraftTarget.MINECRAFT_VERSION)
        task.clientJar.set(downloadClient.flatMap {
            it.outputDirectory.file("client.jar")
        })
        task.versionMetadata.set(metadataOut)
        task.outputDirectory.set(clientDir.map { it.dir("versions") })
    }

    // ── analysis (reads server artifacts) ────────────────────────
    val analyzeTarget = tasks.register(
        "analyzeOfficialMinecraftTarget",
        AnalyzeOfficialMinecraftTargetTask::class.java,
    ) { task ->
        task.group = "official minecraft analysis"
        task.description = "Analyze version and protocol facts."
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.downloadMetadata.set(downloadServer.flatMap { it.metadataFile })
        task.outputFile.set(targetFile)
    }

    val analyzeReports = tasks.register(
        "analyzeOfficialMinecraftReports",
        AnalyzeOfficialMinecraftReportsTask::class.java,
    ) { task ->
        task.group = "official minecraft analysis"
        task.description = "Capture packets, registries, and blocks reports."
        task.javaExecutable.set(java25.map { it.executablePath.asFile.absolutePath })
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.downloadMetadata.set(downloadServer.flatMap { it.metadataFile })
        task.outputDirectory.set(reportsDir)
    }

    val analyzeConfig = tasks.register(
        "analyzeOfficialMinecraftConfiguration",
        AnalyzeOfficialMinecraftConfigurationTask::class.java,
    ) { task ->
        task.group = "official minecraft analysis"
        task.description = "Capture Configuration Known Packs branches."
        task.javaExecutable.set(java25.map { it.executablePath.asFile.absolutePath })
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
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

    return OfficialDownloadTaskRefs(
        downloadServer = downloadServer,
        downloadClient = downloadClient,
        downloadAssets = downloadAssets,
        downloadHeadlessMc = downloadHeadlessMc,
        prepareHeadlessMc = prepareHeadlessMc,
        extractServerRuntime = extractRuntime,
        compileCodecOracle = compileCodecOracle,
    )
}

private const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
