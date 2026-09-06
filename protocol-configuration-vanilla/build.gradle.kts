import com.hiczp.minecraft.buildlogic.*
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val officialReportsDirectory = officialMinecraftArtifactDirectory("officialMinecraftReports")
val officialConfigurationFile = officialMinecraftArtifactFile("officialMinecraftConfiguration")
val officialDataPacksDirectory = officialMinecraftArtifactDirectory("officialMinecraftDataPacks")

val generatedRegistryDataDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaRegistryData/commonMain/kotlin",
)
val generatedConfigurationPacketPayloadDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaConfigurationPacketPayloads/commonMain/kotlin",
)

val generateVanillaRegistryDataSource =
    tasks.register<GenerateVanillaRegistryDataSourceTask>(
        "generateVanillaRegistryDataSource",
    ) {
        description = "Generate typed vanilla registry and block-state source."
        registriesFile = officialReportsDirectory.map {
            it.file("reports/registries.json")
        }
        blocksFile = officialReportsDirectory.map {
            it.file("reports/blocks.json")
        }
        outputFile =
            generatedRegistryDataDirectory.map {
                it.file("com/hiczp/minecraft/protocol/configuration/vanilla/VanillaRegistryDataPayloads.kt")
            }
    }

val generateVanillaConfigurationPacketPayloadSource =
    tasks.register<GenerateVanillaConfigurationPacketPayloadSourceTask>(
        "generateVanillaConfigurationPacketPayloadSource",
    ) {
        description = "Generate vanilla Configuration source from analysis data."
        configurationFile = officialConfigurationFile
        dataPackManifestFile = officialDataPacksDirectory.map { it.file("manifest.json") }
        outputFile =
            generatedConfigurationPacketPayloadDirectory.map {
                it.file("com/hiczp/minecraft/protocol/configuration/vanilla/VanillaConfigurationPacketPayloads.kt")
            }
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
        namespace = "com.hiczp.minecraft.protocol.configuration.vanilla"
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
                files(generatedRegistryDataDirectory)
                    .builtBy(generateVanillaRegistryDataSource),
            )
            kotlin.srcDir(
                files(generatedConfigurationPacketPayloadDirectory)
                    .builtBy(generateVanillaConfigurationPacketPayloadSource),
            )
            dependencies {
                api(project(":protocol-configuration"))
                implementation(project(":protocol-serialization"))
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.json.io)
            }
        }

        commonTest.dependencies {
            implementation(project(":datapack-vanilla"))
            implementation(kotlin("test"))
        }
    }
}
