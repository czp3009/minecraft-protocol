import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets(
        namespace = "com.hiczp.minecraft.protocol.client",
        includeWasmWasi = false,
        includeWasmJsD8 = false,
    )

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            api(project(":protocol-session"))
            api(project(":protocol-transport"))
            api(project(":protocol-auth"))
            api(libs.ktor.network)
            implementation(project(":protocol-vanilla-data"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

val jvmTestCompilation = kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")

tasks.register<Test>("clientLayerTest") {
    group = "verification"
    description =
        "Test client Status, offline Login, Configuration, hooks, and Play entry."
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
val protocolMinecraftVersion = Regex("\"minecraft_version\"\\s*:\\s*\"([^\"]+)\"")
    .find(protocolSnapshotText)
    ?.groupValues
    ?.get(1)
    ?: error("Wiki protocol snapshot does not declare minecraft_version")
val analysisJavaLauncher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(analysisJavaVersion))
    }

tasks.register<JavaExec>("officialServerClientInteropTest") {
    group = "verification"
    description =
        "Run the production Ktor client through Status and Play against vanilla."
    dependsOn(
        jvmTestCompilation.compileTaskProvider,
        rootProject.tasks.named("downloadOfficialMinecraftServer"),
    )
    classpath(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.protocol.client.OfficialServerClientInteropRunner",
    )
    args(
        analysisJavaLauncher.get().executablePath.asFile.absolutePath,
        rootProject.file(
            "build/protocol-reference/mojang/$protocolMinecraftVersion/server.jar",
        ).absolutePath,
        rootProject.file(
            "build/protocol-reference/official-client-interop/" +
                    protocolMinecraftVersion,
        ).absolutePath,
        rootProject.file(
            "build/reports/protocol-update/official-client-interop.json",
        ).absolutePath,
    )
    outputs.upToDateWhen { false }
}
