package com.hiczp.minecraft.protocol.buildScript

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

/**
 * Creates a test source set for targets that can launch a local external
 * process. [includeJsTarget] can exclude JS when the fixture has dependencies
 * that intentionally publish no JS variant.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun KotlinMultiplatformExtension.createHostProcessTestSourceSet(
    name: String,
    includeJsTarget: Boolean = true,
    crossinline configure: KotlinSourceSet.() -> Unit = {},
) {
    val desktopNativeFamilies = setOf(Family.LINUX, Family.MINGW, Family.OSX)
    val hostTarget = HostManager.hostOrNull
    val commonTest = sourceSets.named(KotlinSourceSet.COMMON_TEST_SOURCE_SET_NAME)
    val hostProcessTest = sourceSets.create(name) { sourceSet ->
        sourceSet.dependsOn(commonTest.get())
        sourceSet.configure()
    }

    targets.configureEach { target ->
        val supportsHostProcesses =
            target.platformType == KotlinPlatformType.jvm ||
                    (includeJsTarget && target.platformType == KotlinPlatformType.js) ||
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
