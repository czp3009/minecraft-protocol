import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.useMinecraftTestFixtures
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
        namespace = "com.hiczp.minecraft.protocol.serialization"
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
    }

    wasmWasi {
        nodejs()
    }

    useMinecraftTestFixtures(
        requiresOfficialServer = true,
        requiresCodecOracle = true,
    )

    sourceSets {
        commonMain {
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":nbt"))
                implementation(project(":nbt-serialization"))
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":minecraft-test-support"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }

        jvmTest.dependencies {
            implementation(project(":protocol-transport"))
        }
        named("androidHostTest") {
            dependencies {
                implementation(project(":protocol-transport"))
            }
        }
        nativeTest.dependencies {
            implementation(project(":protocol-transport"))
        }
        wasmJsTest.dependencies {
            implementation(project(":protocol-transport"))
        }
    }
}

tasks.withType(AbstractTestTask::class.java).configureEach {
    if (name == "jsNodeTest") {
        filter.excludeTestsMatching("*OfficialServerInteropTest*")
    }
}
