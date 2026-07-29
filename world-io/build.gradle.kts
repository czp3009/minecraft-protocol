import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets(
        namespace = "com.hiczp.minecraft.world.io",
        includeWasmWasi = false,
        includeJs = false,
        includeWasmJs = false,
    )

    sourceSets {
        commonMain.dependencies {
            api(project(":world-format"))
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val jvmTestCompilation = kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")

tasks.register<Test>("worldIoLayerTest") {
    group = "verification"
    description = "Test random-access region files and world filesystem helpers."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
}

val protocolSnapshotText = rootProject.file(
    "protocol-specification/wiki-protocol-snapshot.json",
).readText()
val analysisJavaVersion = Regex("\"java_major_version\"\\s*:\\s*(\\d+)")
    .find(protocolSnapshotText)
    ?.groupValues
    ?.get(1)
    ?.toInt()
    ?: error("Wiki protocol snapshot does not declare java_major_version")
val protocolMinecraftVersion =
    Regex("\"minecraft_version\"\\s*:\\s*\"([^\"]+)\"")
        .find(protocolSnapshotText)
        ?.groupValues
        ?.get(1)
        ?: error("Wiki protocol snapshot does not declare minecraft_version")
val analysisJavaLauncher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(analysisJavaVersion))
    }

tasks.register<JavaExec>("officialWorldStorageInteropTest") {
    group = "verification"
    description =
        "Rewrite an official world and require the exact server to load it."
    dependsOn(
        jvmTestCompilation.compileTaskProvider,
        rootProject.tasks.named("downloadOfficialMinecraftServer"),
    )
    classpath(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.world.io.OfficialWorldStorageInteropRunner",
    )
    args(
        analysisJavaLauncher.get().executablePath.asFile.absolutePath,
        rootProject.file(
            "build/protocol-reference/mojang/" +
                    "$protocolMinecraftVersion/server.jar",
        ).absolutePath,
        rootProject.file(
            "build/protocol-reference/official-world-storage/" +
                    protocolMinecraftVersion,
        ).absolutePath,
        rootProject.file(
            "build/reports/protocol-update/official-world-storage.json",
        ).absolutePath,
    )
    outputs.upToDateWhen { false }
}
