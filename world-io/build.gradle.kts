import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.useMinecraftTestFixtures
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

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
        namespace = "com.hiczp.minecraft.world.io"
        compileSdk = BuildVersions.ANDROID_COMPILE_SDK
        minSdk = BuildVersions.ANDROID_MIN_SDK
        withHostTest {}
        compilerOptions {
            jvmTarget.set(
                JvmTarget.fromTarget(BuildVersions.JAVA_VERSION.toString()),
            )
        }
    }

    useMinecraftTestFixtures(requiresOfficialServer = true)

    sourceSets {
        commonMain.dependencies {
            api(project(":nbt"))
            api(project(":nbt-serialization"))
            api(project(":world-format"))
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":minecraft-test-support"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
