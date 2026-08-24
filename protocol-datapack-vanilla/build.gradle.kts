import com.hiczp.minecraft.buildlogic.*
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val officialTargetFile = officialMinecraftArtifactFile("officialMinecraftTarget")
val officialReportsDirectory = officialMinecraftArtifactDirectory("officialMinecraftReports")
val officialConfigurationFile = officialMinecraftArtifactFile("officialMinecraftConfiguration")
val officialDataPacksDirectory = officialMinecraftArtifactDirectory("officialMinecraftDataPacks")

val generatedStaticDataDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaStaticData/commonMain/kotlin",
)
val generatedConfigurationDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaConfiguration/commonMain/kotlin",
)
val generatedDataPacksDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaDataPacks/commonMain/kotlin",
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
                it.file("com/hiczp/minecraft/protocol/datapack/vanilla/VanillaStaticDataPayloads.kt")
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
                it.file("com/hiczp/minecraft/protocol/datapack/vanilla/VanillaConfigurationPayloads.kt")
            }
    }

val generateVanillaDataPackSources = tasks.register<GenerateVanillaDataPackSourcesTask>(
    "generateVanillaDataPackSources",
) {
    description = "Generate lazily loaded vanilla data-pack sources from the official extracted packs."
    extractedDataPacksDirectory.set(officialDataPacksDirectory)
    outputDirectory.set(generatedDataPacksDirectory)
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    applyDefaultHierarchyTemplate()

    jvm()

    mingwX64()
    linuxArm64()
    linuxX64()
    macosArm64()

    iosSimulatorArm64()
    iosArm64()
    iosX64()

    watchosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()

    tvosSimulatorArm64()
    tvosArm64()

    android {
        namespace = "com.hiczp.minecraft.protocol.datapack.vanilla"
        compileSdk = BuildVersions.ANDROID_COMPILE_SDK
        minSdk = BuildVersions.ANDROID_MIN_SDK
        withHostTest {}
    }

    js {
        nodejs()
        browser()
    }

    wasmJs {
        nodejs()
        browser()
    }

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
            kotlin.srcDir(
                files(generatedDataPacksDirectory)
                    .builtBy(generateVanillaDataPackSources),
            )
            dependencies {
                api(project(":protocol-datapack"))
                implementation(project(":protocol-serialization"))
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.json.io)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
