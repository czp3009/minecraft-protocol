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

    // The serializer is portable to JS, but this fixture depends on the
    // socket-backed protocol-transport module, whose JS target is omitted.
    createHostProcessTestSourceSet(
        name = "externalProcessTest",
        includeJsTarget = false,
    ) {
        dependencies {
            implementation(project(":minecraft-test-support"))
            implementation(project(":protocol-transport"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":nbt"))
                api(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

    }
}

officialDownloads { server(); codecOracle() }
