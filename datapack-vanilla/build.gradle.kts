import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.GenerateVanillaDataPackSourcesTask
import com.hiczp.minecraft.buildlogic.officialMinecraftArtifactDirectory
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val officialDataPacksDirectory = officialMinecraftArtifactDirectory("officialMinecraftDataPacks")
val generatedDataPacksDirectory = layout.buildDirectory.dir("generated/sources/vanillaDataPacks/commonMain/kotlin")
val generateVanillaDataPackSources =
    tasks.register<GenerateVanillaDataPackSourcesTask>("generateVanillaDataPackSources") {
        description = "Generate lazily loaded official data-pack sources."
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
        namespace = "com.hiczp.minecraft.world.format.datapack.vanilla"
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
            kotlin.srcDir(files(generatedDataPacksDirectory).builtBy(generateVanillaDataPackSources))
            dependencies {
                api(project(":world-format"))
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
