@file:Suppress("NOTHING_TO_INLINE")

package com.hiczp.minecraft.buildlogic

import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.AbstractTestTask
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

private const val KOTLIN_NATIVE_SIMULATOR_TEST_CLASS =
    "org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest"

@PublishedApi
internal val unsupportedMinecraftFixtureTestTasks = setOf(
    "jsBrowserTest",
    "wasmJsBrowserTest",
    "wasmJsD8Test",
    "wasmWasiNodeTest",
)

/** Supplies the Gradle-managed Fixture Host to this module's standard tests. */
inline fun KotlinMultiplatformExtension.useMinecraftTestFixtures(
    requiresOfficialServer: Boolean = false,
    requiresHeadlessClient: Boolean = false,
    requiresCodecOracle: Boolean = false,
) {
    require(requiresOfficialServer || requiresHeadlessClient || requiresCodecOracle) {
        "At least one Minecraft fixture capability must be requested"
    }
    val owningProject = project
    val minecraftTestFixtureOutputs = owningProject.rootProject.extensions.getByType(
        MinecraftTestFixtureOutputs::class.java,
    )
    val minecraftTestFixtureInfrastructure = owningProject.rootProject.extensions.getByType(
        MinecraftTestFixtureInfrastructure::class.java,
    )
    val fixtureInputs = owningProject.files().apply {
        if (requiresOfficialServer) from(minecraftTestFixtureOutputs.officialServer)
        if (requiresHeadlessClient) from(minecraftTestFixtureOutputs.headlessClient)
        if (requiresCodecOracle) from(minecraftTestFixtureOutputs.codecOracle)
    }

    owningProject.tasks.withType(AbstractTestTask::class.java).configureEach { abstractTestTask ->
        if (abstractTestTask.name in unsupportedMinecraftFixtureTestTasks) {
            abstractTestTask.filter.excludeTestsMatching("*.fixturetest.*")
        } else {
            registerMinecraftTestFixtures(abstractTestTask, fixtureInputs, minecraftTestFixtureInfrastructure)
        }
    }
}

@PublishedApi
internal fun registerMinecraftTestFixtures(
    abstractTestTask: AbstractTestTask,
    fixtureInputs: FileCollection,
    minecraftTestFixtureInfrastructure: MinecraftTestFixtureInfrastructure,
) {
    // Provider provenance makes Gradle prepare these inputs before the host is
    // first requested by the executing test task.
    abstractTestTask.inputs.files(fixtureInputs)
        .withPropertyName("minecraftTestFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    abstractTestTask.inputs.files(minecraftTestFixtureInfrastructure.hostClasspath)
        .withPropertyName("minecraftTestFixtureHostClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    abstractTestTask.usesService(minecraftTestFixtureInfrastructure.service)
    val taskPath = abstractTestTask.path
    abstractTestTask.doFirst { executingTask ->
        val minecraftTestFixtureConnection = minecraftTestFixtureInfrastructure.service.get().connectionFor(taskPath)
        val environmentPrefix =
            if (executingTask.isKotlinNativeSimulatorTest()) {
                "SIMCTL_CHILD_"
            } else {
                ""
            }
        setTestEnvironment(
            task = executingTask,
            environment = mapOf(
                "$environmentPrefix$FIXTURE_RPC_URL_ENV" to minecraftTestFixtureConnection.rpcUrl,
                "$environmentPrefix$FIXTURE_OWNER_ID_ENV" to minecraftTestFixtureConnection.ownerId,
            ),
        )
    }
}

private fun Task.isKotlinNativeSimulatorTest(): Boolean {
    var taskClass: Class<*>? = javaClass
    while (taskClass != null) {
        if (taskClass.name == KOTLIN_NATIVE_SIMULATOR_TEST_CLASS) return true
        taskClass = taskClass.superclass
    }
    return false
}

private fun setTestEnvironment(
    task: Task,
    environment: Map<String, String>,
) {
    val methods = task.javaClass.methods.filter { method ->
        method.name == "environment" &&
                method.parameterTypes.firstOrNull() == String::class.java
    }
    val untracked = methods.firstOrNull { it.parameterCount == 3 }
    val ordinary = methods.firstOrNull { it.parameterCount == 2 }
    check(untracked != null || ordinary != null) {
        "Unsupported Minecraft fixture test task ${task.javaClass.name}"
    }
    environment.forEach { (name, value) ->
        if (untracked != null) {
            untracked.invoke(task, name, value, false)
        } else {
            checkNotNull(ordinary).invoke(task, name, value)
        }
    }
}
