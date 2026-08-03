import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.hiczp.minecraft.protocol.buildScript.*
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX64()
    androidNativeX86()

    targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java)
        .configureEach {
            namespace = "com.hiczp.minecraft.protocol.data"
            compileSdk = BuildVersions.ANDROID_COMPILE_SDK
            minSdk = BuildVersions.ANDROID_MIN_SDK
            withHostTest {}
            compilerOptions {
                jvmTarget.set(
                    JvmTarget.fromTarget(BuildVersions.JAVA_VERSION.toString()),
                )
            }
        }

    js {
        nodejs {
            testTask {
                environment("NODE_USE_ENV_PROXY", "1")
            }
        }
        browser()
    }

    wasmJs {
        nodejs {
            testTask {
                environment("NODE_USE_ENV_PROXY", "1")
            }
        }
        browser()
        d8()
    }

    wasmWasi {
        nodejs()
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
