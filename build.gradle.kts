import com.hiczp.minecraft.protocol.buildScript.applyMinecraftTestFixtureServiceConvention
import com.hiczp.minecraft.protocol.buildScript.applyOfficialDownloadsConvention
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.testing.mocha.KotlinMocha
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    id("java-base")
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinxRpc) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}

group = "com.hiczp"
version = "0.0.1"

plugins.withType<YarnPlugin> {
    extensions.configure<YarnRootExtension> {
        lockFileDirectory = layout.buildDirectory.dir("kotlin-js-store/js").get().asFile
    }
}
plugins.withType<WasmYarnPlugin> {
    extensions.configure<WasmYarnRootExtension> {
        lockFileDirectory = layout.buildDirectory.dir("kotlin-js-store/wasm").get().asFile
    }
    val wasmNpmInstall = extensions.getByType<WasmNodeJsRootExtension>().npmInstallTaskProvider
    plugins.withType<YarnPlugin> {
        val jsNpmInstall = extensions.getByType<NodeJsRootExtension>().npmInstallTaskProvider
        wasmNpmInstall.configure { mustRunAfter(jsNpmInstall) }
    }
}

val officialMinecraftFixtures = applyOfficialDownloadsConvention()
applyMinecraftTestFixtureServiceConvention(officialMinecraftFixtures)

subprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<Test>().configureEach {
        systemProperty("kotlinx.coroutines.test.default_timeout", "2m")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        tasks.withType<KotlinJsTest>().configureEach {
            onTestFrameworkSet {
                (this as? KotlinMocha)?.timeout = "30s"
            }
        }
    }
}
