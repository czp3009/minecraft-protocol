package com.hiczp.minecraft.protocol.buildScript

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

@OptIn(ExperimentalWasmDsl::class)
@Suppress("NOTHING_TO_INLINE")
inline fun KotlinMultiplatformExtension.configureAllTargets(
    namespace: String,
    includeWasmWasi: Boolean = true,
    includeJs: Boolean = true,
    includeWasmJs: Boolean = true,
) {
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

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

    jvm()

    (this as ExtensionAware).configure<KotlinMultiplatformAndroidLibraryTarget> {
        this.namespace = namespace
        this.compileSdk = 36
        this.minSdk = 34
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    if (includeJs) {
        js {
            browser()
            nodejs()
        }
    }

    if (includeWasmJs) {
        wasmJs {
            browser()
            nodejs()
            d8()
        }
    }

    if (includeWasmWasi) {
        wasmWasi {
            nodejs()
        }
    }
}
