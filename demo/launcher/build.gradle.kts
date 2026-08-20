import com.hiczp.minecraft.buildlogic.BuildVersions
import com.hiczp.minecraft.buildlogic.JvmProcessArguments
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktorfit)
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    applyDefaultHierarchyTemplate()

    jvm {
        binaries {
            executable {
                mainClass = "com.hiczp.minecraft.demo.launcher.MainKt"
                applicationDefaultJvmArgs.add(JvmProcessArguments.ENABLE_NATIVE_ACCESS_ALL_UNNAMED)
            }
        }
    }

    mingwX64()
    linuxX64()
    linuxArm64()
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable(listOf(NativeBuildType.RELEASE)) {
            entryPoint = "com.hiczp.minecraft.demo.launcher.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":account-auth"))
            implementation(project(":protocol-auth"))
            implementation(libs.mosaic.runtime)
            implementation(libs.kommand)
            implementation(libs.ktorfit.lib.light)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            // Ktor uses SLF4J on JVM. Its NOP provider suppresses provider warnings and all logs so Mosaic owns stdout.
            implementation(libs.slf4j.nop)
        }

        linuxMain.dependencies {
            implementation(libs.ktor.client.curl)
        }

        macosArm64Main.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        mingwX64Main.dependencies {
            implementation(libs.ktor.client.winhttp)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.server.test.host)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

val konanDataDirectory = providers.gradleProperty("konan.data.dir")
    .orElse(providers.systemProperty("user.home").map { "$it/.konan" })
val mingwRuntimeDirectory = konanDataDirectory.map { "$it/dependencies/msys2-mingw-w64-x86_64-2/bin" }
val mingwRuntimePath = mingwRuntimeDirectory.zip(providers.environmentVariable("PATH").orElse("")) { directory, path ->
    "$directory;$path"
}

tasks.named<KotlinNativeTest>("mingwX64Test") {
    environment("PATH", mingwRuntimePath.get())
}

tasks.register<Sync>("installMingwX64Executable") {
    group = "distribution"
    description = "Installs the Windows x64 executable with its MinGW runtime DLLs."
    dependsOn("linkReleaseExecutableMingwX64")
    from(layout.buildDirectory.dir("bin/mingwX64/releaseExecutable")) {
        include("launcher.exe")
    }
    from(mingwRuntimeDirectory) {
        include("libstdc++-6.dll", "libgcc_s_seh-1.dll", "libwinpthread-1.dll")
    }
    into(layout.buildDirectory.dir("install/launcher-mingwX64"))
}
