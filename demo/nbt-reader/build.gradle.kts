import com.hiczp.minecraft.buildlogic.BuildVersions
import org.apache.tools.ant.types.Commandline
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    applyDefaultHierarchyTemplate()

    jvm {
        mainRun {
            mainClass = "com.hiczp.minecraft.demo.nbtreader.MainKt"
        }
    }

    mingwX64()
    linuxX64()
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "nbt-reader"
            entryPoint = "com.hiczp.minecraft.demo.nbtreader.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":nbt-serialization"))
            implementation(libs.kotlinx.cli)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.okio.fakefilesystem)
        }
    }
}

// Native Exec tasks have no --args option. Share a quoted argument property with the JVM task.
val readerArguments = providers.gradleProperty("nbtReaderArgs")
    .map { Commandline.translateCommandline(it).toList() }
    .orElse(listOf("--help"))
val repositoryDirectory = rootProject.layout.projectDirectory

tasks.withType<JavaExec>().configureEach {
    workingDir(repositoryDirectory)
    args(readerArguments.get())
}

tasks.withType<Exec>().configureEach {
    workingDir(repositoryDirectory)
    args(readerArguments.get())
}
