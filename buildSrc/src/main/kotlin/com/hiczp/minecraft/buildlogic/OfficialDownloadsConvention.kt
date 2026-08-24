package com.hiczp.minecraft.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JvmToolchainsPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.nio.charset.StandardCharsets

/** Lazy immutable inputs consumed by standard external-peer test tasks. */
class MinecraftTestFixtureOutputs(
    val officialServer: FileCollection,
    val headlessClient: FileCollection,
    val codecOracle: FileCollection,
    val officialServerRootDirectory: Provider<Directory>,
    val headlessClientRootDirectory: Provider<Directory>,
    val serverRuntimeDirectory: Provider<Directory>,
    val codecClassesDirectory: Provider<Directory>,
)

/** Registers exact-version fixture artifacts and official-data producers. */
fun Project.applyMinecraftFixtureArtifactsConvention(): MinecraftTestFixtureOutputs {
    pluginManager.apply(JvmToolchainsPlugin::class.java)
    val minecraftVersion = MinecraftTarget.MINECRAFT_VERSION
    val protocolRef = layout.buildDirectory.dir("protocol-reference")
    val versionManifestFile = protocolRef.map {
        it.file("version_manifest_v2.json")
    }
    val versionRoot = protocolRef.map { it.dir(minecraftVersion) }
    val versionMetadataFile = versionRoot.map { it.file("version.json") }

    val downloadsRoot = versionRoot.map { it.dir("downloads") }
    val serverDownloadDirectory = downloadsRoot.map { it.dir("server") }
    val serverJarFile = serverDownloadDirectory.map { it.file("server.jar") }
    val serverMetadataFile = serverDownloadDirectory.map {
        it.file("download-metadata.json")
    }
    val clientDownloadDirectory = downloadsRoot.map { it.dir("client") }
    val clientJarFile = clientDownloadDirectory.map { it.file("client.jar") }
    val clientMetadataFile = clientDownloadDirectory.map {
        it.file("download-metadata.json")
    }
    val clientAssetIndexes = clientDownloadDirectory.map {
        it.dir("assets/indexes")
    }
    val clientOriginalAssetObjects = clientDownloadDirectory.map {
        it.dir("assets/objects")
    }
    val headlessMcReplacementDirectory = downloadsRoot.map {
        it.dir("headlessmc-replacements")
    }
    val dummyOggFile = headlessMcReplacementDirectory.map {
        it.file("dummy.ogg")
    }
    val dummyPngFile = headlessMcReplacementDirectory.map {
        it.file("dummy.png")
    }
    val dummyJsonFile = headlessMcReplacementDirectory.map {
        it.file("dummy.json")
    }
    val hmcSpecificsFile = downloadsRoot.map {
        it.file("hmc-specifics/${HmcSpecificsTarget.FABRIC_ASSET_NAME}")
    }
    val fabricProfileFile = downloadsRoot.map {
        it.file("fabric/${FabricLoaderTarget.profileId(minecraftVersion)}.json")
    }

    val officialServerRoot = versionRoot.map {
        it.dir("official-server")
    }
    val headlessClientRoot = versionRoot.map {
        it.dir("headless-client")
    }
    val headlessRuntimeDirectory = headlessClientRoot.map {
        it.dir("runtime")
    }
    val minecraftRuntimeDirectory = headlessRuntimeDirectory.map {
        it.dir("minecraft")
    }
    val minecraftLibrariesDirectory = minecraftRuntimeDirectory.map {
        it.dir("libraries")
    }
    val minecraftAssetsDirectory = minecraftRuntimeDirectory.map {
        it.dir("assets")
    }
    val minecraftVersionsDirectory = minecraftRuntimeDirectory.map {
        it.dir("versions")
    }
    val headlessLauncherFile = headlessRuntimeDirectory.map {
        it.file("headlessmc/headlessmc-launcher.jar")
    }
    val headlessModsDirectory = headlessRuntimeDirectory.map {
        it.dir("mods")
    }
    val headlessTemplateDirectory = headlessClientRoot.map {
        it.dir("template")
    }
    val headlessManifestFile = headlessClientRoot.map {
        it.file("manifest.json")
    }

    val serverRuntimeDirectory = serverDownloadDirectory.map {
        it.dir("runtime")
    }
    val codecOracleDirectory = versionRoot.map { it.dir("codec-oracle") }
    val codecClassesDirectory = codecOracleDirectory.map {
        it.dir("classes")
    }
    val analysisRoot = layout.buildDirectory.dir(
        "generated/official-minecraft/$minecraftVersion",
    )
    val targetFile = analysisRoot.map { it.file("target/target.json") }
    val reportsDirectory = analysisRoot.map {
        it.dir("data-generator-reports")
    }
    val configurationFile = analysisRoot.map {
        it.file("configuration/configuration.json")
    }
    val dataPacksDirectory = analysisRoot.map {
        it.dir("datapacks")
    }

    tasks.register("minecraftVersion", PrintMinecraftVersionTask::class.java) { task ->
        task.group = "help"
        task.description = "Print the official Minecraft release."
        task.minecraftVersion.set(minecraftVersion)
    }

    val downloadManifest = tasks.register(
        "downloadVersionManifest",
        DownloadVersionManifestTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the Mojang version manifest."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(minecraftVersion)
        task.manifestUrl.set(VERSION_MANIFEST_URL)
        task.outputFile.set(versionManifestFile)
    }
    val downloadMetadata = tasks.register(
        "downloadVersionMetadata",
        DownloadVersionMetadataTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the selected Minecraft version metadata."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(minecraftVersion)
        task.manifestFile.set(downloadManifest.flatMap { it.outputFile })
        task.outputFile.set(versionMetadataFile)
    }
    val metadataOutput = downloadMetadata.flatMap { it.outputFile }

    val downloadServer = tasks.register(
        "downloadOfficialMinecraftServer",
        DownloadOfficialMinecraftServerTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the selected official server JAR."
        task.offline.set(gradle.startParameter.isOffline)
        task.versionMetadata.set(metadataOutput)
        task.serverJar.set(serverJarFile)
        task.metadataFile.set(serverMetadataFile)
    }

    val templateWorkerRuntime = configurations.create(
        "minecraftFixtureTemplateWorkerRuntime",
    ) {
        it.isCanBeConsumed = false
        it.isCanBeResolved = true
        it.isTransitive = true
        it.description = "Runtime classpath for immutable Minecraft template generation"
    }
    dependencies.add(
        templateWorkerRuntime.name,
        dependencies.project(
            mapOf(
                "path" to MINECRAFT_TEST_FIXTURE_HOST_PROJECT,
                "configuration" to "runtimeElements",
            ),
        ),
    )

    val generateServerTemplate = tasks.register(
        "generateOfficialMinecraftServerTemplate",
        GenerateOfficialMinecraftServerTemplateTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Start, stop, sanitize, and publish the official server template."
        task.minecraftVersion.set(minecraftVersion)
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.workerClasspath.from(templateWorkerRuntime)
        task.outputDirectory.set(officialServerRoot)
    }
    val prepareServer = tasks.register("prepareOfficialMinecraftServer") { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Prepare the immutable official server fixture."
        task.dependsOn(generateServerTemplate)
    }

    val downloadClient = tasks.register(
        "downloadMinecraftClientJar",
        DownloadMinecraftClientJarTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the selected Mojang client JAR."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(minecraftVersion)
        task.metadataFile.set(metadataOutput)
        task.clientJar.set(clientJarFile)
        task.downloadMetadataFile.set(clientMetadataFile)
    }
    val downloadFabricProfile = tasks.register(
        "downloadFabricLoaderProfile",
        DownloadFabricLoaderProfileTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the exact Fabric Loader client profile."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(minecraftVersion)
        task.fabricLoaderVersion.set(
            FabricLoaderTarget.FABRIC_LOADER_VERSION,
        )
        task.profileUrl.set(FabricLoaderTarget.profileUrl(minecraftVersion))
        task.outputFile.set(fabricProfileFile)
    }
    val downloadLibraries = tasks.register(
        "downloadMinecraftClientLibraries",
        DownloadMinecraftClientLibrariesTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download exact Mojang and Fabric client libraries."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(minecraftVersion)
        task.metadataFile.set(metadataOutput)
        task.fabricProfileFile.set(
            downloadFabricProfile.flatMap { it.outputFile },
        )
        task.librariesDirectory.set(minecraftLibrariesDirectory)
    }
    val downloadAssetIndex = tasks.register(
        "downloadMinecraftClientAssetIndex",
        DownloadMinecraftClientAssetIndexTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the selected client asset index."
        task.offline.set(gradle.startParameter.isOffline)
        task.minecraftVersion.set(minecraftVersion)
        task.metadataFile.set(metadataOutput)
        task.assetIndexesDirectory.set(clientAssetIndexes)
    }
    val downloadAssetObjects = tasks.register(
        "downloadMinecraftClientAssetObjects",
        DownloadMinecraftClientAssetObjectsTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download original client objects not replaced by HeadlessMC."
        task.offline.set(gradle.startParameter.isOffline)
        task.assetIndexesDirectory.set(
            downloadAssetIndex.flatMap { it.assetIndexesDirectory },
        )
        task.outputDirectory.set(clientOriginalAssetObjects)
    }
    val downloadHeadlessLauncher = tasks.register(
        "downloadHeadlessMcLauncher",
        DownloadHeadlessMcLauncherTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the exact HeadlessMC launcher wrapper."
        task.offline.set(gradle.startParameter.isOffline)
        task.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        task.launcherFile.set(headlessLauncherFile)
    }
    val downloadAssetReplacements = tasks.register(
        "downloadHeadlessMcAssetReplacements",
        DownloadHeadlessMcAssetReplacementsTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download HeadlessMC binary asset replacements."
        task.offline.set(gradle.startParameter.isOffline)
        task.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        task.dummyOggFile.set(dummyOggFile)
        task.dummyPngFile.set(dummyPngFile)
    }
    val generateJsonReplacement = tasks.register(
        "generateHeadlessMcJsonReplacement",
        GenerateHeadlessMcJsonReplacementTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Generate the deterministic HeadlessMC JSON replacement."
        task.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        task.outputFile.set(dummyJsonFile)
    }
    val downloadHmcSpecifics = tasks.register(
        "downloadHmcSpecifics",
        DownloadHmcSpecificsTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Download the selected HMC-Specifics Fabric asset."
        task.offline.set(gradle.startParameter.isOffline)
        task.releaseTag.set(HmcSpecificsTarget.RELEASE_TAG)
        task.assetName.set(HmcSpecificsTarget.FABRIC_ASSET_NAME)
        task.assetUrl.set(HmcSpecificsTarget.FABRIC_ASSET_URL)
        task.minecraftVersion.set(minecraftVersion)
        task.outputFile.set(hmcSpecificsFile)
    }

    val assembleAssets = tasks.register(
        "assembleHeadlessClientAssets",
        AssembleHeadlessClientAssetsTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Assemble the filtered HeadlessMC asset store."
        task.assetIndexesDirectory.set(
            downloadAssetIndex.flatMap { it.assetIndexesDirectory },
        )
        task.originalObjectsDirectory.set(
            downloadAssetObjects.flatMap { it.outputDirectory },
        )
        task.dummyOggFile.set(
            downloadAssetReplacements.flatMap { it.dummyOggFile },
        )
        task.dummyPngFile.set(
            downloadAssetReplacements.flatMap { it.dummyPngFile },
        )
        task.dummyJsonFile.set(
            generateJsonReplacement.flatMap { it.outputFile },
        )
        task.outputDirectory.set(minecraftAssetsDirectory)
    }
    val assembleVersionLayout = tasks.register(
        "assembleHeadlessClientVersionLayout",
        Sync::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Assemble exact Mojang and Fabric version profiles."
        task.from(downloadClient.flatMap { it.clientJar }) { copy ->
            copy.into(minecraftVersion)
            copy.rename { "$minecraftVersion.jar" }
        }
        task.from(metadataOutput) { copy ->
            copy.into(minecraftVersion)
            copy.rename { "$minecraftVersion.json" }
        }
        val fabricProfileId = FabricLoaderTarget.profileId(minecraftVersion)
        task.from(downloadClient.flatMap { it.clientJar }) { copy ->
            copy.into(fabricProfileId)
            copy.rename { "$fabricProfileId.jar" }
        }
        task.from(downloadFabricProfile.flatMap { it.outputFile }) { copy ->
            copy.into(fabricProfileId)
            copy.rename { "$fabricProfileId.json" }
        }
        task.into(minecraftVersionsDirectory)
    }
    val assembleMods = tasks.register(
        "assembleHeadlessClientMods",
        Sync::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Assemble the selected upstream client mods."
        task.from(downloadHmcSpecifics.flatMap { it.outputFile })
        task.into(headlessModsDirectory)
    }
    val generateClientTemplate = tasks.register(
        "generateHeadlessClientTemplate",
        GenerateHeadlessClientTemplateTask::class.java,
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Start, stop, sanitize, and publish the HeadlessMC client template."
        task.dependsOn(
            downloadHeadlessLauncher,
            downloadLibraries,
            assembleAssets,
            assembleVersionLayout,
            assembleMods,
        )
        task.minecraftVersion.set(minecraftVersion)
        task.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        task.fabricLoaderVersion.set(
            FabricLoaderTarget.FABRIC_LOADER_VERSION,
        )
        task.hmcSpecificsReleaseTag.set(HmcSpecificsTarget.RELEASE_TAG)
        task.hmcSpecificsAssetName.set(HmcSpecificsTarget.FABRIC_ASSET_NAME)
        task.hmcSpecificsAssetUrl.set(HmcSpecificsTarget.FABRIC_ASSET_URL)
        task.runtimeDirectory.set(headlessRuntimeDirectory)
        task.workerClasspath.from(templateWorkerRuntime)
        task.templateDirectory.set(headlessTemplateDirectory)
        task.manifestFile.set(headlessManifestFile)
    }
    val prepareHeadlessClient = tasks.register("prepareHeadlessClient") { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Prepare the immutable HeadlessMC client fixture."
        task.dependsOn(generateClientTemplate)
    }

    val extractRuntime = tasks.register(
        "extractOfficialServerRuntime",
        ExtractOfficialServerRuntimeTask::class.java,
    ) { task ->
        task.group = OFFICIAL_DATA_TASK_GROUP
        task.description = "Extract the official server implementation and libraries."
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.outputDirectory.set(serverRuntimeDirectory)
    }
    val codecOracleSource = configurations.create("codecOracleSource") {
        it.isCanBeConsumed = false
        it.isCanBeResolved = true
        it.isTransitive = false
        it.description = "Official codec oracle bridge source"
    }
    dependencies.add(
        codecOracleSource.name,
        dependencies.project(
            mapOf(
                "path" to MINECRAFT_TEST_FIXTURE_HOST_PROJECT,
                "configuration" to "codecOracleSourceElements",
            ),
        ),
    )
    val runtimeFiles = files(extractRuntime.flatMap { it.outputDirectory })
    val codecCompileClasspath = files(
        extractRuntime.flatMap { it.outputDirectory.file("server.jar") },
        runtimeFiles.asFileTree.matching { filter ->
            filter.include("libraries/**/*.jar")
        },
    )
    val javaToolchains = extensions.getByType(JavaToolchainService::class.java)
    val compileCodecOracle = tasks.register(
        "compileOfficialCodecOracle",
        JavaCompile::class.java,
    ) { task ->
        task.group = OFFICIAL_DATA_TASK_GROUP
        task.description = "Compile the official codec bridge."
        task.source(codecOracleSource)
        task.classpath = codecCompileClasspath
        task.destinationDirectory.set(codecClassesDirectory)
        task.options.encoding = StandardCharsets.UTF_8.name()
        task.options.release.set(BuildVersions.JAVA_VERSION)
        task.sourceCompatibility = BuildVersions.JAVA_VERSION.toString()
        task.targetCompatibility = BuildVersions.JAVA_VERSION.toString()
        task.javaCompiler.set(
            javaToolchains.compilerFor { spec ->
                spec.languageVersion.set(
                    JavaLanguageVersion.of(BuildVersions.JAVA_VERSION),
                )
            },
        )
    }
    val prepareCodecOracle = tasks.register(
        "prepareOfficialMinecraftCodecOracle",
    ) { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Prepare the official codec oracle fixture."
        task.dependsOn(compileCodecOracle)
    }

    val analyzeTarget = tasks.register(
        "analyzeOfficialMinecraftTarget",
        AnalyzeOfficialMinecraftTargetTask::class.java,
    ) { task ->
        task.group = OFFICIAL_DATA_TASK_GROUP
        task.description = "Analyze version and protocol facts."
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.downloadMetadata.set(downloadServer.flatMap { it.metadataFile })
        task.outputFile.set(targetFile)
    }
    val analyzeReports = tasks.register(
        "analyzeOfficialMinecraftReports",
        AnalyzeOfficialMinecraftReportsTask::class.java,
    ) { task ->
        task.group = OFFICIAL_DATA_TASK_GROUP
        task.description = "Capture official packets, registries, and blocks reports."
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.downloadMetadata.set(downloadServer.flatMap { it.metadataFile })
        task.outputDirectory.set(reportsDirectory)
    }
    val analyzeConfiguration = tasks.register(
        "analyzeOfficialMinecraftConfiguration",
        AnalyzeOfficialMinecraftConfigurationTask::class.java,
    ) { task ->
        task.group = OFFICIAL_DATA_TASK_GROUP
        task.description = "Capture official Configuration Known Packs branches."
        task.serverJar.set(downloadServer.flatMap { it.serverJar })
        task.packetsReport.set(
            analyzeReports.flatMap {
                it.outputDirectory.file("reports/packets.json")
            },
        )
        task.outputFile.set(configurationFile)
    }
    val extractDataPacks = tasks.register(
        "extractOfficialMinecraftDataPacks",
        ExtractOfficialMinecraftDataPacksTask::class.java,
    ) { task ->
        task.group = OFFICIAL_DATA_TASK_GROUP
        task.description = "Extract official core and built-in data packs."
        task.implementationJar.set(extractRuntime.flatMap { it.outputDirectory.file("server.jar") })
        task.outputDirectory.set(dataPacksDirectory)
    }
    tasks.register("prepareOfficialMinecraftData") { task ->
        task.group = OFFICIAL_DATA_TASK_GROUP
        task.description = "Prepare every official analysis and extracted data artifact."
        task.dependsOn(analyzeTarget, analyzeReports, analyzeConfiguration, extractDataPacks)
    }

    publishOfficialMinecraftArtifact(
        "officialMinecraftTarget",
        analyzeTarget.flatMap { it.outputFile },
        analyzeTarget,
    )
    publishOfficialMinecraftArtifact(
        "officialMinecraftReports",
        analyzeReports.flatMap { it.outputDirectory },
        analyzeReports,
        directory = true,
    )
    publishOfficialMinecraftArtifact(
        "officialMinecraftConfiguration",
        analyzeConfiguration.flatMap { it.outputFile },
        analyzeConfiguration,
    )
    publishOfficialMinecraftArtifact(
        "officialMinecraftDataPacks",
        extractDataPacks.flatMap { it.outputDirectory },
        extractDataPacks,
        directory = true,
    )

    val outputs = MinecraftTestFixtureOutputs(
        officialServer = files(officialServerRoot).builtBy(prepareServer),
        headlessClient = files(headlessClientRoot).builtBy(
            prepareHeadlessClient,
        ),
        codecOracle = files(
            serverJarFile,
            serverMetadataFile,
            serverRuntimeDirectory,
            codecClassesDirectory,
        ).builtBy(prepareCodecOracle),
        officialServerRootDirectory = officialServerRoot,
        headlessClientRootDirectory = headlessClientRoot,
        serverRuntimeDirectory = serverRuntimeDirectory,
        codecClassesDirectory = codecClassesDirectory,
    )
    extensions.add("minecraftTestFixtureOutputs", outputs)
    return outputs
}

private const val FIXTURE_TASK_GROUP = "minecraft fixtures"
private const val OFFICIAL_DATA_TASK_GROUP = "official minecraft data"
private const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
