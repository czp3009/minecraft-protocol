@file:Suppress("NOTHING_TO_INLINE")

package com.hiczp.minecraft.protocol.buildScript

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.ExecutionTaskHolder
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetWithTests

/**
 * Creates the ordinary `hostProcessTest` source set for targets whose test
 * runtime can launch a local process. Semantic fixture flags are mapped to
 * lazy root-task outputs and attached to the actual standard test tasks.
 */
inline fun KotlinMultiplatformExtension.createHostProcessTestSourceSet(
    requiresOfficialServer: Boolean = false,
    requiresOfficialClient: Boolean = false,
    requiresCodecOracle: Boolean = false,
    crossinline configure: KotlinSourceSet.() -> Unit = {},
) {
    val owningProject = project
    val hostNativeTargetName = hostNativeTargetName()
    val commonTest = sourceSets.named(KotlinSourceSet.COMMON_TEST_SOURCE_SET_NAME)
    val hostProcessTest = sourceSets.create(
        HOST_PROCESS_TEST_SOURCE_SET,
    ) { sourceSet ->
        sourceSet.dependsOn(commonTest.get())
        if (owningProject.path != MINECRAFT_TEST_SUPPORT_PROJECT) {
            sourceSet.dependencies { dependencies ->
                dependencies.implementation(
                    dependencies.project(MINECRAFT_TEST_SUPPORT_PROJECT),
                )
            }
        }
        configure(sourceSet)
    }

    val needsAnyFixture = requiresOfficialServer ||
            requiresOfficialClient || requiresCodecOracle
    val fixtureOutputs = if (needsAnyFixture) {
        owningProject.rootProject.extensions.getByType(
            OfficialMinecraftFixtureOutputs::class.java,
        )
    } else {
        null
    }
    val hostFixtureInputs = owningProject.files().apply {
        if (requiresOfficialServer) {
            from(checkNotNull(fixtureOutputs).officialServer)
        }
        if (requiresOfficialClient) {
            from(checkNotNull(fixtureOutputs).officialClient)
        }
    }
    val jvmFixtureInputs = owningProject.files(hostFixtureInputs).apply {
        if (requiresCodecOracle) {
            from(checkNotNull(fixtureOutputs).codecOracle)
        }
    }

    targets.configureEach { target ->
        val isJvm = target.platformType == KotlinPlatformType.jvm
        val isDesktopNative = target.platformType == KotlinPlatformType.native &&
                target.name in DESKTOP_NATIVE_TARGET_NAMES
        val isHostNative = target.platformType == KotlinPlatformType.native &&
                target.name == hostNativeTargetName
        val isNodeOnlyWebTarget = target.isNodeOnlyWebTarget()
        if (!isJvm && !isDesktopNative && !isNodeOnlyWebTarget) {
            return@configureEach
        }

        target.compilations.named(
            KotlinCompilation.TEST_COMPILATION_NAME,
        ).configure { compilation ->
            compilation.defaultSourceSet.dependsOn(hostProcessTest)
        }

        if (needsAnyFixture && (isJvm || isHostNative || isNodeOnlyWebTarget)) {
            val fixtureInputs = if (isJvm) {
                jvmFixtureInputs
            } else {
                hostFixtureInputs
            }
            wireFixtureInputs(
                target = target,
                fixtureInputs = fixtureInputs,
                webTarget = isNodeOnlyWebTarget,
            )
        }
    }
}

@PublishedApi
internal inline fun KotlinTarget.isNodeOnlyWebTarget(): Boolean {
    if (
        platformType != KotlinPlatformType.js &&
        platformType != KotlinPlatformType.wasm
    ) {
        return false
    }
    if (!booleanTargetGetter(this, "isNodejsConfigured")) return false
    if (booleanTargetGetter(this, "isBrowserConfigured")) return false
    if (booleanTargetGetter(this, "isD8Configured")) return false
    if (platformType == KotlinPlatformType.wasm) {
        val wasmType = javaClass.methods.single { method ->
            method.name == "getWasmTargetType" && method.parameterCount == 0
        }.invoke(this)
        if (wasmType.toString() == "WASI") return false
    }
    return true
}

@PublishedApi
internal inline fun booleanTargetGetter(
    target: KotlinTarget,
    name: String,
): Boolean = target.javaClass.methods.single { method ->
    method.name == name && method.parameterCount == 0
}.invoke(target) as Boolean

@PublishedApi
internal inline fun wireFixtureInputs(
    target: KotlinTarget,
    fixtureInputs: FileCollection,
    webTarget: Boolean,
) {
    val testRun = if (webTarget) {
        val subTargets = target.javaClass.methods.single { method ->
            method.name == "getSubTargets" && method.parameterCount == 0
        }.invoke(target) as NamedDomainObjectContainer<*>
        val node = checkNotNull(subTargets.findByName("node")) {
            "Node target ${target.name} has no Node subtarget"
        }
        val testRuns = node.javaClass.methods.single { method ->
            method.name == "getTestRuns" && method.parameterCount == 0
        }.invoke(node) as NamedDomainObjectContainer<*>
        testRuns.getByName(KotlinTargetWithTests.DEFAULT_TEST_RUN_NAME)
    } else {
        val targetWithTests = target as? KotlinTargetWithTests<*, *>
            ?: error(
                "Host-process target ${target.name} has no standard test run",
            )
        targetWithTests.testRuns.getByName(
            KotlinTargetWithTests.DEFAULT_TEST_RUN_NAME,
        )
    }
    registerFixtureInputs(testRun, fixtureInputs)
}

@PublishedApi
internal inline fun registerFixtureInputs(
    testRun: Any,
    fixtureInputs: FileCollection,
) {
    val execution = testRun as? ExecutionTaskHolder<*>
        ?: error("Kotlin test run does not expose its execution task")
    execution.executionTask.configure { task ->
        task.inputs.files(fixtureInputs)
            .withPropertyName("officialMinecraftFixtures")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

@PublishedApi
internal fun hostNativeTargetName(): String? {
    val architecture = System.getProperty("os.arch").lowercase()
    return when {
        System.getProperty("os.name").startsWith("Windows") -> "mingwX64"
        System.getProperty("os.name").startsWith("Mac") ->
            if (architecture in setOf("aarch64", "arm64")) {
                "macosArm64"
            } else {
                "macosX64"
            }

        architecture in setOf("aarch64", "arm64") -> "linuxArm64"
        else -> "linuxX64"
    }
}

@PublishedApi
internal const val HOST_PROCESS_TEST_SOURCE_SET = "hostProcessTest"

@PublishedApi
internal const val MINECRAFT_TEST_SUPPORT_PROJECT = ":minecraft-test-support"

@PublishedApi
internal val DESKTOP_NATIVE_TARGET_NAMES = setOf(
    "linuxArm64",
    "linuxX64",
    "macosArm64",
    "macosX64",
    "mingwX64",
)
