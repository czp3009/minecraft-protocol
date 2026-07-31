import com.hiczp.minecraft.protocol.buildScript.*

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val generatedStaticDataDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaStaticData/commonMain/kotlin",
)
val generatedConfigurationDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaConfiguration/commonMain/kotlin",
)
val generatedConfigurationReport = layout.buildDirectory.file(
    "generated/reports/vanillaConfiguration/configuration.json",
)

val generateVanillaStaticDataSource =
    tasks.register<GenerateVanillaStaticDataSourceTask>(
        "generateVanillaStaticDataSource",
    ) {
        description = "Generate typed vanilla registry and block-state source."
        val download = rootProject.tasks
            .named<DownloadOfficialMinecraftServerTask>(
                "downloadOfficialMinecraftServer",
            )
        val reports = rootProject.tasks
            .named<GenerateOfficialMinecraftReportsTask>(
                "generateOfficialMinecraftReports",
            )
        serverJar.set(download.flatMap { it.serverJar })
        registriesFile.set(reports.flatMap {
            it.outputDirectory.file("reports/registries.json")
        })
        blocksFile.set(reports.flatMap {
            it.outputDirectory.file("reports/blocks.json")
        })
        outputFile.set(
            generatedStaticDataDirectory.map {
                it.file(
                    "com/hiczp/minecraft/protocol/data/" +
                            "VanillaStaticDataPayloads.kt",
                )
            },
        )
    }

val java25Launcher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
val generateVanillaConfigurationData =
    tasks.register<GenerateVanillaConfigurationDataTask>(
        "generateVanillaConfigurationData",
    ) {
        description = "Generate vanilla Configuration payload source and its manifest."
        val download = rootProject.tasks
            .named<DownloadOfficialMinecraftServerTask>(
                "downloadOfficialMinecraftServer",
            )
        val reports = rootProject.tasks
            .named<GenerateOfficialMinecraftReportsTask>(
                "generateOfficialMinecraftReports",
            )
        javaExecutable.set(
            java25Launcher.map {
                it.executablePath.asFile.absolutePath
            },
        )
        serverJar.set(download.flatMap { it.serverJar })
        packetsReport.set(reports.flatMap {
            it.outputDirectory.file("reports/packets.json")
        })
        generatedKotlin.set(
            generatedConfigurationDirectory.map {
                it.file(
                    "com/hiczp/minecraft/protocol/data/" +
                            "VanillaConfigurationPayloads.kt",
                )
            },
        )
        manifest.set(generatedConfigurationReport)
    }

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.data")

    sourceSets {
        commonMain {
            kotlin.srcDir(
                files(generatedStaticDataDirectory)
                    .builtBy(generateVanillaStaticDataSource),
            )
            kotlin.srcDir(
                files(generatedConfigurationDirectory)
                    .builtBy(generateVanillaConfigurationData),
            )
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":protocol-serialization"))
                implementation(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
