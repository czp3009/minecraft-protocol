import com.hiczp.minecraft.buildlogic.BuildVersions
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)

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
        namespace = "com.hiczp.minecraft.nbt"
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
        nodejs()
        browser()
    }

    wasmJs {
        nodejs()
        browser()
        d8()
    }

    wasmWasi {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
