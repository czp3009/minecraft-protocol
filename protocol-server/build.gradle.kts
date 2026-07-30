import com.hiczp.minecraft.protocol.buildScript.MinecraftTarget
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

val java25Launcher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
val headlessMinecraftLauncherVersion =
    rootProject.extra["headlessMinecraftLauncherVersion"] as String

tasks.named<Test>("jvmTest") {
    dependsOn(
        rootProject.tasks.named("prepareOfficialMinecraftClient"),
        rootProject.tasks.named("downloadHeadlessMinecraftLauncher"),
    )
    systemProperty(
        "minecraft.protocol.java",
        java25Launcher.get().executablePath.asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.clientDirectory",
        rootProject.layout.buildDirectory.dir(
            "protocol-reference/mojang-client/${MinecraftTarget.version}",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.version",
        MinecraftTarget.version,
    )
    systemProperty(
        "minecraft.protocol.headlessClientWork",
        layout.buildDirectory.dir(
            "test-runtimes/official-client/${MinecraftTarget.version}",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.headlessClientReport",
        layout.buildDirectory.file(
            "reports/tests/official-client-headless.json",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.headlessLauncher",
        rootProject.layout.buildDirectory.file(
            "protocol-reference/headlessmc/" +
                    "$headlessMinecraftLauncherVersion/" +
                    "headlessmc-launcher-$headlessMinecraftLauncherVersion.jar",
        ).get().asFile.absolutePath,
    )
}
