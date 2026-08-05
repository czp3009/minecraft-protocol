@file:Suppress("NOTHING_TO_INLINE")

package com.hiczp.minecraft.buildlogic

import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.AbstractTestTask
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Supplies the Gradle-managed Fixture Host to this module's standard tests. */
inline fun KotlinMultiplatformExtension.useMinecraftTestFixtures(
    requiresOfficialServer: Boolean = false,
    requiresOfficialClient: Boolean = false,
    requiresCodecOracle: Boolean = false,
) {
    require(requiresOfficialServer || requiresOfficialClient || requiresCodecOracle) {
        "At least one Minecraft fixture capability must be requested"
    }
    val owningProject = project
    val fixtureOutputs = owningProject.rootProject.extensions.getByType(
        OfficialMinecraftFixtureOutputs::class.java,
    )
    val fixtureInfrastructure = owningProject.rootProject.extensions.getByType(
        MinecraftTestFixtureInfrastructure::class.java,
    )
    val fixtureInputs = owningProject.files().apply {
        if (requiresOfficialServer) from(fixtureOutputs.officialServer)
        if (requiresOfficialClient) from(fixtureOutputs.officialClient)
        if (requiresCodecOracle) from(fixtureOutputs.codecOracle)
    }

    owningProject.tasks.withType(AbstractTestTask::class.java).configureEach { task ->
        if (
            task.name.startsWith("wasmWasi", ignoreCase = true) ||
            task.name.endsWith("BrowserTest", ignoreCase = true)
        ) {
            task.filter.excludeTestsMatching("*Official*")
        } else {
            registerMinecraftTestFixtures(task, fixtureInputs, fixtureInfrastructure)
        }
    }
}

@PublishedApi
internal fun registerMinecraftTestFixtures(
    task: AbstractTestTask,
    fixtureInputs: FileCollection,
    fixtureInfrastructure: MinecraftTestFixtureInfrastructure,
) {
    // Provider provenance makes Gradle prepare these inputs before the host is
    // first requested by the executing test task.
    task.inputs.files(fixtureInputs)
        .withPropertyName("officialMinecraftFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    task.inputs.files(fixtureInfrastructure.hostClasspath)
        .withPropertyName("minecraftTestFixtureHostClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    task.usesService(fixtureInfrastructure.service)
    val taskPath = task.path
    task.doFirst { executingTask ->
        val connection = fixtureInfrastructure.service.get().connectionFor(taskPath)
        setTestEnvironment(
            task = executingTask,
            environment = mapOf(
                FIXTURE_RPC_URL_ENV to connection.rpcUrl,
                FIXTURE_OWNER_ID_ENV to connection.ownerId,
            ),
        )
    }
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
