import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.useMinecraftTestFixtures
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
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
        namespace = "com.hiczp.minecraft.protocol.server"
        compileSdk = BuildVersions.ANDROID_COMPILE_SDK
        minSdk = BuildVersions.ANDROID_MIN_SDK
        withHostTest {}
    }

    js {
        nodejs()
    }

    wasmJs {
        nodejs()
    }

    useMinecraftTestFixtures(requiresHeadlessClient = true)

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            api(project(":protocol-session"))
            api(project(":protocol-transport"))
            api(project(":protocol-auth"))
            api(project(":protocol-datapack"))
            api(project(":world-format"))
            api(libs.ktor.client.core)
            api(libs.ktor.network)
            api(libs.ktor.utils)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":protocol-datapack-vanilla"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.json.io)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":nbt"))
            implementation(project(":protocol-client"))
            implementation(project(":minecraft-test-support"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
