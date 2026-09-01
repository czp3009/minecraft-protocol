import com.hiczp.minecraft.buildlogic.BuildVersions
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinxRpc)
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(BuildVersions.JAVA_VERSION)
    applyDefaultHierarchyTemplate()

    jvm {
        binaries {
            executable {
                mainClass = "com.hiczp.minecraft.demo.webmap.MainKt"
            }
        }
    }

    mingwX64()
    linuxX64()
    linuxArm64()
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "web-map"
            entryPoint = "com.hiczp.minecraft.demo.webmap.main"
        }
    }

    js {
        browser {
            commonWebpackConfig {
                outputFileName = "web-map.js"
            }
        }
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val serverMain = create("serverMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":protocol-datapack"))
                implementation(project(":protocol-datapack-vanilla"))
                implementation(project(":world-io"))
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.rpc.krpc.ktor.server)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.kotlin.logging)
            }
        }
        jvmMain {
            dependsOn(serverMain)
            dependencies {
                implementation(libs.slf4j.simple)
            }
        }
        nativeMain {
            dependsOn(serverMain)
        }

        commonMain.dependencies {
            api(project(":protocol-model"))
            api(project(":world-format"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.rpc.core)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.ktor.client.js)
            implementation(npm("leaflet", libs.versions.leaflet.get()))
            implementation(npm("@zip.js/zip.js", libs.versions.zip.js.get()))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

val browserDistribution = tasks.named("jsBrowserDistribution")
val stagedWebDirectory = layout.buildDirectory.dir("staged-web")
val stageWebAssets = tasks.register<Sync>("stageWebAssets") {
    group = "distribution"
    description = "Stages the production browser distribution for the web-map servers."
    from(browserDistribution)
    into(stagedWebDirectory)
}

tasks.withType<JavaExec>().configureEach {
    if (name.contains("run", ignoreCase = true)) {
        dependsOn(stageWebAssets)
        inputs.dir(stagedWebDirectory)
        environment("MINECRAFT_WEB_ROOT", stagedWebDirectory.get().asFile.absolutePath)
    }
}

tasks.withType<Exec>().configureEach {
    if (name.startsWith("run") && name.contains("Executable")) {
        dependsOn(stageWebAssets)
        inputs.dir(stagedWebDirectory)
        environment("MINECRAFT_WEB_ROOT", stagedWebDirectory.get().asFile.absolutePath)
    }
}
