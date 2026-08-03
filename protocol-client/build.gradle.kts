import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.hiczp.minecraft.protocol.buildScript.BuildVersions
import com.hiczp.minecraft.protocol.buildScript.createHostProcessTestSourceSet
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
            namespace = "com.hiczp.minecraft.protocol.client"
            compileSdk = BuildVersions.ANDROID_COMPILE_SDK
            minSdk = BuildVersions.ANDROID_MIN_SDK
            withHostTest {}
            compilerOptions {
                jvmTarget.set(
                    JvmTarget.fromTarget(BuildVersions.JAVA_VERSION.toString()),
                )
            }
        }

    wasmJs {
        nodejs {
            testTask {
                environment("NODE_USE_ENV_PROXY", "1")
            }
        }
    }

    createHostProcessTestSourceSet("externalProcessTest") {
        dependencies {
            implementation(project(":minecraft-test-support"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            api(project(":protocol-session"))
            api(project(":protocol-transport"))
            api(project(":protocol-auth"))
            api(libs.ktor.network)
            implementation(project(":protocol-vanilla-data"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.mock)
        }
    }
}

officialDownloads { server() }
