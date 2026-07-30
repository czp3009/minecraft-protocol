import com.hiczp.minecraft.protocol.buildScript.MinecraftTarget
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
        "minecraft.world.officialInteropWork",
        layout.buildDirectory.dir(
            "test-runtimes/official-world/${MinecraftTarget.version}",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.world.officialInteropReport",
        layout.buildDirectory.file(
            "reports/tests/official-world-storage.json",
        ).get().asFile.absolutePath,
    )
}
