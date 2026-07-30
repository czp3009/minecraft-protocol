import com.hiczp.minecraft.protocol.buildScript.*
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    id("java-base")
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
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
}

val minecraftVersion = MinecraftTarget.version
val officialServerDirectory = layout.buildDirectory.dir(
    "protocol-reference/mojang/$minecraftVersion",
)
val officialServerJar = officialServerDirectory.map {
    it.file("server.jar")
}
val officialServerMetadata = officialServerDirectory.map {
    it.file("download-metadata.json")
}
val officialReportsDirectory = officialServerDirectory.map {
    it.dir("generated")
}
val officialRuntimeDirectory = officialServerDirectory.map {
    it.dir("runtime")
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val java25Compiler = javaToolchains.compilerFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.register<PrintMinecraftVersionTask>("minecraftVersion") {
    group = "help"
    description = "Print the official Minecraft release selected in buildSrc."
    this.minecraftVersion.set(MinecraftTarget.version)
}

val downloadOfficialMinecraftServer =
    tasks.register<DownloadOfficialMinecraftServerTask>(
        "downloadOfficialMinecraftServer",
    ) {
        repositoryDirectory.set(layout.projectDirectory)
        this.minecraftVersion.set(MinecraftTarget.version)
        offline.set(gradle.startParameter.isOffline)
        serverJar.set(officialServerJar)
        metadataFile.set(officialServerMetadata)
    }

val generateOfficialMinecraftReports =
    tasks.register<GenerateOfficialMinecraftReportsTask>(
        "generateOfficialMinecraftReports",
    ) {
        dependsOn(downloadOfficialMinecraftServer)
        repositoryDirectory.set(layout.projectDirectory)
        javaExecutable.set(
            java25Launcher.map {
                it.executablePath.asFile.absolutePath
            },
        )
        serverJar.set(officialServerJar)
        downloadMetadata.set(officialServerMetadata)
        outputDirectory.set(officialReportsDirectory)
    }

val unpackOfficialMinecraftServer =
    tasks.register<UnpackOfficialMinecraftServerTask>(
        "unpackOfficialMinecraftServer",
    ) {
        dependsOn(downloadOfficialMinecraftServer)
        repositoryDirectory.set(layout.projectDirectory)
        this.minecraftVersion.set(MinecraftTarget.version)
        serverJar.set(officialServerJar)
        runtimeDirectory.set(officialRuntimeDirectory)
    }

val officialServerProperties =
    tasks.register<GenerateOfficialServerPropertiesTask>(
        "generateOfficialServerProperties",
    ) {
        dependsOn(downloadOfficialMinecraftServer)
        repositoryDirectory.set(layout.projectDirectory)
        javaExecutable.set(
            java25Launcher.map {
                it.executablePath.asFile.absolutePath
            },
        )
        serverJar.set(officialServerJar)
        reportFile.set(
            layout.buildDirectory.file(
                "generated/protocol-specification/server-properties.json",
            ),
        )
    }

val prepareOfficialMinecraftClient =
    tasks.register<PrepareOfficialMinecraftClientTask>(
        "prepareOfficialMinecraftClient",
    ) {
        dependsOn(downloadOfficialMinecraftServer)
        repositoryDirectory.set(layout.projectDirectory)
        minecraftVersion.set(MinecraftTarget.version)
        offline.set(gradle.startParameter.isOffline)
        clientDirectory.set(
            layout.buildDirectory.dir(
                "protocol-reference/mojang-client/" +
                        MinecraftTarget.version,
            ),
        )
    }

val headlessMinecraftLauncherVersion = "2.10.0"
extra["headlessMinecraftLauncherVersion"] =
    headlessMinecraftLauncherVersion
val headlessMinecraftLauncher = layout.buildDirectory.file(
    "protocol-reference/headlessmc/$headlessMinecraftLauncherVersion/" +
            "headlessmc-launcher-$headlessMinecraftLauncherVersion.jar",
)
val downloadHeadlessMinecraftLauncher =
    tasks.register<DownloadHeadlessMinecraftLauncherTask>(
        "downloadHeadlessMinecraftLauncher",
    ) {
        version.set(headlessMinecraftLauncherVersion)
        downloadUrl.set(
            "https://github.com/headlesshq/headlessmc/releases/download/" +
                    "$headlessMinecraftLauncherVersion/" +
                    "headlessmc-launcher-$headlessMinecraftLauncherVersion.jar",
        )
        expectedSize.set(13_010_386L)
        expectedSha256.set(
            "52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd",
        )
        offline.set(gradle.startParameter.isOffline)
        launcherJar.set(headlessMinecraftLauncher)
    }

val officialCodecOracleClasses = layout.buildDirectory.dir(
    "generated/official-codec-oracle/classes",
)
val officialServerRuntimeClasspath = files(
    officialRuntimeDirectory.map { it.file("server.jar") },
    officialRuntimeDirectory.map {
        fileTree(it.dir("libraries")) {
            include("**/*.jar")
        }
    },
)
tasks.register<JavaCompile>("compileOfficialCodecOracle") {
    dependsOn(unpackOfficialMinecraftServer)
    source(
        layout.projectDirectory.dir(
            "buildSrc/src/officialCodecOracle/java",
        ).asFileTree.matching {
            include("**/*.java")
        },
    )
    classpath = officialServerRuntimeClasspath
    destinationDirectory.set(officialCodecOracleClasses)
    javaCompiler.set(java25Compiler)
    options.release.set(25)
}

val generatedProtocolSpecification =
    tasks.register<GenerateProtocolSpecificationTask>(
        "generateProtocolSpecification",
    ) {
        dependsOn(
            generateOfficialMinecraftReports,
            officialServerProperties,
            ":protocol-serialization:generateVanillaConfigurationData",
        )
        repositoryDirectory.set(layout.projectDirectory)
        serverJar.set(officialServerJar)
        downloadMetadata.set(officialServerMetadata)
        packetsReport.set(
            officialReportsDirectory.map {
                it.file("reports/packets.json")
            },
        )
        registriesReport.set(
            officialReportsDirectory.map {
                it.file("reports/registries.json")
            },
        )
        blocksReport.set(
            officialReportsDirectory.map {
                it.file("reports/blocks.json")
            },
        )
        serverPropertiesReport.set(officialServerProperties.flatMap {
            it.reportFile
        })
        configurationReport.set(
            project(":protocol-serialization").layout.buildDirectory.file(
                "generated/vanilla-configuration/configuration.json",
            ),
        )
        outputDirectory.set(
            layout.buildDirectory.dir(
                "generated/protocol-specification/complete",
            ),
        )
    }

val refreshProtocolSpecification =
    tasks.register<Sync>("refreshProtocolSpecification") {
        group = "minecraft"
        description =
            "Regenerate checked-in evidence from the selected official server."
        from(generatedProtocolSpecification)
        into(layout.projectDirectory.dir("protocol-specification"))
    }

val buildLogicTest = tasks.register<GradleBuild>("buildLogicTest") {
    buildName = "buildLogicVerification"
    dir = file("buildSrc")
    tasks = listOf("test")
}

val test = tasks.register("test") {
    group = "verification"
    description =
        "Run every non-GUI test through the standard multiplatform test tasks."
    dependsOn(buildLogicTest)
    dependsOn(subprojects.map { "${it.path}:allTests" })
}

tasks.named("check") {
    dependsOn(test)
}

tasks.named<Delete>("clean") {
    dependsOn("cleanRefreshProtocolSpecification")
    dependsOn(subprojects.map { "${it.path}:clean" })
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}
