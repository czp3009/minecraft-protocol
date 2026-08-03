import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.hiczp.minecraft.protocol.buildScript.BuildVersions
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
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
            namespace = "com.hiczp.minecraft.protocol.transport"
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

    sourceSets {
        commonMain.dependencies {
            implementation(project(":compression"))
            api(libs.ktor.network)
            implementation(libs.ktor.utils)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
