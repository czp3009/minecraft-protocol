import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets(
        namespace = "com.hiczp.minecraft.protocol.server",
        includeWasmWasi = false,
        includeWasmJsD8 = false,
    )

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            api(project(":protocol-session"))
            api(project(":protocol-transport"))
            api(project(":protocol-auth"))
            api(project(":protocol-vanilla-data"))
            api(libs.ktor.network)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":protocol-client"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

val jvmTestCompilation = kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")

tasks.register<Test>("serverLayerTest") {
    group = "verification"
    description =
        "Test Ktor server accept, Status, offline Login, vanilla Configuration, and Play."
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
val minecraftClientDirectory = providers
    .gradleProperty("minecraftClientDirectory")
    .orElse(
        providers.provider {
            rootProject.file(
                "build/protocol-reference/mojang-client/" +
                        protocolMinecraftVersion,
            ).absolutePath
        },
    )
val minecraftClientJavaExecutable = providers
    .gradleProperty("minecraftClientJavaExecutable")
    .orElse(
        analysisJavaLauncher.map {
            it.executablePath.asFile.absolutePath
        },
    )

tasks.register<JavaExec>("officialClientToServerEndToEndTest") {
    group = "verification"
    description =
        "Launch the desktop vanilla client and verify chunks and entities."
    dependsOn(
        jvmTestCompilation.compileTaskProvider,
        rootProject.tasks.named("prepareOfficialMinecraftClient"),
        rootProject.tasks.named("verifyPreparedOfficialMinecraftClient"),
    )
    classpath(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.protocol.server.OfficialClientEndToEndRunner",
    )
    args(
        minecraftClientJavaExecutable.get(),
        minecraftClientDirectory.get(),
        protocolMinecraftVersion,
        rootProject.file(
            "build/protocol-reference/official-client-e2e/" +
                    protocolMinecraftVersion,
        ).absolutePath,
        rootProject.file(
            "build/reports/protocol-update/official-client-e2e.json",
        ).absolutePath,
    )
    outputs.upToDateWhen { false }
}

val headlessMinecraftLauncherVersion =
    rootProject.extra["headlessMinecraftLauncherVersion"] as String
val headlessMinecraftLauncher = rootProject.layout.buildDirectory.file(
    "protocol-reference/headlessmc/$headlessMinecraftLauncherVersion/" +
            "headlessmc-launcher-$headlessMinecraftLauncherVersion.jar",
)

tasks.register<JavaExec>("headlessOfficialClientToServerEndToEndTest") {
    group = "verification"
    description =
        "Launch vanilla without a display and verify initial chunks and entities."
    dependsOn(
        jvmTestCompilation.compileTaskProvider,
        rootProject.tasks.named("prepareOfficialMinecraftClient"),
        rootProject.tasks.named("verifyPreparedOfficialMinecraftClient"),
        rootProject.tasks.named("downloadHeadlessMinecraftLauncher"),
    )
    classpath(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.protocol.server.OfficialClientEndToEndRunner",
    )
    args(
        minecraftClientJavaExecutable.get(),
        minecraftClientDirectory.get(),
        protocolMinecraftVersion,
        rootProject.file(
            "build/protocol-reference/official-client-headless-e2e/" +
                    protocolMinecraftVersion,
        ).absolutePath,
        rootProject.file(
            "build/reports/protocol-update/" +
                    "headless-official-client-e2e.json",
        ).absolutePath,
        headlessMinecraftLauncher.get().asFile.absolutePath,
    )
    outputs.upToDateWhen { false }
}
