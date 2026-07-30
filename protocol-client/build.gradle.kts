import com.hiczp.minecraft.protocol.buildScript.MinecraftTarget
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

val java25Launcher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }

tasks.named<Test>("jvmTest") {
    dependsOn(rootProject.tasks.named("downloadOfficialMinecraftServer"))
    systemProperty(
        "minecraft.protocol.java",
        java25Launcher.get().executablePath.asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.serverJar",
        rootProject.layout.buildDirectory.file(
            "protocol-reference/mojang/${MinecraftTarget.version}/server.jar",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.clientInteropWork",
        layout.buildDirectory.dir(
            "test-runtimes/official-server/${MinecraftTarget.version}",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.clientInteropReport",
        layout.buildDirectory.file(
            "reports/tests/official-server-client.json",
        ).get().asFile.absolutePath,
    )
}
