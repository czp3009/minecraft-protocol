import com.hiczp.minecraft.protocol.buildScript.OfficialDownloadsExtension
import com.hiczp.minecraft.protocol.buildScript.applyOfficialDownloadsConvention
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    id("java-base")
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}

group = "com.hiczp"
version = "0.0.1"

plugins.withType<YarnPlugin> {
    extensions.configure<YarnRootExtension> {
        lockFileDirectory =
            layout.buildDirectory.dir("kotlin-js-store/js").get().asFile
    }
}
plugins.withType<WasmYarnPlugin> {
    extensions.configure<WasmYarnRootExtension> {
        lockFileDirectory =
            layout.buildDirectory.dir("kotlin-js-store/wasm").get().asFile
    }
    val wasmNpmInstall = extensions
        .getByType<WasmNodeJsRootExtension>()
        .npmInstallTaskProvider
    plugins.withType<YarnPlugin> {
        val jsNpmInstall = extensions
            .getByType<NodeJsRootExtension>()
            .npmInstallTaskProvider
        wasmNpmInstall.configure { mustRunAfter(jsNpmInstall) }
    }
}

// ── Download task chain and analysis tasks ────────────────────────
val official = applyOfficialDownloadsConvention()

// ── Subproject DSL: wire test dependencies to download tasks ─────
subprojects {
    group = rootProject.group
    version = rootProject.version

    val ext = extensions.create(
        "officialDownloads", OfficialDownloadsExtension::class.java,
    )
    afterEvaluate {
        if (ext.needsServer || ext.needsClient ||
            ext.needsHeadlessMc || ext.needsCodecOracle
        ) {
            tasks.withType(Test::class.java).configureEach {
                if (ext.needsServer) dependsOn(official.downloadServer)
                if (ext.needsClient) {
                    dependsOn(official.downloadClient)
                    dependsOn(official.downloadAssets)
                }
                if (ext.needsHeadlessMc) {
                    dependsOn(official.downloadHeadlessMc)
                    dependsOn(official.prepareHeadlessMc)
                }
                if (ext.needsCodecOracle) {
                    dependsOn(official.extractServerRuntime)
                    dependsOn(official.compileCodecOracle)
                }
            }
        }
    }
}
