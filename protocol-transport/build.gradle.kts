import com.hiczp.minecraft.buildlogic.BuildVersions
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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

    android {
        namespace = "com.hiczp.minecraft.protocol.transport"
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

    // Okio compression is shared by JVM, Android, and Native, while JCA AES/CFB8 is shared only by JVM and Android.
    // Keep the additional hierarchy linear: commonMain <- okioCompressionMain <- javaCryptoMain <-
    // {jvmMain, androidMain}; nativeMain depends directly on okioCompressionMain and uses cryptography-kotlin.
    // JS and WasmJS stay on the default web hierarchy and use the Node crypto module.
    sourceSets {
        val okioCompressionMain = create("okioCompressionMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlinx.io.okio)
                implementation(libs.okio)
            }
        }
        val javaCryptoMain = create("javaCryptoMain") {
            dependsOn(okioCompressionMain)
        }
        jvmMain {
            dependsOn(javaCryptoMain)
        }
        androidMain {
            dependsOn(javaCryptoMain)
        }
        nativeMain {
            dependsOn(okioCompressionMain)
            dependencies {
                implementation(libs.cryptography.provider.optimal)
            }
        }

        commonMain.dependencies {
            api(libs.ktor.network)
            api(libs.ktor.utils)
            api(libs.kotlinx.io.core)
            api(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
        webMain.dependencies {
            implementation(libs.kompress.core)
            implementation(libs.kompress.zlib)
            implementation(libs.kotlinx.browser)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
