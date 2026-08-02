import com.hiczp.minecraft.protocol.buildScript.*
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
        // Both KGP Yarn 1 installs use the same process-wide network mutex.
        wasmNpmInstall.configure {
            mustRunAfter(jsNpmInstall)
        }
    }
}

val minecraftVersion = MinecraftTarget.MINECRAFT_VERSION
val officialServerDirectory = layout.buildDirectory.dir(
    "protocol-reference/mojang/$minecraftVersion",
)
val officialServerJar = officialServerDirectory.map {
    it.file("server.jar")
}
val officialServerMetadata = officialServerDirectory.map {
    it.file("download-metadata.json")
}
val officialAnalysisDirectory = layout.buildDirectory.dir(
    "generated/official-minecraft/$minecraftVersion",
)
val officialTargetFile = officialAnalysisDirectory.map {
    it.file("target/target.json")
}
val officialReportsDirectory = officialAnalysisDirectory.map {
    it.dir("data-generator-reports")
}
val officialConfigurationFile = officialAnalysisDirectory.map {
    it.file("configuration/configuration.json")
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val java25Launcher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}

tasks.register<PrintMinecraftVersionTask>("minecraftVersion") {
    group = "help"
    description = "Print the official Minecraft release selected in buildSrc."
    this.minecraftVersion = MinecraftTarget.MINECRAFT_VERSION
}

val downloadOfficialMinecraftServer =
    tasks.register<DownloadOfficialMinecraftServerTask>(
        "downloadOfficialMinecraftServer",
    ) {
        group = "official minecraft"
        description = "Download and verify the selected official Minecraft server JAR."
        offline = gradle.startParameter.isOffline
        serverJar = officialServerJar
        metadataFile = officialServerMetadata
    }

val analyzeOfficialMinecraftTarget =
    tasks.register<AnalyzeOfficialMinecraftTargetTask>(
        "analyzeOfficialMinecraftTarget",
    ) {
        group = "official minecraft analysis"
        description = "Analyze version and protocol facts in the official server JAR."
        serverJar = downloadOfficialMinecraftServer.flatMap {
            it.serverJar
        }
        downloadMetadata = downloadOfficialMinecraftServer.flatMap {
            it.metadataFile
        }
        outputFile = officialTargetFile
    }

val analyzeOfficialMinecraftReports =
    tasks.register<AnalyzeOfficialMinecraftReportsTask>(
        "analyzeOfficialMinecraftReports",
    ) {
        group = "official minecraft analysis"
        description = "Capture official packets, registries, and blocks reports."
        javaExecutable =
            java25Launcher.map {
                it.executablePath.asFile.absolutePath
            }
        serverJar = downloadOfficialMinecraftServer.flatMap {
            it.serverJar
        }
        downloadMetadata = downloadOfficialMinecraftServer.flatMap {
            it.metadataFile
        }
        outputDirectory = officialReportsDirectory
    }

val analyzeOfficialMinecraftConfiguration =
    tasks.register<AnalyzeOfficialMinecraftConfigurationTask>(
        "analyzeOfficialMinecraftConfiguration",
    ) {
        group = "official minecraft analysis"
        description = "Capture both official Configuration Known Packs branches."
        javaExecutable =
            java25Launcher.map {
                it.executablePath.asFile.absolutePath
            }
        serverJar = downloadOfficialMinecraftServer.flatMap {
            it.serverJar
        }
        packetsReport =
            analyzeOfficialMinecraftReports.flatMap {
                it.outputDirectory.file("reports/packets.json")
            }
        outputFile = officialConfigurationFile
    }

tasks.register("officialMinecraftAnalysis") {
    group = "official minecraft analysis"
    description = "Run every official Minecraft analysis task."
    dependsOn(
        analyzeOfficialMinecraftTarget,
        analyzeOfficialMinecraftReports,
        analyzeOfficialMinecraftConfiguration,
    )
}

publishOfficialMinecraftAnalysis(
    "officialMinecraftTarget",
    analyzeOfficialMinecraftTarget.flatMap { it.outputFile },
    analyzeOfficialMinecraftTarget,
)
publishOfficialMinecraftAnalysis(
    "officialMinecraftReports",
    analyzeOfficialMinecraftReports.flatMap { it.outputDirectory },
    analyzeOfficialMinecraftReports,
    directory = true,
)
publishOfficialMinecraftAnalysis(
    "officialMinecraftConfiguration",
    analyzeOfficialMinecraftConfiguration.flatMap { it.outputFile },
    analyzeOfficialMinecraftConfiguration,
)

subprojects {
    group = rootProject.group
    version = rootProject.version
}
