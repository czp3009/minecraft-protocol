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
data class MinecraftTestFixtureOutputs(
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
    val clientDownloadDirectory = downloadsRoot.map { it.dir("client") }
    val clientJarFile = clientDownloadDirectory.map { it.file("client.jar") }
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

    tasks.register("minecraftVersion", PrintMinecraftVersionTask::class.java) { printMinecraftVersionTask ->
        printMinecraftVersionTask.group = "help"
        printMinecraftVersionTask.description = "Print the official Minecraft release."
        printMinecraftVersionTask.minecraftVersion.set(minecraftVersion)
    }

    val downloadManifest = tasks.register(
        "downloadVersionManifest",
        DownloadVersionManifestTask::class.java,
    ) { downloadVersionManifestTask ->
        downloadVersionManifestTask.group = FIXTURE_TASK_GROUP
        downloadVersionManifestTask.description = "Download the Mojang version manifest."
        downloadVersionManifestTask.offline.set(gradle.startParameter.isOffline)
        downloadVersionManifestTask.manifestUrl.set(VERSION_MANIFEST_URL)
        downloadVersionManifestTask.outputFile.set(versionManifestFile)
    }
    val downloadMetadata = tasks.register(
        "downloadVersionMetadata",
        DownloadVersionMetadataTask::class.java,
    ) { downloadVersionMetadataTask ->
        downloadVersionMetadataTask.group = FIXTURE_TASK_GROUP
        downloadVersionMetadataTask.description = "Download the selected Minecraft version metadata."
        downloadVersionMetadataTask.offline.set(gradle.startParameter.isOffline)
        downloadVersionMetadataTask.minecraftVersion.set(minecraftVersion)
        downloadVersionMetadataTask.manifestFile.set(downloadManifest.flatMap { it.outputFile })
        downloadVersionMetadataTask.outputFile.set(versionMetadataFile)
    }
    val metadataOutput = downloadMetadata.flatMap { it.outputFile }

    val downloadServer = tasks.register(
        "downloadOfficialMinecraftServer",
        DownloadOfficialMinecraftServerTask::class.java,
    ) { downloadOfficialMinecraftServerTask ->
        downloadOfficialMinecraftServerTask.group = FIXTURE_TASK_GROUP
        downloadOfficialMinecraftServerTask.description = "Download the selected official server JAR."
        downloadOfficialMinecraftServerTask.offline.set(gradle.startParameter.isOffline)
        downloadOfficialMinecraftServerTask.versionMetadata.set(metadataOutput)
        downloadOfficialMinecraftServerTask.serverJar.set(serverJarFile)
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
    ) { generateOfficialMinecraftServerTemplateTask ->
        generateOfficialMinecraftServerTemplateTask.group = FIXTURE_TASK_GROUP
        generateOfficialMinecraftServerTemplateTask.description =
            "Start, stop, sanitize, and publish the official server template."
        generateOfficialMinecraftServerTemplateTask.serverJar.set(downloadServer.flatMap { it.serverJar })
        generateOfficialMinecraftServerTemplateTask.workerClasspath.from(templateWorkerRuntime)
        generateOfficialMinecraftServerTemplateTask.outputDirectory.set(officialServerRoot)
    }
    val prepareServer = tasks.register("prepareOfficialMinecraftServer") { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Prepare the immutable official server fixture."
        task.dependsOn(generateServerTemplate)
    }

    val downloadClient = tasks.register(
        "downloadMinecraftClientJar",
        DownloadMinecraftClientJarTask::class.java,
    ) { downloadMinecraftClientJarTask ->
        downloadMinecraftClientJarTask.group = FIXTURE_TASK_GROUP
        downloadMinecraftClientJarTask.description = "Download the selected Mojang client JAR."
        downloadMinecraftClientJarTask.offline.set(gradle.startParameter.isOffline)
        downloadMinecraftClientJarTask.metadataFile.set(metadataOutput)
        downloadMinecraftClientJarTask.clientJar.set(clientJarFile)
    }
    val downloadFabricProfile = tasks.register(
        "downloadFabricLoaderProfile",
        DownloadFabricLoaderProfileTask::class.java,
    ) { downloadFabricLoaderProfileTask ->
        downloadFabricLoaderProfileTask.group = FIXTURE_TASK_GROUP
        downloadFabricLoaderProfileTask.description = "Download the exact Fabric Loader client profile."
        downloadFabricLoaderProfileTask.offline.set(gradle.startParameter.isOffline)
        downloadFabricLoaderProfileTask.profileUrl.set(FabricLoaderTarget.profileUrl(minecraftVersion))
        downloadFabricLoaderProfileTask.outputFile.set(fabricProfileFile)
    }
    val downloadLibraries = tasks.register(
        "downloadMinecraftClientLibraries",
        DownloadMinecraftClientLibrariesTask::class.java,
    ) { downloadMinecraftClientLibrariesTask ->
        downloadMinecraftClientLibrariesTask.group = FIXTURE_TASK_GROUP
        downloadMinecraftClientLibrariesTask.description = "Download exact Mojang and Fabric client libraries."
        downloadMinecraftClientLibrariesTask.offline.set(gradle.startParameter.isOffline)
        downloadMinecraftClientLibrariesTask.metadataFile.set(metadataOutput)
        downloadMinecraftClientLibrariesTask.fabricProfileFile.set(
            downloadFabricProfile.flatMap { it.outputFile },
        )
        downloadMinecraftClientLibrariesTask.librariesDirectory.set(minecraftLibrariesDirectory)
    }
    val downloadAssetIndex = tasks.register(
        "downloadMinecraftClientAssetIndex",
        DownloadMinecraftClientAssetIndexTask::class.java,
    ) { downloadMinecraftClientAssetIndexTask ->
        downloadMinecraftClientAssetIndexTask.group = FIXTURE_TASK_GROUP
        downloadMinecraftClientAssetIndexTask.description = "Download the selected client asset index."
        downloadMinecraftClientAssetIndexTask.offline.set(gradle.startParameter.isOffline)
        downloadMinecraftClientAssetIndexTask.metadataFile.set(metadataOutput)
        downloadMinecraftClientAssetIndexTask.assetIndexesDirectory.set(clientAssetIndexes)
    }
    val downloadAssetObjects = tasks.register(
        "downloadMinecraftClientAssetObjects",
        DownloadMinecraftClientAssetObjectsTask::class.java,
    ) { downloadMinecraftClientAssetObjectsTask ->
        downloadMinecraftClientAssetObjectsTask.group = FIXTURE_TASK_GROUP
        downloadMinecraftClientAssetObjectsTask.description =
            "Download original client objects not replaced by HeadlessMC."
        downloadMinecraftClientAssetObjectsTask.offline.set(gradle.startParameter.isOffline)
        downloadMinecraftClientAssetObjectsTask.assetIndexesDirectory.set(
            downloadAssetIndex.flatMap { it.assetIndexesDirectory },
        )
        downloadMinecraftClientAssetObjectsTask.outputDirectory.set(clientOriginalAssetObjects)
    }
    val downloadHeadlessLauncher = tasks.register(
        "downloadHeadlessMcLauncher",
        DownloadHeadlessMcLauncherTask::class.java,
    ) { downloadHeadlessMcLauncherTask ->
        downloadHeadlessMcLauncherTask.group = FIXTURE_TASK_GROUP
        downloadHeadlessMcLauncherTask.description = "Download the exact HeadlessMC launcher wrapper."
        downloadHeadlessMcLauncherTask.offline.set(gradle.startParameter.isOffline)
        downloadHeadlessMcLauncherTask.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        downloadHeadlessMcLauncherTask.launcherFile.set(headlessLauncherFile)
    }
    val downloadAssetReplacements = tasks.register(
        "downloadHeadlessMcAssetReplacements",
        DownloadHeadlessMcAssetReplacementsTask::class.java,
    ) { downloadHeadlessMcAssetReplacementsTask ->
        downloadHeadlessMcAssetReplacementsTask.group = FIXTURE_TASK_GROUP
        downloadHeadlessMcAssetReplacementsTask.description = "Download HeadlessMC binary asset replacements."
        downloadHeadlessMcAssetReplacementsTask.offline.set(gradle.startParameter.isOffline)
        downloadHeadlessMcAssetReplacementsTask.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        downloadHeadlessMcAssetReplacementsTask.dummyOggFile.set(dummyOggFile)
        downloadHeadlessMcAssetReplacementsTask.dummyPngFile.set(dummyPngFile)
    }
    val generateJsonReplacement = tasks.register(
        "generateHeadlessMcJsonReplacement",
        GenerateHeadlessMcJsonReplacementTask::class.java,
    ) { generateHeadlessMcJsonReplacementTask ->
        generateHeadlessMcJsonReplacementTask.group = FIXTURE_TASK_GROUP
        generateHeadlessMcJsonReplacementTask.description = "Generate the deterministic HeadlessMC JSON replacement."
        generateHeadlessMcJsonReplacementTask.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        generateHeadlessMcJsonReplacementTask.outputFile.set(dummyJsonFile)
    }
    val downloadHmcSpecifics = tasks.register(
        "downloadHmcSpecifics",
        DownloadHmcSpecificsTask::class.java,
    ) { downloadHmcSpecificsTask ->
        downloadHmcSpecificsTask.group = FIXTURE_TASK_GROUP
        downloadHmcSpecificsTask.description = "Download the selected HMC-Specifics Fabric asset."
        downloadHmcSpecificsTask.offline.set(gradle.startParameter.isOffline)
        downloadHmcSpecificsTask.assetUrl.set(HmcSpecificsTarget.FABRIC_ASSET_URL)
        downloadHmcSpecificsTask.outputFile.set(hmcSpecificsFile)
    }

    val assembleAssets = tasks.register(
        "assembleHeadlessClientAssets",
        AssembleHeadlessClientAssetsTask::class.java,
    ) { assembleHeadlessClientAssetsTask ->
        assembleHeadlessClientAssetsTask.group = FIXTURE_TASK_GROUP
        assembleHeadlessClientAssetsTask.description = "Assemble the filtered HeadlessMC asset store."
        assembleHeadlessClientAssetsTask.assetIndexesDirectory.set(
            downloadAssetIndex.flatMap { it.assetIndexesDirectory },
        )
        assembleHeadlessClientAssetsTask.originalObjectsDirectory.set(
            downloadAssetObjects.flatMap { it.outputDirectory },
        )
        assembleHeadlessClientAssetsTask.dummyOggFile.set(
            downloadAssetReplacements.flatMap { it.dummyOggFile },
        )
        assembleHeadlessClientAssetsTask.dummyPngFile.set(
            downloadAssetReplacements.flatMap { it.dummyPngFile },
        )
        assembleHeadlessClientAssetsTask.dummyJsonFile.set(
            generateJsonReplacement.flatMap { it.outputFile },
        )
        assembleHeadlessClientAssetsTask.outputDirectory.set(minecraftAssetsDirectory)
    }
    val assembleVersionLayout = tasks.register(
        "assembleHeadlessClientVersionLayout",
        Sync::class.java,
    ) { sync ->
        sync.group = FIXTURE_TASK_GROUP
        sync.description = "Assemble exact Mojang and Fabric version profiles."
        sync.from(downloadClient.flatMap { it.clientJar }) { copySpec ->
            copySpec.into(minecraftVersion)
            copySpec.rename { "$minecraftVersion.jar" }
        }
        sync.from(metadataOutput) { copySpec ->
            copySpec.into(minecraftVersion)
            copySpec.rename { "$minecraftVersion.json" }
        }
        val fabricProfileId = FabricLoaderTarget.profileId(minecraftVersion)
        sync.from(downloadClient.flatMap { it.clientJar }) { copySpec ->
            copySpec.into(fabricProfileId)
            copySpec.rename { "$fabricProfileId.jar" }
        }
        sync.from(downloadFabricProfile.flatMap { it.outputFile }) { copySpec ->
            copySpec.into(fabricProfileId)
            copySpec.rename { "$fabricProfileId.json" }
        }
        sync.into(minecraftVersionsDirectory)
    }
    val assembleMods = tasks.register(
        "assembleHeadlessClientMods",
        Sync::class.java,
    ) { sync ->
        sync.group = FIXTURE_TASK_GROUP
        sync.description = "Assemble the selected upstream client mods."
        sync.from(downloadHmcSpecifics.flatMap { it.outputFile })
        sync.into(headlessModsDirectory)
    }
    val generateClientTemplate = tasks.register(
        "generateHeadlessClientTemplate",
        GenerateHeadlessClientTemplateTask::class.java,
    ) { generateHeadlessClientTemplateTask ->
        generateHeadlessClientTemplateTask.group = FIXTURE_TASK_GROUP
        generateHeadlessClientTemplateTask.description =
            "Start, stop, sanitize, and publish the HeadlessMC client template."
        generateHeadlessClientTemplateTask.dependsOn(
            downloadHeadlessLauncher,
            downloadLibraries,
            assembleAssets,
            assembleVersionLayout,
            assembleMods,
        )
        generateHeadlessClientTemplateTask.minecraftVersion.set(minecraftVersion)
        generateHeadlessClientTemplateTask.headlessMcVersion.set(HeadlessMcTarget.HEADLESS_MC_VERSION)
        generateHeadlessClientTemplateTask.fabricLoaderVersion.set(
            FabricLoaderTarget.FABRIC_LOADER_VERSION,
        )
        generateHeadlessClientTemplateTask.hmcSpecificsReleaseTag.set(HmcSpecificsTarget.RELEASE_TAG)
        generateHeadlessClientTemplateTask.hmcSpecificsAssetName.set(HmcSpecificsTarget.FABRIC_ASSET_NAME)
        generateHeadlessClientTemplateTask.hmcSpecificsAssetUrl.set(HmcSpecificsTarget.FABRIC_ASSET_URL)
        generateHeadlessClientTemplateTask.runtimeDirectory.set(headlessRuntimeDirectory)
        generateHeadlessClientTemplateTask.workerClasspath.from(templateWorkerRuntime)
        generateHeadlessClientTemplateTask.templateDirectory.set(headlessTemplateDirectory)
        generateHeadlessClientTemplateTask.manifestFile.set(headlessManifestFile)
    }
    val prepareHeadlessClient = tasks.register("prepareHeadlessClient") { task ->
        task.group = FIXTURE_TASK_GROUP
        task.description = "Prepare the immutable HeadlessMC client fixture."
        task.dependsOn(generateClientTemplate)
    }

    val extractRuntime = tasks.register(
        "extractOfficialServerRuntime",
        ExtractOfficialServerRuntimeTask::class.java,
    ) { extractOfficialServerRuntimeTask ->
        extractOfficialServerRuntimeTask.group = OFFICIAL_DATA_TASK_GROUP
        extractOfficialServerRuntimeTask.description = "Extract the official server implementation and libraries."
        extractOfficialServerRuntimeTask.serverJar.set(downloadServer.flatMap { it.serverJar })
        extractOfficialServerRuntimeTask.outputDirectory.set(serverRuntimeDirectory)
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
        runtimeFiles.asFileTree.matching { patternFilterable ->
            patternFilterable.include("libraries/**/*.jar")
        },
    )
    val javaToolchainService = extensions.getByType(JavaToolchainService::class.java)
    val compileCodecOracle = tasks.register(
        "compileOfficialCodecOracle",
        JavaCompile::class.java,
    ) { javaCompile ->
        javaCompile.group = OFFICIAL_DATA_TASK_GROUP
        javaCompile.description = "Compile the official codec bridge."
        javaCompile.source(codecOracleSource)
        javaCompile.classpath = codecCompileClasspath
        javaCompile.destinationDirectory.set(codecClassesDirectory)
        javaCompile.options.encoding = StandardCharsets.UTF_8.name()
        javaCompile.options.release.set(BuildVersions.JAVA_VERSION)
        javaCompile.sourceCompatibility = BuildVersions.JAVA_VERSION.toString()
        javaCompile.targetCompatibility = BuildVersions.JAVA_VERSION.toString()
        javaCompile.javaCompiler.set(
            javaToolchainService.compilerFor { javaToolchainSpec ->
                javaToolchainSpec.languageVersion.set(
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
    ) { analyzeOfficialMinecraftTargetTask ->
        analyzeOfficialMinecraftTargetTask.group = OFFICIAL_DATA_TASK_GROUP
        analyzeOfficialMinecraftTargetTask.description = "Analyze version and protocol facts."
        analyzeOfficialMinecraftTargetTask.serverJar.set(downloadServer.flatMap { it.serverJar })
        analyzeOfficialMinecraftTargetTask.outputFile.set(targetFile)
    }
    val analyzeReports = tasks.register(
        "analyzeOfficialMinecraftReports",
        AnalyzeOfficialMinecraftReportsTask::class.java,
    ) { analyzeOfficialMinecraftReportsTask ->
        analyzeOfficialMinecraftReportsTask.group = OFFICIAL_DATA_TASK_GROUP
        analyzeOfficialMinecraftReportsTask.description = "Capture official packets, registries, and blocks reports."
        analyzeOfficialMinecraftReportsTask.serverJar.set(downloadServer.flatMap { it.serverJar })
        analyzeOfficialMinecraftReportsTask.outputDirectory.set(reportsDirectory)
    }
    val analyzeConfiguration = tasks.register(
        "analyzeOfficialMinecraftConfiguration",
        AnalyzeOfficialMinecraftConfigurationTask::class.java,
    ) { analyzeOfficialMinecraftConfigurationTask ->
        analyzeOfficialMinecraftConfigurationTask.group = OFFICIAL_DATA_TASK_GROUP
        analyzeOfficialMinecraftConfigurationTask.description = "Capture official Configuration Known Packs branches."
        analyzeOfficialMinecraftConfigurationTask.serverJar.set(downloadServer.flatMap { it.serverJar })
        analyzeOfficialMinecraftConfigurationTask.packetsReport.set(
            analyzeReports.flatMap {
                it.outputDirectory.file("reports/packets.json")
            },
        )
        analyzeOfficialMinecraftConfigurationTask.outputFile.set(configurationFile)
    }
    val extractDataPacks = tasks.register(
        "extractOfficialMinecraftDataPacks",
        ExtractOfficialMinecraftDataPacksTask::class.java,
    ) { extractOfficialMinecraftDataPacksTask ->
        extractOfficialMinecraftDataPacksTask.group = OFFICIAL_DATA_TASK_GROUP
        extractOfficialMinecraftDataPacksTask.description = "Extract official core and built-in data packs."
        extractOfficialMinecraftDataPacksTask.implementationJar.set(extractRuntime.flatMap { it.outputDirectory.file("server.jar") })
        extractOfficialMinecraftDataPacksTask.outputDirectory.set(dataPacksDirectory)
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

    val minecraftTestFixtureOutputs = MinecraftTestFixtureOutputs(
        officialServer = files(officialServerRoot).builtBy(prepareServer),
        headlessClient = files(headlessClientRoot).builtBy(
            prepareHeadlessClient,
        ),
        codecOracle = files(
            serverJarFile,
            serverRuntimeDirectory,
            codecClassesDirectory,
        ).builtBy(prepareCodecOracle),
        officialServerRootDirectory = officialServerRoot,
        headlessClientRootDirectory = headlessClientRoot,
        serverRuntimeDirectory = serverRuntimeDirectory,
        codecClassesDirectory = codecClassesDirectory,
    )
    extensions.add("minecraftTestFixtureOutputs", minecraftTestFixtureOutputs)
    return minecraftTestFixtureOutputs
}

private const val FIXTURE_TASK_GROUP = "minecraft fixtures"
private const val OFFICIAL_DATA_TASK_GROUP = "official minecraft data"
private const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
