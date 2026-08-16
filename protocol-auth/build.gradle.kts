import com.hiczp.minecraft.buildlogic.BuildVersions
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
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
        namespace = "com.hiczp.minecraft.protocol.auth"
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
        val javaCryptoMain = create("javaCryptoMain") {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(javaCryptoMain)
        }
        androidMain {
            dependsOn(javaCryptoMain)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.provider.optimal)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        jsMain.dependencies {
            implementation(npm("node-forge", libs.versions.node.forge.get()))
        }
        wasmJsMain.dependencies {
            implementation(npm("node-forge", libs.versions.node.forge.get()))
        }

        commonMain.dependencies {
            compileOnly(project(":protocol-model"))
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.properties)
            implementation(libs.okio)
            implementation(libs.cryptography.bigint)
            implementation(libs.cryptography.random)
        }
        //Using compileOnly dependencies in these targets is not currently supported, because compileOnly dependencies must be present during the compilation of projects that depend on this project
        nativeMain.dependencies {
            api(project(":protocol-model"))
        }
        webMain.dependencies {
            api(project(":protocol-model"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":protocol-model"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
