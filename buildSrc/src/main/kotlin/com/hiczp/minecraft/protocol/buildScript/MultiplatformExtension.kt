package com.hiczp.minecraft.protocol.buildScript

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager

@Suppress("NOTHING_TO_INLINE")
inline fun KotlinMultiplatformExtension.configureAllTargets(
    namespace: String,
    includeWasmWasi: Boolean = true,
    includeJs: Boolean = true,
    includeWasmJs: Boolean = true,
    includeWasmJsD8: Boolean = true,
    nodeTestTimeout: String = "20m",
) {
    jvmToolchain(25)

    applyDefaultHierarchyTemplate()

    configureDesktopNativeTargets()

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

    targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java)
        .configureEach { target ->
            target.namespace = namespace
            target.compileSdk = 36
            target.minSdk = 34
            target.withHostTest {}
            target.compilerOptions {
                jvmTarget.set(JvmTarget.JVM_25)
            }
        }

    configureWebTargets(
        includeJs = includeJs,
        includeWasmJs = includeWasmJs,
        includeWasmJsD8 = includeWasmJsD8,
        includeWasmWasi = includeWasmWasi,
        nodeTestTimeout = nodeTestTimeout,
    )
}

@Suppress("NOTHING_TO_INLINE")
inline fun KotlinMultiplatformExtension.configureDesktopNativeTargets() {
    mingwX64()

    linuxArm64()
    linuxX64()

    macosArm64()
}

@OptIn(ExperimentalWasmDsl::class)
@Suppress("NOTHING_TO_INLINE")
inline fun KotlinMultiplatformExtension.configureWebTargets(
    includeJs: Boolean = true,
    includeWasmJs: Boolean = true,
    includeWasmJsD8: Boolean = true,
    includeWasmWasi: Boolean = true,
    nodeTestTimeout: String = "20m",
) {
    if (includeJs) {
        js {
            nodejs {
                testTask { test ->
                    test.environment("NODE_USE_ENV_PROXY", "1")
                    test.useMocha { mocha ->
                        mocha.timeout = nodeTestTimeout
                    }
                }
            }
        }
    }

    if (includeWasmJs) {
        wasmJs {
            nodejs {
                testTask { test ->
                    test.environment("NODE_USE_ENV_PROXY", "1")
                }
            }
            if (includeWasmJsD8) {
                d8()
            }
        }
    }

    if (includeWasmWasi) {
        wasmWasi {
            nodejs()
        }
    }
}

/** Creates a test source set for targets that can launch a local external process. */
@Suppress("NOTHING_TO_INLINE")
inline fun KotlinMultiplatformExtension.createHostProcessTestSourceSet(
    name: String,
    crossinline configure: KotlinSourceSet.() -> Unit = {},
) {
    val desktopNativeFamilies = setOf(Family.LINUX, Family.MINGW, Family.OSX)
    val directProcessPlatforms = setOf(KotlinPlatformType.jvm, KotlinPlatformType.js)
    val hostTarget = HostManager.hostOrNull
    val commonTest = sourceSets.named(KotlinSourceSet.COMMON_TEST_SOURCE_SET_NAME)
    val hostProcessTest = sourceSets.create(name) { sourceSet ->
        sourceSet.dependsOn(commonTest.get())
        sourceSet.configure()
    }

    targets.configureEach { target ->
        val supportsHostProcesses =
            target.platformType in directProcessPlatforms ||
                target is KotlinNativeTarget && target.konanTarget.family in desktopNativeFamilies ||
                target is KotlinJsIrTarget && target.wasmTargetType == KotlinWasmTargetType.JS
        if (!supportsHostProcesses) return@configureEach

        target.compilations.named(KotlinCompilation.TEST_COMPILATION_NAME).configure { compilation ->
            compilation.defaultSourceSet.dependsOn(hostProcessTest)
        }

        if (target is KotlinNativeTarget && target.konanTarget != hostTarget) {
            target.binaries.withType(TestExecutable::class.java).configureEach { binary ->
                binary.linkTaskProvider.configure { task ->
                    task.onlyIf("external-process tests run only on their matching host") { false }
                }
            }
        }
    }
}
