import com.hiczp.minecraft.buildlogic.BuildVersions
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinxRpc)
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

    js {
        nodejs()
    }

    wasmJs {
        nodejs()
    }

    wasmWasi {
        nodejs()
    }

    android {
        namespace = "com.hiczp.minecraft.test"
        compileSdk = BuildVersions.ANDROID_COMPILE_SDK
        minSdk = BuildVersions.ANDROID_MIN_SDK
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.rpc.core)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.ktor.client.cio)
        }
        nativeMain.dependencies {
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.ktor.client.cio)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.ktor.client.cio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
