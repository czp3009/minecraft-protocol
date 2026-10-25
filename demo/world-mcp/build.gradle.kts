import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    applyDefaultHierarchyTemplate()
    mingwX64()
    linuxX64()
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "world-mcp"
            entryPoint = "com.hiczp.minecraft.demo.worldmcp.main"
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":world-io"))
            implementation(project(":protocol-configuration-vanilla"))
            implementation(project(":datapack-vanilla"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.mcp.server)
            implementation(libs.ktor.server.cio)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mcp.client)
            implementation(libs.ktor.server.test.host)
        }
    }
}

tasks.withType<Exec>().configureEach {
    workingDir(rootProject.layout.projectDirectory)
}
