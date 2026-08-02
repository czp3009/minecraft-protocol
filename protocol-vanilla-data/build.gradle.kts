import com.hiczp.minecraft.protocol.buildScript.*

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val officialTargetFile =
    officialMinecraftAnalysisFile("officialMinecraftTarget")
val officialReportsDirectory =
    officialMinecraftAnalysisDirectory("officialMinecraftReports")
val officialConfigurationFile =
    officialMinecraftAnalysisFile("officialMinecraftConfiguration")

val generatedStaticDataDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaStaticData/commonMain/kotlin",
)
val generatedConfigurationDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaConfiguration/commonMain/kotlin",
)

val generateVanillaStaticDataSource =
    tasks.register<GenerateVanillaStaticDataSourceTask>(
        "generateVanillaStaticDataSource",
    ) {
        description = "Generate typed vanilla registry and block-state source."
        targetFile = officialTargetFile
        registriesFile = officialReportsDirectory.map {
            it.file("reports/registries.json")
        }
        blocksFile = officialReportsDirectory.map {
            it.file("reports/blocks.json")
        }
        outputFile =
            generatedStaticDataDirectory.map {
                it.file("com/hiczp/minecraft/protocol/data/VanillaStaticDataPayloads.kt")
            }
    }

val generateVanillaConfigurationSource =
    tasks.register<GenerateVanillaConfigurationSourceTask>(
        "generateVanillaConfigurationSource",
    ) {
        description = "Generate vanilla Configuration source from analysis data."
        targetFile = officialTargetFile
        configurationFile = officialConfigurationFile
        outputFile =
            generatedConfigurationDirectory.map {
                it.file("com/hiczp/minecraft/protocol/data/VanillaConfigurationPayloads.kt")
            }
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
                    .builtBy(generateVanillaConfigurationSource),
            )
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":protocol-serialization"))
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
